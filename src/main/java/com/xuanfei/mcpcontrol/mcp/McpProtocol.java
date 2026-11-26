package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** JSON-RPC 2.0 / MCP protocol implementation on top of {@link McpHttpServer}. */
public final class McpProtocol implements McpHttpServer.Backend {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Protocol");
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final ToolRegistry registry;
    private final Supplier<ToolContext> contexts;
    private final String serverName;
    private final String serverVersion;

    public McpProtocol(ToolRegistry registry, Supplier<ToolContext> contexts, String serverName, String serverVersion) {
        this.registry = registry;
        this.contexts = contexts;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    @Override
    public JsonObject handle(JsonObject message, String sessionId, boolean legacySse) {
        JsonElement idElement = message.get("id");
        boolean notification = idElement == null || idElement.isJsonNull();
        String method = message.has("method") && message.get("method").isJsonPrimitive()
                ? message.get("method").getAsString()
                : "";

        if (method.isEmpty()) {
            return notification ? null : error(idElement, -32600, "Invalid Request: missing method");
        }

        if (notification) {
            if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) {
                return null;
            }
            LOGGER.debug("Ignoring MCP notification {}", method);
            return null;
        }

        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params")
                : new JsonObject();

        try {
            return switch (method) {
                case "initialize" -> success(idElement, initialize(params));
                case "ping" -> success(idElement, new JsonObject());
                case "tools/list" -> success(idElement, listTools());
                case "tools/call" -> success(idElement, callTool(params));
                case "resources/list" -> success(idElement, emptyList("resources"));
                case "prompts/list" -> success(idElement, emptyList("prompts"));
                case "logging/setLevel" -> success(idElement, new JsonObject());
                case "resources/templates/list" -> success(idElement, emptyList("resourceTemplates"));
                default -> error(idElement, -32601, "Method not found: " + method);
            };
        } catch (ToolError toolError) {
            return error(idElement, -32602, toolError.getMessage());
        } catch (Throwable throwable) {
            LOGGER.warn("MCP method {} failed", method, throwable);
            return error(idElement, -32603, "Internal error: " + messageOf(throwable));
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
                ? params.get("protocolVersion").getAsString()
                : DEFAULT_PROTOCOL;
        if (requested.isBlank()) {
            requested = DEFAULT_PROTOCOL;
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested);
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", serverName);
        info.addProperty("version", serverVersion);
        result.add("serverInfo", info);
        result.addProperty("instructions",
                "This server controls a real Minecraft player through the game client/server. "
                        + "Start with get_player_state and get_nearby_entities to observe the world, then use move_to, "
                        + "mine_block, place_block, attack_entity, use_item, craft_item and container tools to act. "
                        + "Actions that take time block until they finish or time out. Call stop_task to interrupt. "
                        + "All coordinates are world block coordinates; the player is whoever is bound in-game.");
        return result;
    }

    private JsonObject listTools() {
        JsonObject result = new JsonObject();
        result.add("tools", registry.listJson());
        return result;
    }

    private JsonObject callTool(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        McpTool tool = registry.get(name);
        if (tool == null) {
            JsonObject result = ToolResult.error("Unknown tool '" + name + "'. Call tools/list.").toJson();
            return result;
        }
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        ToolContext context;
        try {
            context = contexts.get();
        } catch (Throwable throwable) {
            return ToolResult.error(messageOf(throwable)).toJson();
        }

        CompletableFuture<ToolResult> future;
        try {
            future = tool.call(arguments, context);
        } catch (Throwable throwable) {
            return convert(throwable, context);
        }
        if (future == null) {
            return ToolResult.error("Tool '" + name + "' returned no result.").toJson();
        }

        try {
            ToolResult result = future.get(5, TimeUnit.MINUTES);
            return result.toJson();
        } catch (TimeoutException timeout) {
            context.server.execute(() -> context.tasks.stop("MCP protocol timeout"));
            return ToolResult.error("Tool timed out after 5 minutes and the action was stopped.").toJson();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Tool call was interrupted.").toJson();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return convert(cause, context);
        } catch (Throwable throwable) {
            return convert(throwable, context);
        }
    }

    private JsonObject convert(Throwable throwable, ToolContext context) {
        if (throwable instanceof ToolError toolError) {
            return ToolResult.error(toolError.getMessage()).toJson();
        }
        LOGGER.warn("Tool execution failed", throwable);
        return ToolResult.error(context.errorFrom(throwable).text()).toJson();
    }

    private static JsonObject emptyList(String key) {
        JsonObject result = new JsonObject();
        result.add(key, new com.google.gson.JsonArray());
        return result;
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        } else {
            response.add("id", com.google.gson.JsonNull.INSTANCE);
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
