package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small JSON-Schema builders for tool input schemas. */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static JsonObject schema(JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required.length > 0) {
            JsonArray array = new JsonArray();
            for (String name : required) {
                array.add(name);
            }
            schema.add("required", array);
        }
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    public static JsonObject string(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    public static JsonObject integer(String description, int min, int max) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("minimum", min);
        property.addProperty("maximum", max);
        return property;
    }

    public static JsonObject number(String description, double min, double max) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "number");
        property.addProperty("description", description);
        property.addProperty("minimum", min);
        property.addProperty("maximum", max);
        return property;
    }

    public static JsonObject bool(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        return property;
    }

    public static JsonObject enumeration(String description, String... values) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        property.add("enum", array);
        return property;
    }

    /** Adds integer x/y/z properties to the given property object. */
    public static void integerPosition(JsonObject properties) {
        properties.add("x", integer("Block X coordinate", -30000000, 30000000));
        properties.add("y", integer("Block Y coordinate", -2048, 2048));
        properties.add("z", integer("Block Z coordinate", -30000000, 30000000));
    }

    /** Adds numeric x/y/z properties to the given property object. */
    public static void numericPosition(JsonObject properties) {
        properties.add("x", number("X coordinate", -30000000, 30000000));
        properties.add("y", number("Y coordinate", -2048, 2048));
        properties.add("z", number("Z coordinate", -30000000, 30000000));
    }

    public static JsonObject object(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "object");
        property.addProperty("description", description);
        return property;
    }
}
