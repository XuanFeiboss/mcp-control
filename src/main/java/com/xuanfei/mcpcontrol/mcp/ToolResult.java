package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Result of an MCP tool invocation, optionally carrying inline images (screenshots). */
public record ToolResult(boolean error, String text, JsonObject structured, List<ImageBlock> images) {

    /** One base64 encoded image block, as defined by the MCP content model. */
    public record ImageBlock(String mimeType, String base64) {

        public JsonObject toJson() {
            JsonObject block = new JsonObject();
            block.addProperty("type", "image");
            block.addProperty("data", base64);
            block.addProperty("mimeType", mimeType);
            return block;
        }
    }

    public static ToolResult ok(String text) {
        return new ToolResult(false, text, null, List.of());
    }

    public static ToolResult ok(String text, JsonObject structured) {
        return new ToolResult(false, text, structured, List.of());
    }

    public static ToolResult error(String text) {
        return new ToolResult(true, text, null, List.of());
    }

    public static ToolResult error(String text, JsonObject structured) {
        return new ToolResult(true, text, structured, List.of());
    }

    /** Result with one or more inline PNG screenshots. */
    public static ToolResult image(String text, JsonObject structured, List<ImageBlock> images) {
        return new ToolResult(false, text, structured, images);
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

        if (images != null) {
            for (ImageBlock image : images) {
                if (image != null && image.base64() != null && !image.base64().isEmpty()) {
                    content.add(image.toJson());
                }
            }
        }
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
