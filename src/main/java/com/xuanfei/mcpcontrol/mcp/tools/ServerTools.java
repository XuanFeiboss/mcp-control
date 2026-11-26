package com.xuanfei.mcpcontrol.mcp.tools;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import com.xuanfei.mcpcontrol.runtime.McpRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/** Chat, commands, binding and runtime diagnostics. */
public final class ServerTools {

    private ServerTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(new BaseTool("chat", "Send chat",
                "Sends a chat message to the server as the controlled player, visible to everyone online.",
                ToolSchemas.schema(singleString("message", "Message to send"), "message"), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    String message = JsonUtil.requiredString(copy, "message");
                    if (message.length() > 256) {
                        throw new ToolError("Chat message is longer than 256 characters");
                    }
                    context.server.getPlayerList().broadcastSystemMessage(
                            Component.literal("<" + player.getScoreboardName() + "> " + message), false);
                    JsonObject data = new JsonObject();
                    data.addProperty("sender", player.getScoreboardName());
                    data.addProperty("message", message);
                    return ToolResult.ok("Chat sent", data);
                });
            }
        });

        registry.register(new BaseTool("run_command", "Run command",
                "Runs a Minecraft command as the controlled player with permission level 2 (requires allow_commands=true). "
                        + "Do not include the leading slash. Examples: 'time set day', 'weather clear', 'give @s apple 4'.",
                commandSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    if (!context.config.allowCommands) {
                        return ToolResult.error("Command execution is disabled in config/mcpcontrol.json (allow_commands=false).");
                    }
                    ServerPlayer player = context.player();
                    String command = JsonUtil.requiredString(copy, "command").trim();
                    if (command.startsWith("/")) {
                        command = command.substring(1);
                    }
                    CommandSourceStack source = context.server.createCommandSourceStack()
                            .withEntity(player)
                            .withPosition(player.position())
                            .withLevel(player.serverLevel())
                            .withPermission(2)
                            .withSuppressedOutput();
                    context.server.getCommands().performPrefixedCommand(source, command);
                    JsonObject data = new JsonObject();
                    data.addProperty("command", command);
                    data.add("position", JsonUtil.vec(player.position()));
                    return ToolResult.ok("Command dispatched: " + command, data);
                });
            }
        });

        registry.register(new BaseTool("bind_player", "Bind player",
                "Chooses which online player the AI controls. Use list_players to see candidates.",
                ToolSchemas.schema(singleString("player", "Exact player name"), "player"), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.onServer(() -> {
                    String name = JsonUtil.requiredString(copy, "player");
                    ServerPlayer found = null;
                    for (ServerPlayer candidate : context.server.getPlayerList().getPlayers()) {
                        if (candidate.getScoreboardName().equalsIgnoreCase(name)) {
                            found = candidate;
                            break;
                        }
                    }
                    if (found == null) {
                        throw new ToolError("Player '" + name + "' is not online");
                    }
                    context.players.bind(found);
                    JsonObject data = context.players.status(context.server, context.config);
                    return ToolResult.ok("Bound to " + found.getScoreboardName(), data);
                });
            }
        });

        registry.register(new BaseTool("unbind_player", "Unbind player",
                "Clears the explicit player binding; the bridge then falls back to default_player or the only online player.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.onServer(() -> {
                    context.players.unbind();
                    return ToolResult.ok("Player binding cleared", context.players.status(context.server, context.config));
                });
            }
        });

        registry.register(new BaseTool("get_status", "Get MCP status",
                "Returns the HTTP endpoint state, masked token, bound player, current task and tool count. "
                        + "Use this to diagnose connectivity.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.onServer(() -> {
                    McpRuntime runtime = McpRuntime.get();
                    if (runtime == null) {
                        return ToolResult.error("The MCP runtime is not started.");
                    }
                    return ToolResult.ok("MCP status", runtime.status());
                });
            }
        });
    }

    private static JsonObject commandSchema() {
        JsonObject properties = new JsonObject();
        properties.add("command", ToolSchemas.string("Command without the leading slash"));
        return ToolSchemas.schema(properties, "command");
    }

    private static JsonObject singleString(String name, String description) {
        JsonObject properties = new JsonObject();
        properties.add(name, ToolSchemas.string(description));
        return properties;
    }
}
