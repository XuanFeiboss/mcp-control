package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Owns every client-side subsystem: virtual input, screen navigation, world lifecycle, container
 * access and screenshots. Tools never touch Minecraft directly; they talk to this bridge, which
 * marshals all work onto the client thread.
 */
public final class ClientBridge implements ClientApi {

    private final Minecraft minecraft;
    private final ClientTaskManager tasks = new ClientTaskManager();
    private final InputDriver input = new InputDriver();
    private final ScreenDriver screens;
    private final PlayerDriver players;
    private final ContainerDriver containers;
    private final WorldDriver worlds;
    private final ShotService shots;

    public ClientBridge(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.screens = new ScreenDriver(minecraft);
        this.players = new PlayerDriver(minecraft, input);
        this.containers = new ContainerDriver(minecraft, screens);
        this.worlds = new WorldDriver(minecraft, screens);
        this.shots = new ShotService(minecraft);
    }

    /** Called once per client tick. */
    public void tick() {
        if (minecraft.player == null) {
            input.release();
        }
        input.apply(minecraft);
        tasks.step(minecraft);
    }

    @Override
    public void registerTools(ToolRegistry registry) {
        ClientTools.register(registry, this);
    }

    @Override
    public String describeSide() {
        return minecraft.getSingleplayerServer() != null ? "client (single-player)" : "client";
    }

    @Override
    public JsonObject status() {
        JsonObject object = new JsonObject();
        object.addProperty("side", describeSide());
        object.addProperty("in_world", minecraft.level != null);
        object.addProperty("screen", screens.screenClass());
        object.addProperty("has_player", minecraft.player != null);
        object.addProperty("virtual_input", input.any());
        object.add("task", tasks.status());
        object.add("screenshot", shots.status());
        if (minecraft.player != null) {
            object.addProperty("dimension", minecraft.level == null
                    ? "" : minecraft.level.dimension().location().toString());
            object.addProperty("position", String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f",
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()));
        }
        return object;
    }

    // ---------------------------------------------------------------- accessors

    public Minecraft minecraft() {
        return minecraft;
    }

    public ClientTaskManager tasks() {
        return tasks;
    }

    public InputDriver input() {
        return input;
    }

    public ScreenDriver screens() {
        return screens;
    }

    public PlayerDriver players() {
        return players;
    }

    public ContainerDriver containers() {
        return containers;
    }

    public WorldDriver worlds() {
        return worlds;
    }

    public ShotService shots() {
        return shots;
    }

    // ---------------------------------------------------------------- thread marshalling

    /** Runs a supplier on the client thread. */
    public <T> CompletableFuture<T> call(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /** Blocking read from the client thread; used by the JSON-only tools. */
    public <T> T read(Supplier<T> supplier, String what) {
        try {
            return call(supplier).get(6, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new ToolError("The client did not answer " + what + " within 6s (is the game frozen?).");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof ToolError toolError) {
                throw toolError;
            }
            throw new ToolError(what + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ToolError("Interrupted while reading " + what + ".");
        }
    }

    /** Starts a tick-driven action and waits for it; safe to call from an HTTP worker thread. */
    public ToolResult run(ClientTask task, int timeoutSeconds) {
        CompletableFuture<ClientTask.ClientOutcome> future = submit(task);
        try {
            ClientTask.ClientOutcome outcome = future.get(Math.max(1, timeoutSeconds) + 5L, TimeUnit.SECONDS);
            if (outcome.success()) {
                return ToolResult.ok(outcome.message(), outcome.data());
            }
            return ToolResult.error(outcome.message(), outcome.data());
        } catch (TimeoutException timeout) {
            minecraft.execute(() -> tasks.cancel("MCP client timed out waiting for the action"));
            return ToolResult.error("Timed out after " + timeoutSeconds + "s; the client action was cancelled.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            minecraft.execute(() -> tasks.cancel("MCP request interrupted"));
            return ToolResult.error("Interrupted while waiting for the client action.");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof ToolError toolError) {
                return ToolResult.error(toolError.getMessage());
            }
            return ToolResult.error("Client action failed: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
        }
    }

    public CompletableFuture<ToolResult> runAsync(ClientTask task, int timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> run(task, timeoutSeconds));
    }

    public CompletableFuture<ClientTask.ClientOutcome> submit(ClientTask task) {
        CompletableFuture<ClientTask.ClientOutcome> result = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                tasks.start(task, minecraft).whenComplete((outcome, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(outcome);
                    }
                });
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }
}
