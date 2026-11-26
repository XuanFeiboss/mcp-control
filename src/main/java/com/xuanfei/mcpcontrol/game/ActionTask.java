package com.xuanfei.mcpcontrol.game;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A long-running action driven one server tick at a time. Tools such as move_to/mine_block
 * create a task and wait for its future, while follow_entity leaves it running until stop.
 */
public interface ActionTask {

    String name();

    String describe();

    default void begin(ServerLevel level, ServerPlayer player) throws Exception {
    }

    Step tick(ServerLevel level, ServerPlayer player) throws Exception;

    /** Called on completion, failure and cancellation. Must be idempotent. */
    default void cancel(ServerLevel level, ServerPlayer player) {
    }

    default int timeoutTicks() {
        return 20 * 300;
    }

    record Step(boolean done, boolean success, String message, JsonObject data) {
        public static Step running() {
            return new Step(false, false, "", null);
        }

        public static Step success(String message, JsonObject data) {
            return new Step(true, true, message, data);
        }

        public static Step failure(String message) {
            return new Step(true, false, message, null);
        }

        public static Step failure(String message, JsonObject data) {
            return new Step(true, false, message, data);
        }
    }
}
