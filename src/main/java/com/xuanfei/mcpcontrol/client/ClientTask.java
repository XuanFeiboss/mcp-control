package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * A client-side action driven one client tick at a time: menu navigation, world loading, mining,
 * walking with virtual key presses, container clicks, and so on.
 */
public interface ClientTask {

    String name();

    default void begin(Minecraft minecraft) throws Exception {
    }

    /** @return {@code null} while the task should keep running, or the final outcome. */
    ClientOutcome tick(Minecraft minecraft) throws Exception;

    /** Called on completion, failure and cancellation. Must be idempotent. */
    default void cancel(Minecraft minecraft) {
    }

    default int timeoutTicks() {
        return 20 * 60;
    }

    record ClientOutcome(boolean success, String message, JsonObject data) {

        public static ClientOutcome success(String message) {
            return new ClientOutcome(true, message, null);
        }

        public static ClientOutcome success(String message, JsonObject data) {
            return new ClientOutcome(true, message, data);
        }

        public static ClientOutcome failure(String message) {
            return new ClientOutcome(false, message, null);
        }

        public static ClientOutcome failure(String message, JsonObject data) {
            return new ClientOutcome(false, message, data);
        }
    }
}
