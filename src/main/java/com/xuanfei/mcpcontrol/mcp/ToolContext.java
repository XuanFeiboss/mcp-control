package com.xuanfei.mcpcontrol.mcp;

import com.xuanfei.mcpcontrol.config.McpConfig;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.game.PlayerBridge;
import com.xuanfei.mcpcontrol.game.TaskManager;
import com.xuanfei.mcpcontrol.game.TaskOutcome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Per-call context handed to tools: server, config, player binding and task scheduler. */
public final class ToolContext {

    public final MinecraftServer server;
    public final McpConfig config;
    public final PlayerBridge players;
    public final TaskManager tasks;

    public ToolContext(MinecraftServer server, McpConfig config, PlayerBridge players, TaskManager tasks) {
        this.server = server;
        this.config = config;
        this.players = players;
        this.tasks = tasks;
    }

    /** Resolves the player currently under AI control. */
    public ServerPlayer player() {
        return players.resolve(server, config);
    }

    /** Runs a supplier on the server thread. */
    public <T> CompletableFuture<T> onServer(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /** Runs a supplier on the server thread and converts it into a tool result. */
    public CompletableFuture<ToolResult> sync(Supplier<ToolResult> supplier) {
        return onServer(supplier);
    }

    /** Starts a tick-driven action on the server thread and returns its completion future. */
    public CompletableFuture<TaskOutcome> startTask(ActionTask task) {
        return onServer(() -> tasks.start(task, player())).thenCompose(future -> future);
    }

    /**
     * Starts an action and blocks the calling (HTTP worker) thread until it finishes or times out.
     * Never call this from the server thread.
     */
    public CompletableFuture<ToolResult> runTask(ActionTask task, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> awaitTask(startTask(task), timeoutSeconds));
    }

    /** Waits for a tick-driven task to finish and converts the outcome to a tool result. */
    public ToolResult awaitTask(CompletableFuture<TaskOutcome> future, int timeoutSeconds) {
        try {
            TaskOutcome outcome = future.get(Math.max(1, timeoutSeconds) + 3L, TimeUnit.SECONDS);
            ToolResult result = outcome.success()
                    ? ToolResult.ok(outcome.message(), outcome.data())
                    : ToolResult.error(outcome.message());
            return result;
        } catch (TimeoutException timeout) {
            stopOnServer("MCP client timed out waiting for the action");
            return ToolResult.error("Timed out after " + timeoutSeconds + "s. The action was stopped; call get_task/status for details.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopOnServer("MCP request interrupted");
            return ToolResult.error("Interrupted while waiting for the action.");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof ToolError toolError) {
                return ToolResult.error(toolError.getMessage());
            }
            return ToolResult.error("Action failed: " + cause.getMessage());
        }
    }

    public ToolResult errorFrom(Throwable throwable) {
        if (throwable instanceof ToolError toolError) {
            return ToolResult.error(toolError.getMessage());
        }
        String message = throwable.getMessage();
        return ToolResult.error("Internal error: " + (message == null ? throwable.getClass().getSimpleName() : message));
    }

    private void stopOnServer(String reason) {
        server.execute(() -> tasks.stop(reason));
    }
}
