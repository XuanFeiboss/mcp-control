package com.xuanfei.mcpcontrol.game;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/** Owns the single active action for the controlled player and ticks it on the server thread. */
public final class TaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/Tasks");

    private ActionTask current;
    private CompletableFuture<TaskOutcome> future;
    private ServerPlayer taskPlayer;
    private ServerLevel taskLevel;
    private int elapsed;
    private String lastResult = "idle";

    public boolean isBusy() {
        return current != null;
    }

    public CompletableFuture<TaskOutcome> start(ActionTask task, ServerPlayer player) {
        if (current != null) {
            throw new ToolError("Another action is already running (" + current.describe() + "). Call stop first.");
        }
        this.current = task;
        this.taskPlayer = player;
        this.taskLevel = player.serverLevel();
        this.elapsed = 0;
        this.future = new CompletableFuture<>();
        try {
            task.begin(taskLevel, player);
        } catch (Throwable throwable) {
            this.current = null;
            this.taskPlayer = null;
            this.taskLevel = null;
            this.future = null;
            if (throwable instanceof ToolError toolError) {
                throw toolError;
            }
            throw new ToolError("Could not start " + task.name() + ": " + throwable.getMessage(), throwable);
        }
        LOGGER.debug("Started task {} for {}", task.name(), player.getScoreboardName());
        return future;
    }

    public void tick(MinecraftServer server) {
        if (current == null) {
            return;
        }
        if (taskPlayer.hasDisconnected() || !taskPlayer.isAlive()) {
            finish(false, "The controlled player disconnected or died; action cancelled.", null);
            return;
        }
        if (taskPlayer.serverLevel() != taskLevel) {
            finish(false, "The player changed dimension; action cancelled.", null);
            return;
        }
        try {
            ActionTask.Step step = current.tick(taskLevel, taskPlayer);
            elapsed++;
            if (step.done()) {
                finish(step.success(), step.message(), step.data());
            } else if (elapsed > current.timeoutTicks()) {
                finish(false, "Action timed out after " + (elapsed / 20) + "s.", null);
            }
        } catch (Throwable throwable) {
            LOGGER.warn("Task {} failed", current.name(), throwable);
            String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            finish(false, "Action failed: " + message, null);
        }
    }

    private void finish(boolean success, String message, JsonObject data) {
        ActionTask task = current;
        ServerPlayer player = taskPlayer;
        ServerLevel level = taskLevel;
        CompletableFuture<TaskOutcome> completed = future;
        int ticks = elapsed;

        current = null;
        future = null;
        taskPlayer = null;
        taskLevel = null;
        elapsed = 0;
        lastResult = (success ? "finished: " : "failed: ") + message;

        if (task != null && player != null && level != null) {
            try {
                task.cancel(level, player);
            } catch (Throwable throwable) {
                LOGGER.debug("Task cleanup failed", throwable);
            }
            try {
                player.setSprinting(false);
                player.setShiftKeyDown(false);
            } catch (Throwable ignored) {
            }
        }
        if (completed != null) {
            completed.complete(new TaskOutcome(success, message, data, ticks));
        }
    }

    public void stop(String reason) {
        if (current != null) {
            finish(false, reason, null);
        }
    }

    public void reset() {
        stop("Server is stopping");
        lastResult = "idle";
    }

    public JsonObject status() {
        JsonObject object = new JsonObject();
        object.addProperty("busy", current != null);
        object.addProperty("last_result", lastResult);
        if (current != null) {
            object.addProperty("task", current.name());
            object.addProperty("description", current.describe());
            object.addProperty("elapsed_seconds", JsonUtilSeconds(elapsed));
            object.addProperty("player", taskPlayer == null ? "" : taskPlayer.getScoreboardName());
        }
        return object;
    }

    private static double JsonUtilSeconds(int ticks) {
        return Math.round((ticks / 20.0) * 10.0) / 10.0;
    }
}
