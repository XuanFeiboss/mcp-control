package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/** A single MCP tool exposed to the AI client. */
public interface McpTool {

    String name();

    default String title() {
        return name();
    }

    String description();

    JsonObject inputSchema();

    /** True when the tool only reads state and never changes the world or player. */
    default boolean readOnly() {
        return false;
    }

    /** True when the tool may destroy blocks, kill entities or otherwise alter the world. */
    default boolean destructive() {
        return false;
    }

    CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context);
}
