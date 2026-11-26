package com.xuanfei.mcpcontrol.game;

import com.google.gson.JsonObject;

/** Final outcome of a tick-driven action. */
public record TaskOutcome(boolean success, String message, JsonObject data, int ticks) {
}
