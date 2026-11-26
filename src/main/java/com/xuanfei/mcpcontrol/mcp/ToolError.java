package com.xuanfei.mcpcontrol.mcp;

/** User-facing error raised by a tool or the player bridge. */
public class ToolError extends RuntimeException {
    public ToolError(String message) {
        super(message);
    }

    public ToolError(String message, Throwable cause) {
        super(message, cause);
    }
}
