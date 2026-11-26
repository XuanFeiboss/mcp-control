package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal, dependency-free HTTP/1.1 server that exposes the MCP transports used by
 * common clients:
 *
 * <ul>
 *     <li>Streamable HTTP: POST/GET/DELETE {@code /mcp}</li>
 *     <li>Legacy HTTP+SSE: GET {@code /sse}, POST {@code /message?sessionId=...}</li>
 * </ul>
 *
 * <p>Only <code>java.base</code> and Gson are used on purpose: Minecraft servers are launched
 * in a module layer where optional JDK modules such as {@code jdk.httpserver} are not
 * guaranteed to be resolved.</p>
 */
public final class McpHttpServer implements AutoCloseable {

    public static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/HTTP");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String CRLF = "\r\n";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_HEADER_LINES = 64;
    private static final int MAX_SESSIONS = 128;

    /** Handles JSON-RPC messages. Returns a response object, or {@code null} for notifications. */
    public interface Backend {
        JsonObject handle(JsonObject message, String sessionId, boolean legacySse);
    }

    private final String bindAddress;
    private final int requestedPort;
    private final String serverName;
    private final String serverVersion;
    private final Backend backend;

    private volatile String token;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private ExecutorService connectionPool;
    private ScheduledExecutorService scheduler;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public McpHttpServer(String bindAddress, int port, String token, String serverName,
                         String serverVersion, Backend backend) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.token = token == null ? "" : token;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.backend = backend;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, requestedPort), 64);
        this.serverSocket = socket;
        this.running = true;

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mcp-control-http");
            thread.setDaemon(true);
            return thread;
        };
        this.connectionPool = Executors.newCachedThreadPool(factory);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-control-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::heartbeat, 25, 25, TimeUnit.SECONDS);
        this.scheduler.scheduleAtFixedRate(this::cleanupSessions, 60, 60, TimeUnit.SECONDS);

        this.acceptThread = new Thread(this::acceptLoop, "mcp-control-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LOGGER.info("MCP HTTP listening on http://{}:{}/mcp", bindAddress, getPort());
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? requestedPort : socket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public int getSessionCount() {
        return sessions.size();
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            session.closeSse();
        }
        sessions.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (connectionPool != null) {
            connectionPool.shutdownNow();
        }
        LOGGER.info("MCP HTTP server stopped");
    }

    // ------------------------------------------------------------------
    // Accept / connection handling
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionPool.execute(() -> handleConnection(socket));
            } catch (SocketException e) {
                if (running) {
                    LOGGER.warn("MCP HTTP accept failed: {}", e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    LOGGER.warn("MCP HTTP accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket ignored = socket) {
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            boolean keepAlive = true;
            while (running && keepAlive) {
                Request request;
                try {
                    request = readRequest(in);
                } catch (SocketTimeoutException timeout) {
                    break;
                }
                if (request == null) {
                    break;
                }
                keepAlive = dispatch(request, out);
            }
        } catch (IOException ignored) {
            // Client disconnected; nothing to do.
        } catch (RuntimeException e) {
            LOGGER.warn("MCP HTTP connection error", e);
        }
    }

    private boolean dispatch(Request request, OutputStream out) throws IOException {
        Map<String, String> extra = corsHeaders();
        String path = request.path;

        if ("OPTIONS".equals(request.method)) {
            writeStatusOnly(out, 204, "No Content", extra, true);
            return true;
        }

        if (path.equals("/health")) {
            JsonObject health = new JsonObject();
            health.addProperty("status", "ok");
            health.addProperty("name", serverName);
            health.addProperty("version", serverVersion);
            health.addProperty("sessions", sessions.size());
            health.addProperty("uptime", System.currentTimeMillis());
            writeJson(out, 200, "OK", health, extra, true);
            return true;
        }

        if (!authorized(request)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "unauthorized");
            error.addProperty("message", "Missing or invalid bearer token. Use 'Authorization: Bearer <token>' or '?token=<token>'.");
            writeJson(out, 401, "Unauthorized", error, extra, true);
            return true;
        }

        switch (path) {
            case "/mcp":
                return handleMcp(request, out, extra);
            case "/sse":
                return handleLegacySse(request, out, extra);
            case "/message":
                return handleLegacyMessage(request, out, extra);
            default:
                JsonObject notFound = new JsonObject();
                notFound.addProperty("error", "not_found");
                notFound.addProperty("message", "Unknown endpoint. Use /mcp (Streamable HTTP) or /sse (legacy SSE).");
                writeJson(out, 404, "Not Found", notFound, extra, true);
                return true;
        }
    }

    private boolean handleMcp(Request request, OutputStream out, Map<String, String> extra) throws IOException {
        switch (request.method) {
            case "POST":
                return handleMcpPost(request, out, extra);
            case "GET": {
                String accept = request.header("accept");
                if (accept != null && accept.contains("text/event-stream")) {
                    return handleStreamableSse(request, out, extra);
                }
                JsonObject error = new JsonObject();
                error.addProperty("error", "method_not_allowed");
                error.addProperty("message", "GET /mcp requires 'Accept: text/event-stream'.");
                writeJson(out, 405, "Method Not Allowed", error, extra, true);
                return true;
            }
            case "DELETE": {
                Session session = sessionFor(request);
                if (session != null) {
                    session.closeSse();
                    sessions.remove(session.id);
                }
                writeStatusOnly(out, 200, "OK", extra, true);
                return true;
            }
            default: {
                JsonObject error = new JsonObject();
                error.addProperty("error", "method_not_allowed");
                writeJson(out, 405, "Method Not Allowed", error, extra, true);
                return true;
            }
        }
    }

    private boolean handleMcpPost(Request request, OutputStream out, Map<String, String> extra) throws IOException {
        JsonObject message;
        try {
            message = parseObject(request.body);
        } catch (RuntimeException e) {
            writeJson(out, 400, "Bad Request", rpcError(null, -32700, "Parse error: " + e.getMessage()), extra, true);
            return true;
        }

        String method = string(message, "method");
        Session session;
        if ("initialize".equals(method)) {
            session = createSession();
        } else {
            String headerId = request.header(SESSION_HEADER.toLowerCase(Locale.ROOT));
            if (headerId != null && !headerId.isBlank()) {
                session = sessions.get(headerId);
                if (session == null) {
                    // Notifications after a lost session are harmless; requests must be rejected.
                    if (message.has("id")) {
                        writeJson(out, 404, "Not Found", rpcError(message.get("id"), -32001, "Unknown MCP session. Re-run initialize."), extra, true);
                        return true;
                    }
                    session = createSession();
                }
            } else {
                session = createSession();
            }
        }
        session.touch();

        JsonObject response;
        try {
            response = backend.handle(message, session.id, false);
        } catch (Throwable t) {
            LOGGER.warn("MCP backend error for method {}", method, t);
            if (!message.has("id")) {
                writeStatusOnly(out, 202, "Accepted", extra, true);
                return true;
            }
            response = rpcError(message.get("id"), -32603, "Internal error: " + t.getMessage());
        }

        if (response == null) {
            writeStatusOnly(out, 202, "Accepted", extra, true);
            return true;
        }
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put(SESSION_HEADER, session.id);
        writeJson(out, 200, "OK", response, headers, true);
        return true;
    }

    private boolean handleStreamableSse(Request request, OutputStream out, Map<String, String> extra) {
        Session session = sessionFor(request);
        if (session == null) {
            session = createSession();
        }
        SseStream stream = startSse(out, extra);
        if (stream == null) {
            return false;
        }
        session.sse = stream;
        session.touch();
        LOGGER.debug("Streamable SSE opened for session {}", session.id);
        try {
            stream.awaitClose(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.sse = null;
        sessions.remove(session.id);
        return false;
    }

    private boolean handleLegacySse(Request request, OutputStream out, Map<String, String> extra) {
        Session session = createSession();
        SseStream stream = startSse(out, extra);
        if (stream == null) {
            return false;
        }
        session.sse = stream;
        try {
            stream.event("endpoint", "/message?sessionId=" + session.id);
            stream.comment("connected");
            stream.awaitClose(Long.MAX_VALUE);
        } catch (IOException e) {
            LOGGER.debug("Legacy SSE write failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.sse = null;
        sessions.remove(session.id);
        return false;
    }

    private boolean handleLegacyMessage(Request request, OutputStream out, Map<String, String> extra) throws IOException {
        if (!"POST".equals(request.method)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "method_not_allowed");
            writeJson(out, 405, "Method Not Allowed", error, extra, true);
            return true;
        }
        String sessionId = request.query.get("sessionId");
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "unknown_session");
            error.addProperty("message", "Unknown or expired sessionId. Reconnect to /sse.");
            writeJson(out, 404, "Not Found", error, extra, true);
            return true;
        }
        SseStream stream = session.sse;
        if (stream == null || stream.isClosed()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "sse_closed");
            error.addProperty("message", "The SSE stream for this session is closed.");
            writeJson(out, 409, "Conflict", error, extra, true);
            return true;
        }

        JsonObject message;
        try {
            message = parseObject(request.body);
        } catch (RuntimeException e) {
            JsonObject error = rpcError(null, -32700, "Parse error: " + e.getMessage());
            stream.event("message", GSON.toJson(error));
            writeStatusOnly(out, 202, "Accepted", extra, true);
            return true;
        }
        session.touch();

        try {
            JsonObject response = backend.handle(message, session.id, true);
            if (response != null) {
                stream.event("message", GSON.toJson(response));
            }
        } catch (Throwable t) {
            LOGGER.warn("MCP legacy backend error", t);
            if (message.has("id")) {
                stream.event("message", GSON.toJson(rpcError(message.get("id"), -32603, "Internal error: " + t.getMessage())));
            }
        }
        writeStatusOnly(out, 202, "Accepted", extra, true);
        return true;
    }

    private SseStream startSse(OutputStream out, Map<String, String> extra) {
        try {
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 200 OK").append(CRLF);
            header.append("Content-Type: text/event-stream; charset=utf-8").append(CRLF);
            header.append("Cache-Control: no-cache, no-transform").append(CRLF);
            header.append("Connection: keep-alive").append(CRLF);
            header.append("Transfer-Encoding: chunked").append(CRLF);
            header.append("X-Accel-Buffering: no").append(CRLF);
            appendHeaders(header, extra);
            header.append(CRLF);
            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new SseStream(out);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Sessions / heartbeat
    // ------------------------------------------------------------------

    private Session createSession() {
        if (sessions.size() >= MAX_SESSIONS) {
            // Drop the oldest idle session to keep the map bounded.
            sessions.entrySet().stream()
                    .filter(entry -> entry.getValue().sse == null)
                    .min((a, b) -> Long.compare(a.getValue().lastSeen, b.getValue().lastSeen))
                    .ifPresent(entry -> sessions.remove(entry.getKey()));
        }
        Session session = new Session(UUID.randomUUID().toString());
        sessions.put(session.id, session);
        return session;
    }

    private Session sessionFor(Request request) {
        String header = request.header(SESSION_HEADER.toLowerCase(Locale.ROOT));
        if (header != null && sessions.containsKey(header)) {
            return sessions.get(header);
        }
        String query = request.query.get("sessionId");
        if (query != null && sessions.containsKey(query)) {
            return sessions.get(query);
        }
        return null;
    }

    private void heartbeat() {
        for (Session session : new ArrayList<>(sessions.values())) {
            SseStream stream = session.sse;
            if (stream != null) {
                try {
                    stream.comment("ping");
                } catch (IOException e) {
                    stream.close();
                    session.sse = null;
                    sessions.remove(session.id);
                }
            }
        }
    }

    private void cleanupSessions() {
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.sse == null && now - session.lastSeen > TimeUnit.MINUTES.toMillis(30)) {
                sessions.remove(session.id);
            }
        }
    }

    private boolean authorized(Request request) {
        String expected = token;
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        // Legacy SSE clients receive an unguessable session id from /sse and may not repeat
        // the bearer token when posting to /message. The session id itself is the capability.
        if ("/message".equals(request.path)) {
            String sessionId = request.query.get("sessionId");
            if (sessionId != null && sessions.containsKey(sessionId)) {
                return true;
            }
        }
        String actual = null;
        String auth = request.header("authorization");
        if (auth != null) {
            int space = auth.indexOf(' ');
            if (space > 0 && auth.substring(0, space).equalsIgnoreCase("Bearer")) {
                actual = auth.substring(space + 1).trim();
            }
        }
        if (actual == null || actual.isEmpty()) {
            actual = request.query.get("token");
        }
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Request parsing
    // ------------------------------------------------------------------

    private Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) {
            return null;
        }
        while (requestLine.isEmpty()) {
            requestLine = readLine(in);
            if (requestLine == null) {
                return null;
            }
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            throw new IOException("Malformed HTTP request line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("Unexpected end of headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        byte[] body = readBody(in, headers);
        String target = parts[1];
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed request target: " + target);
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        return new Request(parts[0].toUpperCase(Locale.ROOT), path, parseQuery(uri.getRawQuery()), headers, body);
    }

    private byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String lengthHeader = headers.get("content-length");
        if (lengthHeader == null) {
            return new byte[0];
        }
        int length;
        try {
            length = Integer.parseInt(lengthHeader.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length");
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new IOException("Request body too large");
        }
        return readFully(in, length);
    }

    private byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                throw new IOException("Unexpected end of chunked body");
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size: " + hex);
            }
            if (size < 0 || buffer.size() + size > MAX_BODY_BYTES) {
                throw new IOException("Request body too large");
            }
            if (size == 0) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) {
                        break;
                    }
                }
                break;
            }
            buffer.write(readFully(in, size));
            String after = readLine(in);
            if (after == null || !after.isEmpty()) {
                throw new IOException("Malformed chunk terminator");
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("Unexpected end of stream");
            }
            offset += read;
        }
        return data;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        boolean any = false;
        while ((value = in.read()) != -1) {
            any = true;
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new IOException("HTTP header line too long");
            }
        }
        if (!any && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                query.put(decode(pair), "");
            } else {
                query.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            }
        }
        return query;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static JsonObject parseObject(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Empty body");
        }
        var element = JsonParser.parseString(text);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    // ------------------------------------------------------------------
    // Response writing
    // ------------------------------------------------------------------

    private void writeJson(OutputStream out, int status, String reason, JsonObject body,
                           Map<String, String> extra, boolean keepAlive) throws IOException {
        byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF);
        header.append("Content-Type: application/json; charset=utf-8").append(CRLF);
        header.append("Content-Length: ").append(payload.length).append(CRLF);
        header.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append(CRLF);
        appendHeaders(header, extra);
        header.append(CRLF);
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private void writeStatusOnly(OutputStream out, int status, String reason,
                                 Map<String, String> extra, boolean keepAlive) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF);
        header.append("Content-Length: 0").append(CRLF);
        header.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append(CRLF);
        appendHeaders(header, extra);
        header.append(CRLF);
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void appendHeaders(StringBuilder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }
    }

    private static Map<String, String> corsHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, Mcp-Session-Id, MCP-Protocol-Version");
        headers.put("Access-Control-Expose-Headers", SESSION_HEADER);
        return headers;
    }

    private static JsonObject rpcError(com.google.gson.JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id == null) {
            response.add("id", com.google.gson.JsonNull.INSTANCE);
        } else {
            response.add("id", id);
        }
        response.add("error", error);
        return response;
    }

    // ------------------------------------------------------------------
    // Data types
    // ------------------------------------------------------------------

    private record Request(String method, String path, Map<String, String> query,
                           Map<String, String> headers, byte[] body) {
        String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
        }
    }

    private static final class Session {
        final String id;
        volatile boolean initialized;
        volatile SseStream sse;
        volatile long lastSeen = System.currentTimeMillis();

        Session(String id) {
            this.id = id;
        }

        void touch() {
            lastSeen = System.currentTimeMillis();
        }

        void closeSse() {
            SseStream stream = sse;
            if (stream != null) {
                stream.close();
            }
        }
    }

    private static final class SseStream {
        private final OutputStream out;
        private final Object lock = new Object();
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile boolean closed;

        SseStream(OutputStream out) {
            this.out = out;
        }

        void event(String event, String data) throws IOException {
            StringBuilder payload = new StringBuilder();
            payload.append("event: ").append(event).append('\n');
            for (String line : data.split("\n", -1)) {
                payload.append("data: ").append(line).append('\n');
            }
            payload.append('\n');
            send(payload.toString());
        }

        void comment(String comment) throws IOException {
            send(": " + comment + "\n\n");
        }

        private void send(String payload) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("SSE stream closed");
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                out.write(Integer.toHexString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        }

        void close() {
            synchronized (lock) {
                if (!closed) {
                    closed = true;
                    try {
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException ignored) {
                    }
                }
                closedLatch.countDown();
            }
        }

        boolean isClosed() {
            return closed;
        }

        void awaitClose(long millis) throws InterruptedException {
            if (millis == Long.MAX_VALUE) {
                closedLatch.await();
            } else {
                closedLatch.await(millis, TimeUnit.MILLISECONDS);
            }
        }
    }
}
