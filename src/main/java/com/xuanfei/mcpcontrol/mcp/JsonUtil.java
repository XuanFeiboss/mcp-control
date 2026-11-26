package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/** Small helpers shared by the MCP layer and tools. */
public final class JsonUtil {

    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private JsonUtil() {
    }

    public static JsonObject obj(Object... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            if (value == null) {
                continue;
            }
            if (value instanceof JsonElement element) {
                object.add(key, element);
            } else if (value instanceof Number number) {
                object.addProperty(key, number);
            } else if (value instanceof Boolean bool) {
                object.addProperty(key, bool);
            } else {
                object.addProperty(key, String.valueOf(value));
            }
        }
        return object;
    }

    public static JsonArray array(Collection<?> values) {
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (value instanceof JsonElement element) {
                array.add(element);
            } else if (value instanceof Number number) {
                array.add(number);
            } else if (value instanceof Boolean bool) {
                array.add(bool);
            } else {
                array.add(String.valueOf(value));
            }
        }
        return array;
    }

    public static JsonObject vec(Vec3 vec) {
        JsonObject object = new JsonObject();
        object.addProperty("x", round(vec.x));
        object.addProperty("y", round(vec.y));
        object.addProperty("z", round(vec.z));
        return object;
    }

    public static JsonObject blockPos(BlockPos pos) {
        JsonObject object = new JsonObject();
        object.addProperty("x", pos.getX());
        object.addProperty("y", pos.getY());
        object.addProperty("z", pos.getZ());
        return object;
    }

    public static JsonObject item(ItemStack stack) {
        JsonObject object = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            object.addProperty("empty", true);
            return object;
        }
        object.addProperty("item", itemId(stack));
        object.addProperty("count", stack.getCount());
        String name = stack.getHoverName().getString();
        if (!name.isEmpty()) {
            object.addProperty("name", name);
        }
        if (stack.isDamageableItem()) {
            object.addProperty("damage", stack.getDamageValue());
            object.addProperty("max_damage", stack.getMaxDamage());
        }
        return object;
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String playerName(net.minecraft.server.level.ServerPlayer player) {
        return player.getName().getString();
    }

    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static String formatVec(Vec3 vec) {
        return String.format("%.2f, %.2f, %.2f", vec.x, vec.y, vec.z);
    }

    public static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    // ----- argument helpers -----

    public static String requiredString(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new ToolError("Missing required string argument '" + key + "'");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new ToolError("Argument '" + key + "' must not be empty");
        }
        return value;
    }

    public static String optionalString(JsonObject args, String key, String fallback) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsString();
    }

    public static int requiredInt(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ToolError("Missing required integer argument '" + key + "'");
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            throw new ToolError("Argument '" + key + "' must be an integer");
        }
    }

    public static int optionalInt(JsonObject args, String key, int fallback, int min, int max) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        int value;
        try {
            value = element.getAsInt();
        } catch (RuntimeException e) {
            throw new ToolError("Argument '" + key + "' must be an integer");
        }
        return Math.max(min, Math.min(max, value));
    }

    public static double requiredDouble(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            throw new ToolError("Missing required number argument '" + key + "'");
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            throw new ToolError("Argument '" + key + "' must be a number");
        }
    }

    public static double optionalDouble(JsonObject args, String key, double fallback, double min, double max) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        double value;
        try {
            value = element.getAsDouble();
        } catch (RuntimeException e) {
            throw new ToolError("Argument '" + key + "' must be a number");
        }
        return Math.max(min, Math.min(max, value));
    }

    public static boolean optionalBoolean(JsonObject args, String key, boolean fallback) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    public static BlockPos requiredBlockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    public static Vec3 requiredVec(JsonObject args) {
        return new Vec3(requiredDouble(args, "x"), requiredDouble(args, "y"), requiredDouble(args, "z"));
    }
}
