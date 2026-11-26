package com.xuanfei.mcpcontrol.game.tasks;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** Right-clicks / starts using an item and waits for its use duration to finish. */
public final class UseItemTask implements ActionTask {

    private final InteractionHand hand;
    private final boolean announce;

    private String itemId = "";
    private int countBefore;
    private boolean finished;
    private String finishMessage = "";
    private String resultName = "";

    public UseItemTask(InteractionHand hand, boolean announce) {
        this.hand = hand;
        this.announce = announce;
    }

    @Override
    public String name() {
        return "use_item";
    }

    @Override
    public String describe() {
        return "using " + itemId + " (" + hand.name().toLowerCase() + ")";
    }

    @Override
    public void begin(ServerLevel level, ServerPlayer player) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("The " + hand.name().toLowerCase() + " hand is empty");
        }
        if (player.isUsingItem()) {
            player.stopUsingItem();
        }
        this.itemId = JsonUtil.itemId(stack);
        this.countBefore = stack.getCount();
        InteractionResult result = player.gameMode.useItem(player, level, stack, hand);
        this.resultName = result.name();
        if (!result.consumesAction()) {
            this.finished = true;
            this.finishMessage = "The item could not be used here (result " + result.name() + ")";
        } else if (announce) {
            player.displayClientMessage(Component.literal("AI: using " + itemId), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        if (finished) {
            return finish(player);
        }
        if (player.isUsingItem()) {
            return Step.running();
        }
        return finish(player);
    }

    private Step finish(ServerPlayer player) {
        ItemStack stack = player.getItemInHand(hand);
        JsonObject data = new JsonObject();
        data.addProperty("item", itemId);
        data.addProperty("hand", hand.name().toLowerCase());
        data.addProperty("count_before", countBefore);
        data.addProperty("count_after", stack.getCount());
        data.addProperty("result", resultName);
        if (finished && resultName.equals("FAIL")) {
            return Step.failure(finishMessage, data);
        }
        String message = stack.isEmpty()
                ? "Used and consumed " + itemId + "."
                : "Used " + itemId + " (" + stack.getCount() + " left).";
        return Step.success(message, data);
    }

    @Override
    public void cancel(ServerLevel level, ServerPlayer player) {
        if (player.isUsingItem()) {
            player.stopUsingItem();
        }
    }

    @Override
    public int timeoutTicks() {
        return 20 * 30;
    }
}
