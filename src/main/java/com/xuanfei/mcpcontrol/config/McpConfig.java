package com.xuanfei.mcpcontrol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

/** JSON configuration stored in {@code config/mcpcontrol.json}. */
public final class McpConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "mcpcontrol.json";
    private static final SecureRandom RANDOM = new SecureRandom();

    public int port = 25566;
    public String bindAddress = "127.0.0.1";
    public boolean autoStart = true;
    public String token = "";
    public String defaultPlayer = "";
    public boolean allowCommands = true;
    public boolean announceActions = true;
    public double walkSpeed = 0.22;
    public double sprintSpeed = 0.30;
    public int maxPathNodes = 12000;

    /**
     * Full URL advertised to the AI client, e.g. {@code http://127.0.0.1:25566/mcp}.
     * Leave empty to derive it from {@code bindAddress} + {@code port} + {@code /mcp}.
     */
    public String publicUrl = "";
    /** Write {@code config/mcpcontrol-client.json} (a ready-to-paste mcp-remote snippet) on every start. */
    public boolean writeClientConfig = true;

    /** Client-side (physical client only): register screen/world/screenshot tools. */
    public boolean clientControl = true;
    /** Client-side: allow world_create / world_join / world_leave to drive the menus. */
    public boolean allowWorldManagement = true;
    /** Client-side: maximum screenshot width in pixels (larger frames are downscaled). */
    public int screenshotMaxWidth = 1280;

    public static McpConfig load() {
        Path path = path();
        McpConfig config = new McpConfig();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                McpConfig loaded = GSON.fromJson(reader, McpConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("Could not read {}; using defaults: {}", path, e.getMessage());
            }
        }
        config.normalize();
        config.save();
        return config;
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public void normalize() {
        this.port = Math.max(1, Math.min(65535, this.port));
        if (this.bindAddress == null || this.bindAddress.isBlank()) {
            this.bindAddress = "127.0.0.1";
        }
        if (this.token == null || this.token.isBlank() || this.token.length() < 16) {
            this.token = generateToken();
        }
        if (this.defaultPlayer == null) {
            this.defaultPlayer = "";
        }
        this.walkSpeed = clamp(this.walkSpeed, 0.05, 0.45);
        this.sprintSpeed = clamp(this.sprintSpeed, 0.05, 0.55);
        this.maxPathNodes = Math.max(1000, Math.min(40000, this.maxPathNodes));
        if (this.publicUrl == null) {
            this.publicUrl = "";
        }
        this.publicUrl = this.publicUrl.trim();
        if (!this.publicUrl.isEmpty() && !this.publicUrl.startsWith("http://") && !this.publicUrl.startsWith("https://")) {
            this.publicUrl = "http://" + this.publicUrl;
        }
        while (this.publicUrl.endsWith("/")) {
            this.publicUrl = this.publicUrl.substring(0, this.publicUrl.length() - 1);
        }
        this.screenshotMaxWidth = Math.max(160, Math.min(3840, this.screenshotMaxWidth));
    }

    public void resetToken() {
        this.token = generateToken();
        save();
    }

    public void save() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save MCP config: {}", e.getMessage());
        }
    }

    public String url() {
        String host = bindAddress;
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + port;
    }

    /** The Streamable HTTP endpoint the AI should connect to (always ends with {@code /mcp}). */
    public String endpointUrl() {
        String advertised = this.publicUrl;
        if (advertised == null || advertised.isBlank()) {
            return url() + "/mcp";
        }
        if (advertised.endsWith("/mcp")) {
            return advertised;
        }
        return advertised + "/mcp";
    }

    /** The legacy SSE endpoint (base URL + {@code /sse}). */
    public String sseUrl() {
        String endpoint = endpointUrl();
        return endpoint.substring(0, endpoint.length() - "/mcp".length()) + "/sse";
    }

    public String maskedToken() {
        String value = token;
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(48);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
