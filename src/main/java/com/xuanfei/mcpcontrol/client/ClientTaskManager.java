package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/** Runs at most one {@link ClientTask} at a time on the client thread. */
public final class ClientTaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCP-Control/ClientTasks");

    private ClientTask current;
    private CompletableFuture<ClientTask.ClientOutcome> future;
    private int elapsed;
    private String lastResult = "idle";

    public boolean isBusy() {
        return current != null;
    }

    public String currentName() {
        return current == null ? "" : current.name();
    }

    /** Must be called on the client thread. */
    public CompletableFuture<ClientTask.ClientOutcome> start(ClientTask task, Minecraft minecraft) {
        if (current != null) {
            throw new ToolError("Another client action is already running (" + current.name() + "). Stop it first.");
        }
        this.current = task;
        this.elapsed = 0;
        this.future = new CompletableFuture<>();
        try {
            task.begin(minecraft);
        } catch (Throwable throwable) {
            this.current = null;
            this.future = null;
            if (throwable instanceof ToolError toolError) {
                throw toolError;
            }
            throw new ToolError("Could not start " + task.name() + ": " + throwable.getMessage(), throwable);
        }
        LOGGER.debug("Started client task {}", task.name());
        return future;
    }

    /** Must be called on the client thread, once per client tick. */
    public void step(Minecraft minecraft) {
        if (current == null) {
            return;
        }
        try {
            ClientTask.ClientOutcome outcome = current.tick(minecraft);
            elapsed++;
            if (outcome != null) {
                finish(outcome);
            } else if (elapsed > current.timeoutTicks()) {
                finish(ClientTask.ClientOutcome.failure("The client action timed out after "
                        + (elapsed / 20) + "s."));
            }
        } catch (Throwable throwable) {
            LOGGER.warn("Client task {} failed", current.name(), throwable);
            String message = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            finish(ClientTask.ClientOutcome.failure("Client action failed: " + message));
        }
    }

    private void finish(ClientTask.ClientOutcome outcome) {
        ClientTask task = current;
        CompletableFuture<ClientTask.ClientOutcome> completed = future;
        this.current = null;
        this.future = null;
        int ticks = elapsed;
        this.elapsed = 0;
        this.lastResult = (outcome.success() ? "finished: " : "failed: ") + outcome.message();
        JsonObject data = outcome.data() == null ? new JsonObject() : outcome.data().deepCopy();
        data.addProperty("ticks", ticks);
        ClientTask.ClientOutcome enriched = new ClientTask.ClientOutcome(
                outcome.success(), outcome.message(), data);
        if (task != null) {
            try {
                task.cancel(Minecraft.getInstance());
            } catch (Throwable throwable) {
                LOGGER.debug("Client task cleanup failed", throwable);
            }
        }
        if (completed != null) {
            completed.complete(enriched);
        }
    }

    /** Must be called on the client thread. */
    public void cancel(String reason) {
        if (current != null) {
            finish(ClientTask.ClientOutcome.failure(reason));
        }
    }

    public void reset() {
        cancel("Client action cancelled");
        lastResult = "idle";
    }

    public JsonObject status() {
        JsonObject object = new JsonObject();
        object.addProperty("busy", current != null);
        object.addProperty("task", current == null ? "" : current.name());
        object.addProperty("elapsed_seconds", Math.round((elapsed / 20.0) * 10.0) / 10.0);
        object.addProperty("last_result", lastResult);
        return object;
    }
}
