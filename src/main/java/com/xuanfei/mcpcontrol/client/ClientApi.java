package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;

/**
 * Side-agnostic view of the client capabilities. Loaded on both sides (it references no Minecraft
 * client class); only {@link ClientBridge} implements it, and that class is only instantiated on a
 * physical client.
 */
public interface ClientApi {

    /** Registers the client-only tool group into the shared registry. */
    void registerTools(ToolRegistry registry);

    /** Live client state for diagnostics. */
    JsonObject status();

    /** Human readable side name, e.g. "client (single-player)". */
    String describeSide();
}
