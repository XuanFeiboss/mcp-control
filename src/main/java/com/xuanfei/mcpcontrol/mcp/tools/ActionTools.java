package com.xuanfei.mcpcontrol.mcp.tools;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.PathFinder;
import com.xuanfei.mcpcontrol.game.tasks.AttackTask;
import com.xuanfei.mcpcontrol.game.tasks.FollowTask;
import com.xuanfei.mcpcontrol.game.tasks.MineTask;
import com.xuanfei.mcpcontrol.game.tasks.MoveTask;
import com.xuanfei.mcpcontrol.game.tasks.PlaceTask;
import com.xuanfei.mcpcontrol.game.tasks.UseItemTask;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolData;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Tools that make the controlled player act in the world. */
public final class ActionTools {

    private ActionTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(new BaseTool("move_to", "Move to coordinates",
                "A* pathfinds and walks the player to the given coordinates. Handles up to 1-block jumps, "
                        + "1-3 block drops, water and ladders. Returns final position and distance to the goal.",
                moveSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.onServer(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    Vec3 goal = new Vec3(JsonUtil.requiredDouble(copy, "x"), JsonUtil.requiredDouble(copy, "y"),
                            JsonUtil.requiredDouble(copy, "z"));
                    double tolerance = JsonUtil.optionalDouble(copy, "tolerance", 1.0, 0.3, 8.0);
                    boolean sprint = JsonUtil.optionalBoolean(copy, "sprint", false);
                    int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 90, 5, 300);
                    PathFinder.Result path = PathFinder.find(level, player.blockPosition(), goal,
                            pos -> Vec3.atCenterOf(pos).distanceToSqr(goal) <= tolerance * tolerance,
                            context.config.maxPathNodes, 256.0);
                    if (path.path().isEmpty()) {
                        throw new ToolError("No path to " + JsonUtil.formatVec(goal) + ". The goal may be inside blocks, "
                                + "too far away, or surrounded by hazards.");
                    }
                    double speed = sprint ? context.config.sprintSpeed : context.config.walkSpeed;
                    return new MoveTask(path.path(), goal, tolerance, speed, sprint,
                            context.config.announceActions, timeout);
                }).thenCompose(task -> context.runTask(task, JsonUtil.optionalInt(copy, "timeout_seconds", 90, 5, 300)));
            }
        });

        registry.register(new BaseTool("mine_block", "Mine block",
                "Walks within reach if needed, selects the best tool from the inventory, then mines one block "
                        + "through the vanilla break pipeline and picks up nearby drops.",
                positionSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos target = JsonUtil.requiredBlockPos(copy);
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 45, 5, 120);
                return context.runTask(new MineTask(target, context.config.announceActions,
                        context.config.maxPathNodes), timeout);
            }
        });

        registry.register(new BaseTool("place_block", "Place block",
                "Places a block item from the inventory against a sturdy neighbour of the target position. "
                        + "If 'item' is omitted the first block item in the inventory is used.",
                placeSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                BlockPos target = JsonUtil.requiredBlockPos(copy);
                String item = JsonUtil.optionalString(copy, "item", "");
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 30, 5, 120);
                return context.runTask(new PlaceTask(target, item, context.config.announceActions,
                        context.config.maxPathNodes), timeout);
            }
        });

        registry.register(new BaseTool("attack_entity", "Attack entity",
                "Walks into melee range and performs charged attacks (12 tick cooldown). "
                        + "Specify an entity uuid from get_nearby_entities, or a type such as minecraft:zombie.",
                attackSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                UUID uuid = parseUuid(JsonUtil.optionalString(copy, "uuid", ""));
                String type = JsonUtil.optionalString(copy, "type", "");
                if (uuid == null && type.isBlank()) {
                    throw new ToolError("Provide either 'uuid' or 'type'");
                }
                int hits = JsonUtil.optionalInt(copy, "hits", 5, 1, 50);
                double radius = JsonUtil.optionalDouble(copy, "radius", 32.0, 4.0, 64.0);
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 60, 5, 120);
                return context.runTask(new AttackTask(uuid, type, radius, hits,
                        context.config.announceActions), timeout);
            }
        });

        registry.register(new BaseTool("follow_entity", "Follow entity",
                "Starts continuously following an entity, replanning while it moves. "
                        + "Returns immediately; call get_task or stop_task to manage it.",
                followSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.onServer(() -> {
                    ServerPlayer player = context.player();
                    UUID uuid = parseUuid(JsonUtil.optionalString(copy, "uuid", ""));
                    String type = JsonUtil.optionalString(copy, "type", "");
                    if (uuid == null && type.isBlank()) {
                        throw new ToolError("Provide either 'uuid' or 'type'");
                    }
                    double distance = JsonUtil.optionalDouble(copy, "distance", 3.0, 1.5, 8.0);
                    double radius = JsonUtil.optionalDouble(copy, "radius", 32.0, 4.0, 64.0);
                    context.tasks.start(new FollowTask(uuid, type, radius, distance,
                            context.config.announceActions), player);
                    JsonObject data = context.tasks.status();
                    return ToolResult.ok("Following target", data);
                });
            }
        });

        registry.register(new BaseTool("use_item", "Use item",
                "Right-clicks / starts using the item in the main hand or offhand, and waits until its use "
                        + "duration finishes (food, potions, bows, shields, buckets, etc.).",
                useItemSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                String handValue = JsonUtil.optionalString(copy, "hand", "main");
                InteractionHand hand = handValue.equalsIgnoreCase("offhand") || handValue.equalsIgnoreCase("off")
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                int timeout = JsonUtil.optionalInt(copy, "timeout_seconds", 30, 5, 120);
                boolean wait = JsonUtil.optionalBoolean(copy, "wait", true);
                if (!wait) {
                    return context.onServer(() -> {
                        context.tasks.start(new UseItemTask(hand, context.config.announceActions), context.player());
                        return ToolResult.ok("Using item in " + hand.name().toLowerCase()
                                + "; call release_use_item to fire/stop, or get_task for status.");
                    });
                }
                return context.runTask(new UseItemTask(hand, context.config.announceActions), timeout);
            }
        });

        registry.register(new BaseTool("look_at", "Look at",
                "Turns the player's head to coordinates or an entity uuid without moving.",
                lookSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    Vec3 target;
                    String uuidValue = JsonUtil.optionalString(copy, "uuid", "");
                    if (!uuidValue.isBlank()) {
                        Entity entity = level.getEntity(parseUuid(uuidValue));
                        if (entity == null) {
                            throw new ToolError("Entity " + uuidValue + " was not found");
                        }
                        target = entity.getEyePosition();
                    } else {
                        target = new Vec3(JsonUtil.requiredDouble(copy, "x"), JsonUtil.requiredDouble(copy, "y"),
                                JsonUtil.requiredDouble(copy, "z"));
                    }
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
                    player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                    JsonObject data = new JsonObject();
                    data.add("target", JsonUtil.vec(target));
                    data.addProperty("yaw", JsonUtil.round(player.getYRot()));
                    data.addProperty("pitch", JsonUtil.round(player.getXRot()));
                    return ToolResult.ok("Looking at " + JsonUtil.formatVec(target), data);
                });
            }
        });

        registry.register(new BaseTool("interact_block", "Interact with block",
                "Right-clicks a block within reach: doors, buttons, levers, trapdoors, crafting tables, beds, etc. "
                        + "Uses the currently selected item.",
                positionSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    BlockPos pos = JsonUtil.requiredBlockPos(copy);
                    Vec3 center = Vec3.atCenterOf(pos);
                    if (player.getEyePosition().distanceTo(center) > 4.8) {
                        throw new ToolError("Block is out of reach; move closer first");
                    }
                    Vec3 delta = center.subtract(player.getEyePosition());
                    Direction face = Direction.getNearest(delta.x, delta.y, delta.z);
                    Vec3 hit = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, center);
                    player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                    ItemStack stack = player.getInventory().getSelected();
                    InteractionResult result = player.gameMode.useItemOn(player, level, stack,
                            InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
                    if (result.shouldSwing()) {
                        player.swing(InteractionHand.MAIN_HAND, true);
                    }
                    JsonObject data = ToolData.blockInfo(level, pos);
                    data.addProperty("interaction_result", result.name());
                    return ToolResult.ok("Interacted with " + JsonUtil.formatPos(pos) + " (" + result.name() + ")", data);
                });
            }
        });

        registry.register(new BaseTool("interact_entity", "Interact with entity",
                "Right-clicks an entity within reach, e.g. a villager, horse, boat, or item frame.",
                entityInteractSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    UUID uuid = parseUuid(JsonUtil.requiredString(copy, "uuid"));
                    Entity entity = level.getEntity(uuid);
                    if (entity == null) {
                        throw new ToolError("Entity " + uuid + " was not found");
                    }
                    if (player.getEyePosition().distanceTo(entity.getEyePosition()) > 4.0) {
                        throw new ToolError("Entity is out of reach; move closer first");
                    }
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
                    InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
                    if (result.shouldSwing()) {
                        player.swing(InteractionHand.MAIN_HAND, true);
                    }
                    JsonObject data = ToolData.entityInfo(entity, player);
                    data.addProperty("interaction_result", result.name());
                    return ToolResult.ok("Interacted with " + entity.getName().getString() + " (" + result.name() + ")", data);
                });
            }
        });

        registry.register(new BaseTool("mount_entity", "Mount entity",
                "Starts riding a nearby entity such as a horse, boat or minecart. The entity must be within reach.",
                entityInteractSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    UUID uuid = parseUuid(JsonUtil.requiredString(copy, "uuid"));
                    Entity entity = level.getEntity(uuid);
                    if (entity == null) {
                        throw new ToolError("Entity " + uuid + " was not found");
                    }
                    if (player.getVehicle() != null) {
                        return ToolResult.error("Already riding " + player.getVehicle().getName().getString() + "; dismount first.");
                    }
                    if (player.getEyePosition().distanceTo(entity.position()) > 4.5) {
                        throw new ToolError("Entity is out of reach; move closer first.");
                    }
                    if (!player.startRiding(entity, true)) {
                        return ToolResult.error("Could not mount " + entity.getName().getString() + ".");
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("vehicle", entity.getUUID().toString());
                    data.addProperty("vehicle_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    return ToolResult.ok("Mounted " + entity.getName().getString(), data);
                });
            }
        });

        registry.register(new BaseTool("dismount", "Dismount",
                "Leaves the current vehicle.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    if (player.getVehicle() == null) {
                        return ToolResult.ok("Not riding anything.");
                    }
                    String name = player.getVehicle().getName().getString();
                    player.stopRiding();
                    return ToolResult.ok("Dismounted " + name);
                });
            }
        });

        registry.register(new BaseTool("release_use_item", "Release use item",
                "Releases a currently charging item such as a bow, crossbow or trident, firing it. "
                        + "Use after use_item with wait=false.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    if (!player.isUsingItem()) {
                        return ToolResult.ok("The player is not using an item.");
                    }
                    ItemStack stack = player.getUseItem();
                    player.releaseUsingItem();
                    JsonObject data = new JsonObject();
                    data.add("item", JsonUtil.item(stack));
                    return ToolResult.ok("Released " + JsonUtil.itemId(stack), data);
                });
            }
        });

        registry.register(new BaseTool("stop_task", "Stop current action",
                "Cancels the running action (movement, mining, attacking, following, using an item).",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.onServer(() -> {
                    context.tasks.stop("Stopped by MCP client");
                    JsonObject data = context.tasks.status();
                    return ToolResult.ok("Action stopped", data);
                });
            }
        });

        registry.register(new BaseTool("get_task", "Get current action",
                "Shows whether an action is running, its name, description, elapsed seconds and last result.",
                ToolSchemas.schema(new JsonObject()), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.onServer(() -> ToolResult.ok("Task status", context.tasks.status()));
            }
        });

        registry.register(new BaseTool("set_flags", "Set movement flags",
                "Sets or clears the sneak and sprint flags. Sprint is normally managed by move/attack tasks.",
                flagsSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    if (copy.has("sneak")) {
                        player.setShiftKeyDown(JsonUtil.optionalBoolean(copy, "sneak", false));
                    }
                    if (copy.has("sprint")) {
                        player.setSprinting(JsonUtil.optionalBoolean(copy, "sprint", false));
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("sneaking", player.isShiftKeyDown());
                    data.addProperty("sprinting", player.isSprinting());
                    return ToolResult.ok("Flags updated", data);
                });
            }
        });

        registry.register(new BaseTool("jump", "Jump",
                "Makes the player jump once if standing on ground.",
                ToolSchemas.schema(new JsonObject()), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    if (!player.onGround()) {
                        return ToolResult.error("The player is not on the ground and cannot jump now.");
                    }
                    player.jumpFromGround();
                    return ToolResult.ok("Jumped");
                });
            }
        });
    }

    private static JsonObject positionSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum seconds to wait", 5, 120));
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject moveSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.numericPosition(properties);
        properties.add("tolerance", ToolSchemas.number("How close is close enough, in blocks", 0.3, 8.0));
        properties.add("sprint", ToolSchemas.bool("Sprint while moving"));
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum seconds to wait", 5, 300));
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject placeSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        properties.add("item", ToolSchemas.string("Optional block item id, e.g. minecraft:cobblestone"));
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum seconds to wait", 5, 120));
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject attackSchema() {
        JsonObject properties = new JsonObject();
        properties.add("uuid", ToolSchemas.string("Target entity uuid from get_nearby_entities"));
        properties.add("type", ToolSchemas.string("Alternative to uuid: entity type id, e.g. minecraft:zombie"));
        properties.add("hits", ToolSchemas.integer("Number of charged hits", 1, 50));
        properties.add("radius", ToolSchemas.number("Search radius when resolving by type", 4, 64));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject followSchema() {
        JsonObject properties = new JsonObject();
        properties.add("uuid", ToolSchemas.string("Target entity uuid"));
        properties.add("type", ToolSchemas.string("Alternative to uuid: entity type id"));
        properties.add("distance", ToolSchemas.number("Stop distance in blocks", 1.5, 8.0));
        properties.add("radius", ToolSchemas.number("Search radius when resolving by type", 4, 64));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject useItemSchema() {
        JsonObject properties = new JsonObject();
        properties.add("hand", ToolSchemas.enumeration("Which hand holds the item", "main", "offhand"));
        properties.add("wait", ToolSchemas.bool("Wait for the use duration to finish (default true; set false for bows)"));
        properties.add("timeout_seconds", ToolSchemas.integer("Maximum seconds to wait", 5, 120));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject lookSchema() {
        JsonObject properties = new JsonObject();
        properties.add("uuid", ToolSchemas.string("Entity uuid to look at (alternative to x/y/z)"));
        ToolSchemas.numericPosition(properties);
        return ToolSchemas.schema(properties);
    }

    private static JsonObject entityInteractSchema() {
        JsonObject properties = new JsonObject();
        properties.add("uuid", ToolSchemas.string("Entity uuid from get_nearby_entities"));
        return ToolSchemas.schema(properties, "uuid");
    }

    private static JsonObject flagsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("sneak", ToolSchemas.bool("Hold sneak"));
        properties.add("sprint", ToolSchemas.bool("Sprint"));
        return ToolSchemas.schema(properties);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException error) {
            throw new ToolError("Invalid uuid: " + value);
        }
    }
}
