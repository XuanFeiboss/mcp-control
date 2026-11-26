package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered registry of tools advertised through {@code tools/list}. */
public final class ToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public void register(McpTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("Duplicate MCP tool: " + tool.name());
        }
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public Collection<McpTool> all() {
        return tools.values();
    }

    public int size() {
        return tools.size();
    }

    public JsonArray listJson() {
        JsonArray array = new JsonArray();
        for (McpTool tool : tools.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("title", tool.title());
            entry.addProperty("description", tool.description());
            entry.add("inputSchema", tool.inputSchema());

            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", tool.readOnly());
            annotations.addProperty("destructiveHint", tool.destructive());
            annotations.addProperty("idempotentHint", tool.readOnly());
            annotations.addProperty("openWorldHint", true);
            entry.add("annotations", annotations);
            array.add(entry);
        }
        return array;
    }

    public JsonArray namesJson() {
        JsonArray array = new JsonArray();
        for (McpTool tool : tools.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.addProperty("read_only", tool.readOnly());
            array.add(entry);
        }
        return array;
    }
}
