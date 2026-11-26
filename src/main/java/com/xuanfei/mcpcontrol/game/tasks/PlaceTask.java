package com.xuanfei.mcpcontrol.game.tasks;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.game.PathFinder;
import com.xuanfei.mcpcontrol.game.PathFollower;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Places one block item against a sturdy neighbour. */
public final class PlaceTask implements ActionTask {

    private enum Phase {
        APPROACH,
        PLACE
    }

    private final BlockPos target;
    private final String wantedItem;
    private final boolean announce;
    private final int maxPathNodes;

    private Phase phase = Phase.PLACE;
    private PathFollower follower;
    private List<BlockPos> path = List.of();
    private Direction face;
    private BlockPos support;
    private Vec3 hitVec;
    private String placedItem = "";
    private String expectedBlock = "";

    public PlaceTask(BlockPos target, String wantedItem, boolean announce) {
        this(target, wantedItem, announce, 12000);
    }

    public PlaceTask(BlockPos target, String wantedItem, boolean announce, int maxPathNodes) {
        this.target = target;
        this.wantedItem = wantedItem == null ? "" : wantedItem.trim();
        this.announce = announce;
        this.maxPathNodes = Math.max(1000, maxPathNodes);
    }

    @Override
    public String name() {
        return "place";
    }

    @Override
    public String describe() {
        return "placing " + wantedItem + " at " + JsonUtil.formatPos(target);
    }

    @Override
    public void begin(ServerLevel level, ServerPlayer player) {
        BlockState targetState = level.getBlockState(target);
        if (!targetState.isAir() && !targetState.canBeReplaced()) {
            throw new IllegalArgumentException("Target block " + JsonUtil.formatPos(target) + " is occupied by "
                    + BuiltInRegistries.BLOCK.getKey(targetState.getBlock()));
        }

        ItemStack stack = findItem(player);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            throw new IllegalArgumentException("No matching block item in the inventory"
                    + (wantedItem.isEmpty() ? "" : " (wanted " + wantedItem + ")"));
        }
        this.placedItem = JsonUtil.itemId(stack);
        this.expectedBlock = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();

        Direction bestFace = null;
        BlockPos bestSupport = null;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = target.relative(direction);
            BlockState neighbourState = level.getBlockState(neighbour);
            if (neighbourState.isFaceSturdy(level, neighbour, direction.getOpposite())) {
                bestFace = direction;
                bestSupport = neighbour;
                break;
            }
        }
        if (bestFace == null) {
            throw new IllegalArgumentException("No sturdy neighbour to place against at " + JsonUtil.formatPos(target));
        }
        this.face = bestFace;
        this.support = bestSupport;
        this.hitVec = Vec3.atCenterOf(bestSupport).add(
                bestFace.getStepX() * 0.5, bestFace.getStepY() * 0.5, bestFace.getStepZ() * 0.5);

        if (player.getEyePosition().distanceTo(hitVec) > 3.9) {
            var result = PathFinder.find(level, player.blockPosition(), hitVec,
                    pos -> Vec3.atCenterOf(pos).distanceToSqr(hitVec) <= 3.2 * 3.2,
                     maxPathNodes, 48);
            this.path = result.path();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("The placement spot is too far away and no path was found");
            }
            if (path.size() > 1) {
                this.phase = Phase.APPROACH;
                this.follower = new PathFollower(path, 0.22, false);
            }
        }
        if (announce) {
            player.displayClientMessage(Component.literal("AI: placing " + placedItem), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        if (phase == Phase.APPROACH) {
            PathFollower.Progress progress = follower.tick(level, player);
            if (progress == PathFollower.Progress.STUCK) {
                return Step.failure("Could not reach the placement spot");
            }
            if (progress == PathFollower.Progress.ARRIVED) {
                phase = Phase.PLACE;
            }
            return Step.running();
        }

        if (player.getEyePosition().distanceTo(hitVec) > 4.8) {
            return Step.failure("The player is too far from the placement spot; move closer first");
        }
        ItemStack stack = findItem(player);
        if (stack.isEmpty()) {
            return Step.failure("The block item disappeared from the inventory");
        }
        selectSlot(player, stack);
        stack = player.getInventory().getSelected();

        player.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.setShiftKeyDown(true);
        InteractionResult result;
        try {
            result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitVec, face, support, false));
        } finally {
            player.setShiftKeyDown(false);
        }

        BlockState placedState = level.getBlockState(target);
        boolean changed = !placedState.isAir();
        JsonObject data = new JsonObject();
        data.add("position", JsonUtil.blockPos(target));
        data.addProperty("item", placedItem);
        data.addProperty("expected_block", expectedBlock);
        data.addProperty("result", result.name());
        data.add("block", JsonUtil.obj("id", BuiltInRegistries.BLOCK.getKey(placedState.getBlock()).toString()));

        if (changed || result.consumesAction()) {
            if (announce) {
                player.displayClientMessage(Component.literal("AI: placed block at " + JsonUtil.formatPos(target)), true);
            }
            return Step.success("Placed " + expectedBlock + " at " + JsonUtil.formatPos(target) + ".", data);
        }
        return Step.failure("Placement failed (result " + result.name() + "). Check that the target is replaceable and the support face is solid.");
    }

    private ItemStack findItem(ServerPlayer player) {
        var inventory = player.getInventory();
        if (!wantedItem.isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(wantedItem.contains(":") ? wantedItem : "minecraft:" + wantedItem);
            if (id == null) {
                return ItemStack.EMPTY;
            }
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }
        ItemStack selected = inventory.getSelected();
        if (!selected.isEmpty() && selected.getItem() instanceof BlockItem) {
            return selected;
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private void selectSlot(ServerPlayer player, ItemStack stack) {
        var inventory = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack candidate = inventory.getItem(i);
            if (!candidate.isEmpty() && candidate.getItem() == stack.getItem()) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        if (slot >= 9) {
            ItemStack hotbar = inventory.getItem(inventory.selected);
            inventory.setItem(inventory.selected, inventory.getItem(slot));
            inventory.setItem(slot, hotbar);
            player.inventoryMenu.broadcastChanges();
            slot = inventory.selected;
        } else if (slot != inventory.selected) {
            inventory.selected = slot;
            player.connection.send(new ClientboundSetCarriedItemPacket(slot));
        }
    }

    @Override
    public void cancel(ServerLevel level, ServerPlayer player) {
        player.setShiftKeyDown(false);
        player.setSprinting(false);
    }

    @Override
    public int timeoutTicks() {
        return 20 * 30;
    }
}
