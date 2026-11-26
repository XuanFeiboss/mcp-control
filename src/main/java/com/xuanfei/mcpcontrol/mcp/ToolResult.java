package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Result of an MCP tool invocation. */
public record ToolResult(boolean error, String text, JsonObject structured) {

    public static ToolResult ok(String text) {
        return new ToolResult(false, text, null);
    }

    public static ToolResult ok(String text, JsonObject structured) {
        return new ToolResult(false, text, structured);
    }

    public static ToolResult error(String text) {
        return new ToolResult(true, text, null);
    }

    public static ToolResult error(String text, JsonObject structured) {
        return new ToolResult(true, text, structured);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();

        JsonArray content = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        String display = text == null ? "" : text;
        if (structured != null) {
            String json = JsonUtil.COMPACT.toJson(structured);
            if (display.isEmpty()) {
                display = json;
            } else {
                display = display + "\n" + json;
            }
        }
        if (display.length() > 8000) {
            display = display.substring(0, 8000) + "\n...(truncated)";
        }
        textBlock.addProperty("text", display);
        content.add(textBlock);
        result.add("content", content);

        if (structured != null) {
            result.add("structuredContent", structured);
        }
        if (error) {
            result.addProperty("isError", true);
        }
        return result;
    }
}
