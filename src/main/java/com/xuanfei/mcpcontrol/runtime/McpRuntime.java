package com.xuanfei.mcpcontrol.runtime;

import com.google.gson.JsonObject;
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

/** Owns the config, HTTP endpoint, tool registry and tick-driven task manager. */
public final class McpRuntime {

    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Runtime");
    private static volatile McpRuntime INSTANCE;

    private final MinecraftServer server;
    private final McpConfig config;
    private final PlayerBridge players = new PlayerBridge();
    private final TaskManager tasks = new TaskManager();
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolContext context;

    private McpHttpServer http;

    private McpRuntime(MinecraftServer server) {
        this.server = server;
        this.config = McpConfig.load();
        this.context = new ToolContext(server, config, players, tasks);
        StateTools.register(registry);
        ActionTools.register(registry);
        InventoryTools.register(registry);
        CraftTools.register(registry);
        ServerTools.register(registry);
        LOGGER.info("Loaded {} MCP tools", registry.size());
    }

    public static synchronized void startServer(MinecraftServer server) {
        if (INSTANCE != null && INSTANCE.server == server) {
            INSTANCE.ensureStarted();
            return;
        }
        stop();
        INSTANCE = new McpRuntime(server);
        INSTANCE.ensureStarted();
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
        object.add("players", players.status(server, config));
        object.add("task", tasks.status());
        return object;
    }
}
