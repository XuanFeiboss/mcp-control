package com.xuanfei.mcpcontrol.game.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.game.PathFinder;
import com.xuanfei.mcpcontrol.game.PathFollower;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Mines one block at a time using the vanilla server-side destroy pipeline. */
public final class MineTask implements ActionTask {

    private enum Phase {
        APPROACH,
        MINE,
        PICKUP
    }

    private final BlockPos target;
    private final Vec3 center;
    private final boolean announce;
    private final int maxPathNodes;

    private Phase phase;
    private PathFollower follower;
    private List<BlockPos> path = List.of();
    private Direction face;
    private boolean started;
    private int pickupTicks;
    private String blockId;
    private double hardness;
    private JsonArray drops;
    private final Set<UUID> seenDrops = new HashSet<>();
    private String toolUsed = "";

    public MineTask(BlockPos target, boolean announce) {
        this(target, announce, 12000);
    }

    public MineTask(BlockPos target, boolean announce, int maxPathNodes) {
        this.target = target;
        this.center = Vec3.atCenterOf(target);
        this.announce = announce;
        this.maxPathNodes = Math.max(1000, maxPathNodes);
        this.phase = Phase.MINE;
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String describe() {
        return "mining " + blockId + " at " + JsonUtil.formatPos(target);
    }

    @Override
    public void begin(ServerLevel level, ServerPlayer player) {
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            throw new IllegalArgumentException("There is no block to mine at " + JsonUtil.formatPos(target));
        }
        this.blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        this.hardness = state.getDestroySpeed(level, target);
        if (this.hardness < 0) {
            throw new IllegalArgumentException(blockId + " is unbreakable");
        }
        GameType gameType = player.gameMode.getGameModeForPlayer();
        if (player.blockActionRestricted(level, target, gameType)) {
            throw new IllegalArgumentException("The player is not allowed to break blocks here (adventure/spectator/spawn protection)");
        }

        double eyeDistance = player.getEyePosition().distanceTo(center);
        if (eyeDistance > 3.7) {
            var result = PathFinder.find(level, player.blockPosition(), center,
                    pos -> Vec3.atCenterOf(pos).distanceToSqr(center) <= 3.2 * 3.2,
                    maxPathNodes, 48);
            this.path = result.path();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("No walkable path to " + blockId + " at " + JsonUtil.formatPos(target));
            }
            if (path.size() > 1) {
                this.phase = Phase.APPROACH;
                this.follower = new PathFollower(path, 0.22, false);
            }
        }
        this.face = Direction.getNearest(center.x - player.getX(), center.y - player.getY(), center.z - player.getZ());
        if (announce) {
            player.displayClientMessage(Component.literal("AI: mining " + blockId), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        switch (phase) {
            case APPROACH -> {
                PathFollower.Progress progress = follower.tick(level, player);
                if (progress == PathFollower.Progress.STUCK) {
                    return Step.failure("Could not reach the block; path was blocked near " + JsonUtil.formatVec(player.position()));
                }
                if (progress == PathFollower.Progress.ARRIVED) {
                    phase = Phase.MINE;
                }
                return Step.running();
            }
            case MINE -> {
                BlockState state = level.getBlockState(target);
                if (state.isAir()) {
                    phase = Phase.PICKUP;
                    pickupTicks = 0;
                    drops = new JsonArray();
                    return Step.running();
                }
                if (!started) {
                    double distance = player.getEyePosition().distanceTo(center);
                    if (distance > 4.6) {
                        return Step.failure("The player is " + String.format("%.2f", distance) + " blocks away; move closer first.");
                    }
                    selectBestTool(player, state);
                    faceBlock(player);
                    player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            face, level.getMaxBuildHeight(), 0);
                    started = true;
                    return Step.running();
                }
                if (player.getEyePosition().distanceTo(center) > 5.2) {
                    return Step.failure("The player moved out of reach while mining");
                }
                if (!level.getBlockState(target).isAir()
                        && level.getBlockState(target).getBlock() != state.getBlock()) {
                    return Step.failure("The block changed while mining");
                }
                return Step.running();
            }
            case PICKUP -> {
                collectDrops(level, player);
                if (++pickupTicks >= 12) {
                    JsonObject data = new JsonObject();
                    data.addProperty("block", blockId);
                    data.add("position", JsonUtil.blockPos(target));
                    data.addProperty("hardness", hardness);
                    data.addProperty("tool", toolUsed);
                    data.add("drops", drops == null ? new JsonArray() : drops);
                    if (announce) {
                        player.displayClientMessage(Component.literal("AI: mined " + blockId), true);
                    }
                    return Step.success("Mined " + blockId + " at " + JsonUtil.formatPos(target) + ".", data);
                }
                return Step.running();
            }
            default -> {
                return Step.failure("Unknown mining phase");
            }
        }
    }

    private void faceBlock(ServerPlayer player) {
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, center);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private void selectBestTool(ServerPlayer player, BlockState state) {
        var inventory = player.getInventory();
        int selected = inventory.selected;
        ItemStack current = inventory.getItem(selected);
        double currentScore = score(current, state, player);
        int bestSlot = selected;
        double bestScore = currentScore;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            double score = score(stack, state, player);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        // Any correct tool elsewhere in the inventory is moved into the selected hotbar slot.
        if (bestScore <= currentScore) {
            for (int slot = 9; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                double score = score(stack, state, player);
                if (score > bestScore) {
                    bestScore = score;
                    ItemStack hotbarStack = inventory.getItem(selected);
                    inventory.setItem(selected, stack);
                    inventory.setItem(slot, hotbarStack);
                    player.inventoryMenu.broadcastChanges();
                    bestSlot = selected;
                    break;
                }
            }
        }
        if (bestSlot != selected) {
            inventory.selected = bestSlot;
            player.connection.send(new ClientboundSetCarriedItemPacket(bestSlot));
        }
        ItemStack chosen = inventory.getItem(inventory.selected);
        this.toolUsed = JsonUtil.itemId(chosen);
    }

    private double score(ItemStack stack, BlockState state, ServerPlayer player) {
        if (stack == null || stack.isEmpty()) {
            return 0.0;
        }
        double speed = stack.getDestroySpeed(state);
        if (stack.isCorrectToolForDrops(state)) {
            return 1000.0 + speed;
        }
        return speed;
    }

    private void collectDrops(ServerLevel level, ServerPlayer player) {
        AABB box = new AABB(target).inflate(3.0);
        for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!seenDrops.add(itemEntity.getUUID())) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject drop = JsonUtil.item(stack);
            boolean alive = !itemEntity.isRemoved();
            itemEntity.playerTouch(player);
            drop.addProperty("collected", alive && itemEntity.isRemoved());
            if (drop.has("item")) {
                drops.add(drop);
            }
        }
    }

    @Override
    public void cancel(ServerLevel level, ServerPlayer player) {
        if (started && !level.getBlockState(target).isAir()) {
            player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    face, level.getMaxBuildHeight(), 0);
        }
        started = false;
        player.setSprinting(false);
    }

    @Override
    public int timeoutTicks() {
        return 20 * 45;
    }
}
