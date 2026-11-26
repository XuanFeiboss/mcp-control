package com.xuanfei.mcpcontrol.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolData;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only observation tools: state, inventory, blocks, entities and world info. */
public final class StateTools {

    private StateTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(new BaseTool("get_player_state", "Get player state",
                "Returns the controlled player's position, dimension, health, hunger, XP, game mode, "
                        + "effects, held item and basic movement flags. Call this first to learn where you are.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    JsonObject data = ToolData.playerState(player, context.server);
                    return ToolResult.ok("Player state for " + player.getScoreboardName(), data);
                });
            }
        });

        registry.register(new BaseTool("get_inventory", "Get inventory",
                "Lists every occupied inventory slot with slot number, area (hotbar/main/armor/offhand), "
                        + "item id, count and durability. Slot numbers: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    JsonObject data = ToolData.inventory(player);
                    data.addProperty("player", player.getScoreboardName());
                    return ToolResult.ok("Inventory of " + player.getScoreboardName(), data);
                });
            }
        });

        registry.register(new BaseTool("get_block", "Get block",
                "Inspects one block: id, state properties, hardness, collision, fluid and block entity type.",
                positionSchema(), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    BlockPos pos = JsonUtil.requiredBlockPos(arguments);
                    ServerLevel level = player.serverLevel();
                    if (!level.isLoaded(pos)) {
                        return ToolResult.error("Block " + JsonUtil.formatPos(pos) + " is not loaded.");
                    }
                    JsonObject data = ToolData.blockInfo(level, pos);
                    data.addProperty("dimension", level.dimension().location().toString());
                    return ToolResult.ok("Block at " + JsonUtil.formatPos(pos), data);
                });
            }
        });

        registry.register(new BaseTool("get_nearby_blocks", "Get nearby blocks",
                "Scans blocks around the player within a radius (max 16) and optionally filters by block id. "
                        + "Useful for finding ores, trees, water, chests and build targets.",
                nearbyBlocksSchema(), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    int radius = JsonUtil.optionalInt(arguments, "radius", 6, 1, 16);
                    String filter = normalizeBlock(JsonUtil.optionalString(arguments, "block", ""));
                    boolean includeAir = JsonUtil.optionalBoolean(arguments, "include_air", false);
                    int limit = JsonUtil.optionalInt(arguments, "max_results", 100, 1, 300);

                    BlockPos center = player.blockPosition();
                    List<JsonObject> found = new ArrayList<>();
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                                    continue;
                                }
                                BlockPos pos = center.offset(dx, dy, dz);
                                BlockState state = level.getBlockState(pos);
                                if (!includeAir && state.isAir()) {
                                    continue;
                                }
                                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                                if (!filter.isEmpty() && !id.equals(filter)) {
                                    continue;
                                }
                                JsonObject entry = new JsonObject();
                                entry.add("position", JsonUtil.blockPos(pos));
                                entry.addProperty("block", id);
                                entry.addProperty("distance", JsonUtil.round(Math.sqrt(pos.distSqr(center))));
                                found.add(entry);
                            }
                        }
                    }
                    found.sort(Comparator.comparingDouble(entry -> entry.get("distance").getAsDouble()));
                    JsonArray array = new JsonArray();
                    for (int i = 0; i < Math.min(limit, found.size()); i++) {
                        array.add(found.get(i));
                    }
                    JsonObject data = new JsonObject();
                    data.add("center", JsonUtil.blockPos(center));
                    data.addProperty("radius", radius);
                    data.addProperty("filter", filter);
                    data.addProperty("count", array.size());
                    data.addProperty("truncated", found.size() > limit);
                    data.add("blocks", array);
                    return ToolResult.ok("Found " + array.size() + " matching blocks", data);
                });
            }
        });

        registry.register(new BaseTool("get_nearby_entities", "Get nearby entities",
                "Lists entities around the player (max 64 blocks) with type, uuid, position, distance and health. "
                        + "Use the uuid for attack_entity/follow_entity; use type to filter.",
                nearbyEntitiesSchema(), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    double radius = JsonUtil.optionalDouble(arguments, "radius", 16.0, 1.0, 64.0);
                    String filter = normalizeEntity(JsonUtil.optionalString(arguments, "type", ""));
                    int limit = JsonUtil.optionalInt(arguments, "max_results", 50, 1, 100);

                    List<Entity> entities = level.getEntitiesOfClass(Entity.class,
                            player.getBoundingBox().inflate(radius),
                            entity -> entity != player && entity.isAlive() && matchesEntity(entity, filter));
                    entities.sort(Comparator.comparingDouble(entity -> player.distanceToSqr(entity)));
                    JsonArray array = new JsonArray();
                    for (int i = 0; i < Math.min(limit, entities.size()); i++) {
                        array.add(ToolData.entityInfo(entities.get(i), player));
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("radius", radius);
                    data.addProperty("filter", filter);
                    data.addProperty("count", array.size());
                    data.addProperty("truncated", entities.size() > limit);
                    data.add("entities", array);
                    return ToolResult.ok("Found " + array.size() + " entities", data);
                });
            }
        });

        registry.register(new BaseTool("get_looking_at", "Get crosshair target",
                "Ray-traces from the player's eyes along the view direction (up to 5 blocks) and reports the block "
                        + "or entity under the crosshair, exactly like a player's pick.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    var hit = player.pick(5.0, 1.0F, false);
                    JsonObject data = new JsonObject();
                    data.addProperty("type", hit.getType().name().toLowerCase());
                    data.add("hit_position", JsonUtil.vec(hit.getLocation()));
                    if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                        data.add("block", ToolData.blockInfo(level, blockHit.getBlockPos()));
                        data.addProperty("face", blockHit.getDirection().getName());
                    } else if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                        data.add("entity", ToolData.entityInfo(entityHit.getEntity(), player));
                    }
                    return ToolResult.ok("Crosshair target: " + hit.getType().name().toLowerCase(), data);
                });
            }
        });

        registry.register(new BaseTool("get_world_state", "Get world state",
                "Returns dimension, game time, day number, weather, difficulty, hardcore flag, moon phase and loaded entity count.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    JsonObject data = ToolData.worldState(context.server, player.serverLevel());
                    return ToolResult.ok("World state of " + player.serverLevel().dimension().location(), data);
                });
            }
        });

        registry.register(new BaseTool("get_server_info", "Get server info",
                "Returns the server motd, Minecraft/NeoForge versions, player counts, tick and average tick time.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> ToolResult.ok("Server info", ToolData.serverInfo(context.server)));
            }
        });

        registry.register(new BaseTool("list_players", "List players",
                "Lists online players with uuid, dimension, position, health, game mode and op level, "
                        + "and shows which player is currently bound to the AI.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public java.util.concurrent.CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> ToolResult.ok("Online players",
                        context.players.status(context.server, context.config)));
            }
        });
    }

    private static JsonObject positionSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject nearbyBlocksSchema() {
        JsonObject properties = new JsonObject();
        properties.add("radius", ToolSchemas.integer("Search radius in blocks", 1, 16));
        properties.add("block", ToolSchemas.string("Optional block id filter, e.g. minecraft:oak_log or oak_log"));
        properties.add("include_air", ToolSchemas.bool("Include air blocks (default false)"));
        properties.add("max_results", ToolSchemas.integer("Maximum returned blocks", 1, 300));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject nearbyEntitiesSchema() {
        JsonObject properties = new JsonObject();
        properties.add("radius", ToolSchemas.number("Search radius in blocks", 1, 64));
        properties.add("type", ToolSchemas.string("Optional entity type filter, e.g. minecraft:zombie or zombie"));
        properties.add("max_results", ToolSchemas.integer("Maximum returned entities", 1, 100));
        return ToolSchemas.schema(properties);
    }

    private static String normalizeBlock(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static String normalizeEntity(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static boolean matchesEntity(Entity entity, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(filter);
    }
}
