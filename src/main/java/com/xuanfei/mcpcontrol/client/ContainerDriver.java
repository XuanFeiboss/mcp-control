package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** Reads and clicks container slots (chest, crafting table, furnace, villager, ...). */
public final class ContainerDriver {

    private final Minecraft minecraft;
    private final ScreenDriver screens;

    public ContainerDriver(Minecraft minecraft, ScreenDriver screens) {
        this.minecraft = minecraft;
        this.screens = screens;
    }

    public boolean inContainer() {
        return minecraft.player != null
                && minecraft.player.containerMenu != null
                && minecraft.player.containerMenu != minecraft.player.inventoryMenu;
    }

    public JsonObject state() {
        JsonObject object = new JsonObject();
        if (minecraft.player == null) {
            object.addProperty("open", false);
            object.addProperty("hint", "No player is loaded yet.");
            return object;
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu == null) {
            object.addProperty("open", false);
            return object;
        }
        boolean container = menu != minecraft.player.inventoryMenu;
        object.addProperty("open", container);
        object.addProperty("container_id", menu.containerId);
        object.addProperty("title", screens.screen() == null ? "" : Ref.text(Ref.callOpt(screens.screen(), "getTitle")));
        object.addProperty("screen", screens.screenClass());
        object.add("carried", JsonUtil.item(menu.getCarried()));

        JsonArray slots = new JsonArray();
        int index = 0;
        for (Slot slot : menu.slots) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", index);
            Object containerSlot = Ref.get(slot, "index", "slot");
            if (containerSlot instanceof Number number) {
                entry.addProperty("container_slot", number.intValue());
            }
            entry.addProperty("x", Ref.getInt(slot, "x"));
            entry.addProperty("y", Ref.getInt(slot, "y"));
            ItemStack stack = slot.getItem();
            entry.add("item", JsonUtil.item(stack));
            entry.addProperty("empty", stack.isEmpty());
            slots.add(entry);
            index++;
        }
        object.add("slots", slots);
        object.addProperty("slot_count", slots.size());

        JsonObject inventory = new JsonObject();
        inventory.addProperty("selected_hotbar_slot", minecraft.player.getInventory().selected);
        inventory.add("held", JsonUtil.item(minecraft.player.getInventory().getSelected()));
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(JsonUtil.item(minecraft.player.getInventory().getItem(i)));
        }
        inventory.add("hotbar", hotbar);
        object.add("player_inventory", inventory);
        return object;
    }

    /** Clicks a menu slot through the vanilla multiplayer game mode (local prediction + packet). */
    public String click(int slot, int button, String clickTypeName) {
        if (minecraft.player == null) {
            throw new IllegalStateException("No player is loaded");
        }
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu == null) {
            throw new IllegalStateException("No container menu is open");
        }
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new IllegalStateException("Slot " + slot + " is out of range (0.." + (menu.slots.size() - 1) + ")");
        }
        ClickType type = parseClickType(clickTypeName);
        Ref.call(minecraft.gameMode, "handleInventoryMouseClick",
                menu.containerId, slot, button, type, minecraft.player);
        return "clicked slot " + slot + " (" + type.name().toLowerCase(Locale.ROOT) + ", button " + button + ")";
    }

    public boolean close() {
        Screen screen = screens.screen();
        if (screen != null) {
            return screens.close();
        }
        if (minecraft.player != null) {
            Object result = Ref.callOpt(minecraft.player, "closeContainer");
            return result != null;
        }
        return false;
    }

    private static ClickType parseClickType(String name) {
        if (name == null || name.isBlank()) {
            return ClickType.PICKUP;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "QUICK_MOVE", "SHIFT", "SHIFT_CLICK" -> ClickType.QUICK_MOVE;
            case "SWAP", "SWAP_HANDS", "F" -> ClickType.SWAP;
            case "THROW", "DROP", "Q" -> ClickType.THROW;
            case "QUICK_CRAFT", "DRAG" -> ClickType.QUICK_CRAFT;
            case "PICKUP_ALL", "DOUBLE_CLICK" -> ClickType.PICKUP_ALL;
            case "CLONE", "CREATIVE" -> ClickType.CLONE;
            default -> ClickType.PICKUP;
        };
    }
}
