package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side player actions. Movement uses the real key bindings (so collision, sprinting and
 * hunger behave normally); mining, placing and using go through {@code MultiPlayerGameMode}, which is
 * exactly the path a player's mouse input takes.
 */
public final class PlayerDriver {

    private static final double REACH = 4.5;

    private final Minecraft minecraft;
    private final InputDriver input;

    public PlayerDriver(Minecraft minecraft, InputDriver input) {
        this.minecraft = minecraft;
        this.input = input;
    }

    // ---------------------------------------------------------------- state

    public JsonObject state() {
        JsonObject object = new JsonObject();
        object.addProperty("in_world", minecraft.level != null);
        object.addProperty("singleplayer", minecraft.getSingleplayerServer() != null);
        if (minecraft.player != null) {
            JsonObject player = new JsonObject();
            player.addProperty("name", minecraft.player.getName().getString());
            player.addProperty("uuid", minecraft.player.getUUID().toString());
            player.add("position", JsonUtil.vec(minecraft.player.position()));
            player.addProperty("yaw", JsonUtil.round(minecraft.player.getYRot()));
            player.addProperty("pitch", JsonUtil.round(minecraft.player.getXRot()));
            player.addProperty("health", JsonUtil.round(minecraft.player.getHealth()));
            player.addProperty("max_health", JsonUtil.round(minecraft.player.getMaxHealth()));
            player.addProperty("food", minecraft.player.getFoodData().getFoodLevel());
            player.addProperty("air", minecraft.player.getAirSupply());
            player.addProperty("on_ground", minecraft.player.onGround());
            player.addProperty("sprinting", minecraft.player.isSprinting());
            player.addProperty("sneaking", minecraft.player.isShiftKeyDown());
            player.addProperty("using_item", minecraft.player.isUsingItem());
            player.addProperty("creative", minecraft.player.isCreative());
            player.addProperty("selected_hotbar_slot", minecraft.player.getInventory().selected);
            player.addProperty("hotbar_slot", minecraft.player.getInventory().selected + 1);
            player.add("held_item", JsonUtil.item(minecraft.player.getInventory().getSelected()));
            player.add("offhand_item", JsonUtil.item(minecraft.player.getOffhandItem()));
            Object mode = Ref.callOpt(minecraft.gameMode, "getPlayerMode");
            if (mode != null) {
                player.addProperty("game_mode", String.valueOf(mode).toLowerCase(Locale.ROOT));
            }
            object.add("player", player);
            if (minecraft.level != null) {
                object.addProperty("dimension", minecraft.level.dimension().location().toString());
            }
        }
        if (minecraft.player != null && minecraft.player.containerMenu != null) {
            object.addProperty("container_id", minecraft.player.containerMenu.containerId);
            object.addProperty("in_container",
                    minecraft.player.containerMenu != minecraft.player.inventoryMenu);
        }
        object.addProperty("virtual_input", input.any());
        return object;
    }

    public JsonObject blockInfo(BlockPos pos) {
        JsonObject object = new JsonObject();
        Level level = minecraft.level;
        if (level == null) {
            object.addProperty("error", "not in a world");
            return object;
        }
        BlockState state = level.getBlockState(pos);
        object.add("position", JsonUtil.blockPos(pos));
        object.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        object.addProperty("air", state.isAir());
        object.addProperty("solid", state.isSolid());
        object.addProperty("destroy_speed", JsonUtil.round(state.getDestroySpeed(level, pos)));
        object.addProperty("distance", minecraft.player == null
                ? -1
                : JsonUtil.round(Math.sqrt(minecraft.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)))));
        return object;
    }

    public JsonObject entitiesJson(double radius) {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (Entity entity : entities(radius)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entry.addProperty("uuid", entity.getUUID().toString());
            entry.addProperty("name", entity.getName().getString());
            entry.add("position", JsonUtil.vec(entity.position()));
            entry.addProperty("distance", JsonUtil.round(Math.sqrt(entity.distanceToSqr(minecraft.player))));
            entry.addProperty("alive", entity.isAlive());
            if (entity instanceof LivingEntity living) {
                entry.addProperty("health", JsonUtil.round(living.getHealth()));
                entry.addProperty("max_health", JsonUtil.round(living.getMaxHealth()));
            }
            array.add(entry);
        }
        object.add("entities", array);
        object.addProperty("count", array.size());
        object.addProperty("radius", radius);
        return object;
    }

    public List<Entity> entities(double radius) {
        List<Entity> result = new ArrayList<>();
        if (minecraft.level == null || minecraft.player == null) {
            return result;
        }
        Vec3 centre = minecraft.player.position();
        AABB box = new AABB(centre.x - radius, centre.y - radius, centre.z - radius,
                centre.x + radius, centre.y + radius, centre.z + radius);
        for (Entity entity : minecraft.level.getEntities(minecraft.player, box)) {
            if (entity.isAlive() && entity != minecraft.player) {
                result.add(entity);
            }
        }
        return result;
    }

    public Entity entityByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        for (Entity entity : entities(64)) {
            if (entity.getUUID().toString().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- view

    public void look(float yaw, float pitch) {
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(Math.max(-90.0F, Math.min(90.0F, pitch)));
    }

    public void lookAt(Vec3 target) {
        if (minecraft.player == null || target == null) {
            return;
        }
        Vec3 eye = minecraft.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        look(yaw, pitch);
    }

    public void lookAt(BlockPos pos) {
        lookAt(Vec3.atCenterOf(pos));
    }

    // ---------------------------------------------------------------- tasks

    public ClientTask moveTask(boolean forward, boolean back, boolean left, boolean right,
                               boolean jump, boolean sneak, boolean sprint, int ticks) {
        return new ClientTask() {
            private int elapsed;

            @Override
            public String name() {
                return "client_input";
            }

            @Override
            public int timeoutTicks() {
                return Math.max(20, ticks + 40);
            }

            @Override
            public void begin(Minecraft mc) {
                input.set(forward, back, left, right, jump, sneak, sprint);
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null) {
                    input.release();
                    return ClientTask.ClientOutcome.failure("No player is loaded.");
                }
                if (mc.screen != null) {
                    input.release();
                    return ClientTask.ClientOutcome.failure("A screen is open ('"
                            + mc.screen.getClass().getSimpleName() + "'), so movement input is ignored. Close it first.");
                }
                if (elapsed >= ticks) {
                    input.release();
                    JsonObject data = state();
                    return ClientTask.ClientOutcome.success("Movement input finished after " + ticks + " ticks.", data);
                }
                return null;
            }

            @Override
            public void cancel(Minecraft mc) {
                input.release();
            }
        };
    }

    public ClientTask mineTask(BlockPos pos) {
        return new ClientTask() {
            private boolean started;
            private int elapsed;

            @Override
            public String name() {
                return "client_mine_block";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 20;
            }

            @Override
            public void begin(Minecraft mc) {
                if (mc.player == null || mc.level == null) {
                    throw new IllegalStateException("No world is loaded.");
                }
                if (mc.level.getBlockState(pos).isAir()) {
                    throw new IllegalStateException("There is no block at " + JsonUtil.formatPos(pos) + " (air).");
                }
                double distance = Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
                if (distance > REACH) {
                    throw new IllegalStateException("Block is " + JsonUtil.round(distance)
                            + " blocks away; move closer (reach is about " + REACH + ").");
                }
                lookAt(pos);
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null || mc.level == null || mc.gameMode == null) {
                    return ClientTask.ClientOutcome.failure("No world is loaded.");
                }
                lookAt(pos);
                Direction side = facing(mc.player.getEyePosition(), Vec3.atCenterOf(pos));
                if (!started) {
                    Ref.call(mc.gameMode, "startDestroyBlock", pos, side);
                    started = true;
                } else {
                    Ref.call(mc.gameMode, "continueDestroyBlock", pos, side);
                }
                if (mc.level.getBlockState(pos).isAir()) {
                    JsonObject data = new JsonObject();
                    data.add("position", JsonUtil.blockPos(pos));
                    data.addProperty("seconds", JsonUtil.round(elapsed / 20.0));
                    return ClientTask.ClientOutcome.success("Block broken at " + JsonUtil.formatPos(pos) + ".", data);
                }
                if (elapsed % 20 == 0 && elapsed >= 20 * 5) {
                    return null;
                }
                if (elapsed > 20 * 12) {
                    JsonObject data = blockInfo(pos);
                    return ClientTask.ClientOutcome.failure("The block did not break after 12s (too hard, wrong tool, "
                            + "protected, or out of reach).", data);
                }
                return null;
            }

            @Override
            public void cancel(Minecraft mc) {
                if (mc.gameMode != null) {
                    Ref.callOpt(mc.gameMode, "stopDestroyBlock");
                }
            }
        };
    }

    public ClientTask placeTask(BlockPos pos, Direction side, int hotbarSlot) {
        return new ClientTask() {
            private boolean used;
            private int elapsed;

            @Override
            public String name() {
                return "client_place_block";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 6;
            }

            @Override
            public void begin(Minecraft mc) {
                if (mc.player == null || mc.level == null) {
                    throw new IllegalStateException("No world is loaded.");
                }
                if (hotbarSlot >= 0) {
                    selectHotbar(Math.min(8, hotbarSlot));
                }
                if (mc.player.getInventory().getSelected().isEmpty()) {
                    throw new IllegalStateException("The selected hotbar slot is empty; put a block there first.");
                }
                BlockPos neighbour = pos.relative(side.getOpposite());
                if (mc.level.getBlockState(neighbour).isAir()) {
                    throw new IllegalStateException("There is nothing to place against at "
                            + JsonUtil.formatPos(neighbour) + " (it is air).");
                }
                lookAt(neighbour);
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null || mc.level == null) {
                    return ClientTask.ClientOutcome.failure("No world is loaded.");
                }
                if (!used) {
                    BlockPos neighbour = pos.relative(side.getOpposite());
                    Vec3 hitPoint = Vec3.atCenterOf(neighbour)
                            .add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
                    BlockHitResult hit = new BlockHitResult(hitPoint, side, neighbour, false);
                    lookAt(neighbour);
                    Ref.call(mc.gameMode, "useItemOn", mc.player, InteractionHand.MAIN_HAND, hit);
                    used = true;
                    return null;
                }
                if (!mc.level.getBlockState(pos).isAir()) {
                    JsonObject data = blockInfo(pos);
                    return ClientTask.ClientOutcome.success("Placed a block at " + JsonUtil.formatPos(pos) + ".", data);
                }
                if (elapsed > 20 * 3) {
                    return ClientTask.ClientOutcome.failure("Nothing was placed at " + JsonUtil.formatPos(pos)
                            + ". The face may be blocked, the item may not be a block, or the area is protected.");
                }
                return null;
            }
        };
    }

    public ClientTask useTask(boolean offhand, int holdTicks) {
        return new ClientTask() {
            private int elapsed;

            @Override
            public String name() {
                return "client_use_item";
            }

            @Override
            public int timeoutTicks() {
                return Math.max(40, holdTicks + 40);
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null) {
                    return ClientTask.ClientOutcome.failure("No player is loaded.");
                }
                if (elapsed == 1) {
                    InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                    Ref.call(mc.gameMode, "useItem", mc.player, hand);
                    return null;
                }
                if (elapsed >= Math.max(1, holdTicks)) {
                    if (holdTicks > 1) {
                        mc.player.stopUsingItem();
                    }
                    JsonObject data = new JsonObject();
                    data.add("held_item", JsonUtil.item(mc.player.getInventory().getSelected()));
                    return ClientTask.ClientOutcome.success("Item used.", data);
                }
                return null;
            }
        };
    }

    public ClientTask attackTask(String uuid, double maxDistance) {
        return new ClientTask() {
            private int elapsed;

            @Override
            public String name() {
                return "client_attack";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 10;
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null || mc.level == null) {
                    return ClientTask.ClientOutcome.failure("No world is loaded.");
                }
                Entity target = entityByUuid(uuid);
                if (target == null) {
                    return ClientTask.ClientOutcome.failure("Entity '" + uuid + "' is not loaded within 64 blocks.");
                }
                double distance = Math.sqrt(target.distanceToSqr(mc.player));
                if (distance > maxDistance) {
                    return ClientTask.ClientOutcome.failure("Target is " + JsonUtil.round(distance)
                            + " blocks away; move closer than " + JsonUtil.round(maxDistance) + ".");
                }
                Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
                lookAt(aim);
                if (elapsed < 2) {
                    return null;
                }
                Ref.call(mc.gameMode, "attack", mc.player, target);
                JsonObject data = new JsonObject();
                data.addProperty("target", target.getName().getString());
                data.addProperty("uuid", target.getUUID().toString());
                if (target instanceof LivingEntity living) {
                    data.addProperty("health_after", JsonUtil.round(living.getHealth()));
                }
                return ClientTask.ClientOutcome.success("Attacked " + target.getName().getString() + ".", data);
            }
        };
    }

    public ClientTask interactTask(BlockPos pos, boolean offhand) {
        return new ClientTask() {
            private int elapsed;

            @Override
            public String name() {
                return "client_interact_block";
            }

            @Override
            public int timeoutTicks() {
                return 20 * 6;
            }

            @Override
            public void begin(Minecraft mc) {
                if (mc.player == null || mc.level == null) {
                    throw new IllegalStateException("No world is loaded.");
                }
                double distance = Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
                if (distance > REACH) {
                    throw new IllegalStateException("Block is " + JsonUtil.round(distance)
                            + " blocks away; move closer (reach is about " + REACH + ").");
                }
                lookAt(pos);
            }

            @Override
            public ClientTask.ClientOutcome tick(Minecraft mc) {
                elapsed++;
                if (mc.player == null || mc.level == null) {
                    return ClientTask.ClientOutcome.failure("No world is loaded.");
                }
                if (elapsed > 1) {
                    JsonObject data = blockInfo(pos);
                    if (mc.screen != null) {
                        data.addProperty("opened_screen", mc.screen.getClass().getSimpleName());
                    }
                    data.addProperty("in_container", mc.player.containerMenu != mc.player.inventoryMenu);
                    return ClientTask.ClientOutcome.success("Interacted with " + JsonUtil.formatPos(pos) + ".", data);
                }
                lookAt(pos);
                Direction side = facing(mc.player.getEyePosition(), Vec3.atCenterOf(pos));
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), side, pos, false);
                InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                Ref.call(mc.gameMode, "useItemOn", mc.player, hand, hit);
                return null;
            }
        };
    }

    // ---------------------------------------------------------------- misc

    public void selectHotbar(int slot) {
        if (minecraft.player == null) {
            return;
        }
        int index = Math.max(0, Math.min(8, slot));
        minecraft.player.getInventory().selected = index;
        Object packet = Ref.newInstanceIfPossible(
                "net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket", index);
        if (packet != null) {
            Ref.callOpt(minecraft.player.connection, "send", packet);
        }
    }

    public String itemNameOf(String uuid) {
        Entity entity = entityByUuid(uuid);
        return entity == null ? "" : entity.getName().getString();
    }

    /** Dominant axis from one point to another, used as the block face for mining/placing. */
    static Direction facing(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
