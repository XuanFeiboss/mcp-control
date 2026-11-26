package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonObject;

/** Declarative base for the built-in tools. */
public abstract class BaseTool implements McpTool {

    private final String name;
    private final String title;
    private final String description;
    private final JsonObject schema;
    private final boolean readOnly;
    private final boolean destructive;

    protected BaseTool(String name, String title, String description, JsonObject schema,
                       boolean readOnly, boolean destructive) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.schema = schema;
        this.readOnly = readOnly;
        this.destructive = destructive;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonObject inputSchema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public boolean destructive() {
        return destructive;
    }
}
