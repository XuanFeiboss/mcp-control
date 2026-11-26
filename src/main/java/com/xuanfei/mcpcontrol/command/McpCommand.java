package com.xuanfei.mcpcontrol.command;

import com.mojang.brigadier.CommandDispatcher;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.runtime.McpRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/** In-game operator commands for starting, stopping and inspecting the MCP endpoint. */
public final class McpCommand {

    private McpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcp")
                .requires(source -> source.hasPermission(2))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("start").executes(context -> start(context.getSource())))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                .then(Commands.literal("restart").executes(context -> restart(context.getSource())))
                .then(Commands.literal("url").executes(context -> url(context.getSource())))
                .then(Commands.literal("token")
                        .executes(context -> token(context.getSource()))
                        .then(Commands.literal("reset").executes(context -> resetToken(context.getSource()))))
                .then(Commands.literal("bind")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> bind(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("unbind").executes(context -> unbind(context.getSource())))
                .then(Commands.literal("tasks").executes(context -> tasks(context.getSource())))
                .then(Commands.literal("help").executes(context -> help(context.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(JsonUtil.PRETTY.toJson(runtime.status())), false);
        return 1;
    }

    private static int start(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started. Start a world first."));
            return 0;
        }
        try {
            runtime.startHttp();
            source.sendSuccess(() -> Component.literal("MCP HTTP server started at " + runtime.config().url() + "/mcp"), false);
            return 1;
        } catch (IOException error) {
            source.sendFailure(Component.literal("Could not start MCP HTTP server: " + error.getMessage()));
            return 0;
        }
    }

    private static int stop(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        runtime.stopServices();
        source.sendSuccess(() -> Component.literal("MCP HTTP server stopped; tasks cancelled."), false);
        return 1;
    }

    private static int restart(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        try {
            runtime.restartHttp();
            source.sendSuccess(() -> Component.literal("MCP HTTP server restarted at " + runtime.config().url() + "/mcp"), false);
            return 1;
        } catch (IOException error) {
            source.sendFailure(Component.literal("Could not restart MCP HTTP server: " + error.getMessage()));
            return 0;
        }
    }

    private static int url(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Streamable HTTP: " + runtime.config().url() + "/mcp\n"
                + "Legacy SSE: " + runtime.config().url() + "/sse\n"
                + "Token: " + runtime.config().maskedToken() + " (use /mcp token for the full value)"), false);
        return 1;
    }

    private static int token(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("MCP bearer token: " + runtime.config().token), false);
        return 1;
    }

    private static int resetToken(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        runtime.config().resetToken();
        if (runtime.http() != null) {
            runtime.http().setToken(runtime.config().token);
        }
        source.sendSuccess(() -> Component.literal("MCP token reset. New token: " + runtime.config().token), false);
        return 1;
    }

    private static int bind(CommandSourceStack source, ServerPlayer player) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        runtime.players().bind(player);
        source.sendSuccess(() -> Component.literal("AI now controls " + player.getScoreboardName()), false);
        return 1;
    }

    private static int unbind(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        runtime.players().unbind();
        source.sendSuccess(() -> Component.literal("AI player binding cleared."), false);
        return 1;
    }

    private static int tasks(CommandSourceStack source) {
        McpRuntime runtime = McpRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("MCP runtime is not started."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(JsonUtil.PRETTY.toJson(runtime.tasks().status())), false);
        return 1;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("""
                /mcp status        Show endpoint, token mask, players and task
                /mcp start|stop|restart
                /mcp url           Show MCP URLs
                /mcp token         Show the bearer token
                /mcp token reset   Generate a new token
                /mcp bind <player> Choose the controlled player
                /mcp unbind        Clear the binding
                /mcp tasks         Show the current action
                """), false);
        return 1;
    }
}
