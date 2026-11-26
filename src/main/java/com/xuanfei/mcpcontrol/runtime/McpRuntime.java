package com.xuanfei.mcpcontrol.runtime;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.client.ClientApi;
import com.xuanfei.mcpcontrol.config.McpConfig;
import com.xuanfei.mcpcontrol.game.PlayerBridge;
import com.xuanfei.mcpcontrol.game.TaskManager;
import com.xuanfei.mcpcontrol.mcp.McpHttpServer;
import com.xuanfei.mcpcontrol.mcp.McpProtocol;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.tools.ActionTools;
import com.xuanfei.mcpcontrol.mcp.tools.CraftTools;
import com.xuanfei.mcpcontrol.mcp.tools.InventoryTools;
import com.xuanfei.mcpcontrol.mcp.tools.ServerTools;
import com.xuanfei.mcpcontrol.mcp.tools.StateTools;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Owns the config, the HTTP endpoint, the tool registry and the tick-driven task managers.
 *
 * <p>On a dedicated server this behaves exactly like before: the endpoint starts with the server and
 * controls a real online player. On a physical client the endpoint starts with the game and stays up
 * across world loads, so the AI can sit at the main menu, create or load a world, drive menus and
 * take screenshots, and only then use the server-side player tools (which talk to the integrated
 * server once a world is running).</p>
 */
public final class McpRuntime {

    public static final String VERSION = "1.1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Runtime");
    private static volatile McpRuntime INSTANCE;

    private volatile MinecraftServer server;
    private final McpConfig config;
    private final PlayerBridge players = new PlayerBridge();
    private final TaskManager tasks = new TaskManager();
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolContext context;

    private volatile ClientApi client;
    private boolean clientToolsRegistered;
    private McpHttpServer http;

    private McpRuntime() {
        this.config = McpConfig.load();
        this.context = new ToolContext(null, null, config, players, tasks);
        StateTools.register(registry);
        ActionTools.register(registry);
        InventoryTools.register(registry);
        CraftTools.register(registry);
        ServerTools.register(registry);
        LOGGER.info("Loaded {} server-side MCP tools", registry.size());
    }

    public static synchronized McpRuntime ensure() {
        if (INSTANCE == null) {
            INSTANCE = new McpRuntime();
        }
        return INSTANCE;
    }

    /**
     * Called when a Minecraft server starts. A dedicated server also opens the HTTP endpoint; an
     * integrated (single-player) server only attaches itself, because the client owns the endpoint.
     */
    public static synchronized void startServer(MinecraftServer server) {
        McpRuntime runtime = ensure();
        runtime.attachServer(server);
        if (server.isDedicatedServer()) {
            runtime.ensureStarted();
        } else {
            LOGGER.debug("Integrated server attached to the client MCP endpoint");
        }
    }

    /** Safety net for integrated servers: attach the client's own server as soon as a world is loaded. */
    public static synchronized void attachIntegratedServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        McpRuntime runtime = ensure();
        if (runtime.server != server) {
            runtime.attachServer(server);
        }
    }

    /** Called once on a physical client, at the first client tick. */    public static synchronized void startClient(ClientApi api) {
        McpRuntime runtime = ensure();
        runtime.client = api;
        runtime.context.client = api;
        if (!runtime.clientToolsRegistered && runtime.config.clientControl) {
            runtime.clientToolsRegistered = true;
            api.registerTools(runtime.registry);
            LOGGER.info("Client tools registered; {} tools total", runtime.registry.size());
        }
        runtime.ensureStarted();
    }

    /** Called when the server stops: a dedicated server shuts everything down, an integrated one just detaches. */
    public static synchronized void serverStopping(MinecraftServer server) {
        McpRuntime runtime = INSTANCE;
        if (runtime == null || runtime.server != server) {
            return;
        }
        runtime.detachServer();
        if (server.isDedicatedServer()) {
            stop();
        }
    }

    public static synchronized void stop() {
        if (INSTANCE != null) {
            INSTANCE.stopServices();
            INSTANCE = null;
        }
    }

    public static McpRuntime get() {
        return INSTANCE;
    }

    public static void tick(MinecraftServer server) {
        McpRuntime runtime = INSTANCE;
        if (runtime != null && runtime.server == server) {
            runtime.tasks.tick(server);
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        McpRuntime runtime = INSTANCE;
        if (runtime != null) {
            runtime.players.onLogout(player);
        }
    }

    public static void bind(ServerPlayer player) {
        McpRuntime runtime = INSTANCE;
        if (runtime != null) {
            runtime.players.bind(player);
        }
    }

    private void attachServer(MinecraftServer server) {
        this.server = server;
        this.context.server = server;
    }

    private void detachServer() {
        tasks.reset();
        players.unbind();
        this.server = null;
        this.context.server = null;
    }

    private void ensureStarted() {
        if (config.autoStart) {
            try {
                startHttp();
            } catch (IOException error) {
                LOGGER.error("Could not start MCP HTTP server on {}:{} - {}", config.bindAddress, config.port, error.getMessage());
            }
        }
    }

    public synchronized void startHttp() throws IOException {
        if (http != null && http.isRunning()) {
            return;
        }
        if (http != null) {
            http.close();
        }
        McpProtocol protocol = new McpProtocol(registry, () -> context, "mcp-control", VERSION);
        McpHttpServer server = new McpHttpServer(config.bindAddress, config.port, config.token,
                "mcp-control", VERSION, protocol);
        server.start();
        this.http = server;
        LOGGER.info("MCP endpoint ready at {}/mcp (token {})", config.url(), config.maskedToken());
    }

    public synchronized void stopServices() {
        if (http != null) {
            http.close();
            http = null;
        }
        tasks.reset();
        players.unbind();
    }

    public synchronized void restartHttp() throws IOException {
        stopServices();
        startHttp();
    }

    public McpConfig config() {
        return config;
    }

    public PlayerBridge players() {
        return players;
    }

    public TaskManager tasks() {
        return tasks;
    }

    public ToolRegistry registry() {
        return registry;
    }

    public ClientApi client() {
        return client;
    }

    public boolean hasServer() {
        return server != null;
    }

    public McpHttpServer http() {
        return http;
    }

    public JsonObject status() {
        JsonObject object = new JsonObject();
        object.addProperty("version", VERSION);
        object.addProperty("http_running", http != null && http.isRunning());
        object.addProperty("url", config.url() + "/mcp");
        object.addProperty("sse_url", config.url() + "/sse");
        object.addProperty("bind_address", config.bindAddress);
        object.addProperty("port", http == null ? config.port : http.getPort());
        object.addProperty("token_masked", config.maskedToken());
        object.addProperty("tools", registry.size());
        object.addProperty("sessions", http == null ? 0 : http.getSessionCount());
        object.addProperty("allow_commands", config.allowCommands);
        object.addProperty("auto_start", config.autoStart);
        object.addProperty("config_file", McpConfig.path().toString());
        object.addProperty("client_available", client != null);
        object.addProperty("server_attached", server != null);
        if (client != null) {
            object.add("client", client.status());
        }
        object.add("players", players.status(server, config));
        object.add("task", tasks.status());
        return object;
    }
}
