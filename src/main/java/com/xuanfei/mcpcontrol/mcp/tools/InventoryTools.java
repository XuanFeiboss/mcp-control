package com.xuanfei.mcpcontrol.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Inventory, hotbar and container manipulation tools. */
public final class InventoryTools {

    private InventoryTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(new BaseTool("find_item", "Find item",
                "Counts matching items across the whole inventory and lists every slot holding them. "
                        + "Omit 'item' to get a summary of all carried items.",
                findSchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    String wanted = normalizeItem(JsonUtil.optionalString(copy, "item", ""));
                    Inventory inventory = player.getInventory();
                    JsonArray slots = new JsonArray();
                    Map<String, Integer> totals = new LinkedHashMap<>();
                    for (int slot = 0; slot < 36; slot++) {
                        ItemStack stack = inventory.getItem(slot);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        String id = JsonUtil.itemId(stack);
                        totals.merge(id, stack.getCount(), Integer::sum);
                        if (wanted.isEmpty() || id.equals(wanted)) {
                            slots.add(slotEntry(slot, slot < 9 ? "hotbar" : "main", stack));
                        }
                    }
                    for (int index = 0; index < inventory.armor.size(); index++) {
                        ItemStack stack = inventory.armor.get(index);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        String id = JsonUtil.itemId(stack);
                        totals.merge(id, stack.getCount(), Integer::sum);
                        if (wanted.isEmpty() || id.equals(wanted)) {
                            slots.add(slotEntry(36 + index, "armor", stack));
                        }
                    }
                    ItemStack offhand = inventory.offhand.get(0);
                    if (!offhand.isEmpty()) {
                        String id = JsonUtil.itemId(offhand);
                        totals.merge(id, offhand.getCount(), Integer::sum);
                        if (wanted.isEmpty() || id.equals(wanted)) {
                            slots.add(slotEntry(40, "offhand", offhand));
                        }
                    }

                    JsonObject data = new JsonObject();
                    data.addProperty("item", wanted);
                    data.addProperty("total", wanted.isEmpty() ? 0 : totals.getOrDefault(wanted, 0));
                    data.add("slots", slots);
                    if (wanted.isEmpty()) {
                        JsonObject summary = new JsonObject();
                        totals.forEach(summary::addProperty);
                        data.add("totals", summary);
                    }
                    return ToolResult.ok(wanted.isEmpty() ? "Inventory summary" : "Found " + totals.getOrDefault(wanted, 0) + " " + wanted, data);
                });
            }
        });

        registry.register(new BaseTool("drop_item", "Drop item",
                "Drops items from a slot (or the selected hotbar slot) into the world. "
                        + "Use count=all or 'all': true to drop the whole stack.",
                dropSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    Inventory inventory = player.getInventory();
                    int slot = JsonUtil.optionalInt(copy, "slot", -1, -1, 40);
                    if (slot == -1) {
                        slot = inventory.selected;
                    }
                    ItemStack current = stackAt(inventory, slot);
                    if (current.isEmpty()) {
                        return ToolResult.error("Slot " + slot + " is empty.");
                    }
                    int available = current.getCount();
                    int count = JsonUtil.optionalBoolean(copy, "all", false)
                            ? available
                            : JsonUtil.optionalInt(copy, "count", 1, 1, 64);
                    if (count > available) {
                        return ToolResult.error("Only " + available + " items in slot " + slot + ".");
                    }
                    ItemStack removed = removeAt(inventory, slot, count);
                    player.drop(removed, false);
                    JsonObject data = new JsonObject();
                    data.addProperty("slot", slot);
                    data.add("dropped", JsonUtil.item(removed));
                    data.addProperty("position", JsonUtil.formatVec(player.position()));
                    return ToolResult.ok("Dropped " + removed.getCount() + " " + JsonUtil.itemId(removed), data);
                });
            }
        });

        registry.register(new BaseTool("select_hotbar", "Select hotbar slot",
                "Selects a hotbar slot (0-8), making its item the held item.",
                ToolSchemas.schema(singleInt("slot", "Hotbar slot, 0-8", 0, 8), "slot"), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    int slot = JsonUtil.requiredInt(copy, "slot");
                    if (slot < 0 || slot > 8) {
                        throw new ToolError("Hotbar slot must be 0-8");
                    }
                    player.getInventory().selected = slot;
                    player.connection.send(new ClientboundSetCarriedItemPacket(slot));
                    JsonObject data = new JsonObject();
                    data.addProperty("selected", slot);
                    data.add("item", JsonUtil.item(player.getInventory().getSelected()));
                    return ToolResult.ok("Selected hotbar slot " + slot, data);
                });
            }
        });

        registry.register(new BaseTool("equip_item", "Equip armor",
                "Equips one armor or elytra item from the inventory into its proper equipment slot, "
                        + "swapping out any item already there.",
                ToolSchemas.schema(singleString("item", "Item id, e.g. minecraft:diamond_helmet"), "item"), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    String wanted = normalizeItem(JsonUtil.requiredString(copy, "item"));
                    Inventory inventory = player.getInventory();
                    for (int slot = 0; slot < 36; slot++) {
                        ItemStack stack = inventory.getItem(slot);
                        if (stack.isEmpty() || !JsonUtil.itemId(stack).equals(wanted)) {
                            continue;
                        }
                        if (!(stack.getItem() instanceof Equipable equipable)) {
                            return ToolResult.error("Item " + wanted + " is not equippable (armor or elytra).");
                        }
                        EquipmentSlot equipmentSlot = equipable.getEquipmentSlot();
                        ItemStack one = stack.copy();
                        one.setCount(1);
                        stack.shrink(1);
                        inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                        ItemStack previous = player.getItemBySlot(equipmentSlot);
                        player.setItemSlot(equipmentSlot, one);
                        if (!previous.isEmpty()) {
                            if (!inventory.add(previous)) {
                                player.drop(previous, false);
                            }
                        }
                        player.inventoryMenu.broadcastChanges();
                        JsonObject data = new JsonObject();
                        data.addProperty("equipped", wanted);
                        data.addProperty("slot", equipmentSlot.getName());
                        data.add("previous", JsonUtil.item(previous));
                        return ToolResult.ok("Equipped " + wanted + " in " + equipmentSlot.getName(), data);
                    }
                    return ToolResult.error("No " + wanted + " found in the inventory.");
                });
            }
        });

        registry.register(new BaseTool("move_item", "Move item",
                "Moves or swaps items between inventory slots. Slots: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand.",
                moveItemSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    Inventory inventory = player.getInventory();
                    int from = JsonUtil.requiredInt(copy, "from_slot");
                    int to = JsonUtil.requiredInt(copy, "to_slot");
                    validateSlot(from);
                    validateSlot(to);
                    if (from == to) {
                        return ToolResult.ok("Source and destination are the same slot.");
                    }
                    ItemStack source = stackAt(inventory, from).copy();
                    ItemStack destination = stackAt(inventory, to).copy();
                    setAt(inventory, from, destination);
                    setAt(inventory, to, source);
                    player.inventoryMenu.broadcastChanges();
                    JsonObject data = new JsonObject();
                    data.addProperty("from", from);
                    data.addProperty("to", to);
                    data.add("from_item", JsonUtil.item(destination));
                    data.add("to_item", JsonUtil.item(source));
                    return ToolResult.ok("Moved slot " + from + " to " + to, data);
                });
            }
        });

        registry.register(new BaseTool("access_container", "Inspect container",
                "Lists the contents of a container block (chest, barrel, furnace, hopper, etc.) within reach.",
                positionSchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    BlockPos pos = JsonUtil.requiredBlockPos(copy);
                    checkReach(player, pos);
                    Container container = containerAt(level, pos);
                    JsonArray items = new JsonArray();
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        JsonObject entry = slotEntry(slot, "container", stack);
                        items.add(entry);
                    }
                    JsonObject data = new JsonObject();
                    data.add("position", JsonUtil.blockPos(pos));
                    data.addProperty("slots", container.getContainerSize());
                    data.add("items", items);
                    return ToolResult.ok("Container has " + items.size() + " occupied slots", data);
                });
            }
        });

        registry.register(new BaseTool("transfer_items", "Transfer items",
                "Takes items from a container into the player inventory, or puts items from the inventory into a container. "
                        + "Container must be within reach.",
                transferSchema(), false, true) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    ServerLevel level = player.serverLevel();
                    BlockPos pos = JsonUtil.requiredBlockPos(copy);
                    checkReach(player, pos);
                    Container container = containerAt(level, pos);
                    String direction = JsonUtil.optionalString(copy, "direction", "take").toLowerCase();
                    String filter = normalizeItem(JsonUtil.optionalString(copy, "item", ""));
                    int limit = JsonUtil.optionalInt(copy, "count", 64, 1, 64);
                    int moved = 0;

                    if (direction.equals("take")) {
                        for (int slot = 0; slot < container.getContainerSize() && moved < limit; slot++) {
                            ItemStack stack = container.getItem(slot);
                            if (stack.isEmpty() || (!filter.isEmpty() && !JsonUtil.itemId(stack).equals(filter))) {
                                continue;
                            }
                            int amount = Math.min(limit - moved, stack.getCount());
                            ItemStack taken = container.removeItem(slot, amount);
                            moved += taken.getCount();
                            if (!player.getInventory().add(taken) && !taken.isEmpty()) {
                                player.drop(taken, false);
                            }
                        }
                    } else if (direction.equals("put")) {
                        for (int slot = 0; slot < 36 && moved < limit; slot++) {
                            ItemStack stack = player.getInventory().getItem(slot);
                            if (stack.isEmpty() || (!filter.isEmpty() && !JsonUtil.itemId(stack).equals(filter))) {
                                continue;
                            }
                            int amount = Math.min(limit - moved, stack.getCount());
                            ItemStack moving = stack.copy();
                            moving.setCount(amount);
                            stack.shrink(amount);
                            player.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                            int movedNow = insertIntoContainer(container, moving);
                            moved += movedNow;
                            if (movedNow < moving.getCount()) {
                                ItemStack remainder = moving.copyWithCount(moving.getCount() - movedNow);
                                if (!player.getInventory().add(remainder)) {
                                    player.drop(remainder, false);
                                }
                            }
                        }
                    } else {
                        throw new ToolError("direction must be 'take' or 'put'");
                    }
                    player.inventoryMenu.broadcastChanges();
                    JsonObject data = new JsonObject();
                    data.add("position", JsonUtil.blockPos(pos));
                    data.addProperty("direction", direction);
                    data.addProperty("moved", moved);
                    data.addProperty("filter", filter);
                    return ToolResult.ok("Transfer complete (" + moved + " items)", data);
                });
            }
        });
    }

    private static int insertIntoContainer(Container container, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                int amount = Math.min(remaining, stack.getMaxStackSize());
                ItemStack placed = stack.copy();
                placed.setCount(amount);
                container.setItem(slot, placed);
                remaining -= amount;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                if (space > 0) {
                    existing.grow(space);
                    container.setItem(slot, existing);
                    remaining -= space;
                }
            }
        }
        return stack.getCount() - remaining;
    }

    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            throw new ToolError("Block at " + JsonUtil.formatPos(pos) + " is not a container.");
        }
        return container;
    }

    private static void checkReach(ServerPlayer player, BlockPos pos) {
        if (player.getEyePosition().distanceTo(Vec3Center(pos)) > 4.8) {
            throw new ToolError("Container is out of reach; move closer first.");
        }
    }

    private static net.minecraft.world.phys.Vec3 Vec3Center(BlockPos pos) {
        return net.minecraft.world.phys.Vec3.atCenterOf(pos);
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot > 40) {
            throw new ToolError("Slot must be 0-40 (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)");
        }
    }

    private static ItemStack stackAt(Inventory inventory, int slot) {
        if (slot < 36) {
            return inventory.getItem(slot);
        }
        if (slot < 40) {
            return inventory.armor.get(slot - 36);
        }
        return inventory.offhand.get(0);
    }

    private static void setAt(Inventory inventory, int slot, ItemStack stack) {
        if (slot < 36) {
            inventory.setItem(slot, stack);
        } else if (slot < 40) {
            inventory.armor.set(slot - 36, stack);
        } else {
            inventory.offhand.set(0, stack);
        }
    }

    private static ItemStack removeAt(Inventory inventory, int slot, int count) {
        if (slot < 36) {
            return inventory.removeItem(slot, count);
        }
        ItemStack current = stackAt(inventory, slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int amount = Math.min(count, current.getCount());
        ItemStack removed = current.copy();
        removed.setCount(amount);
        current.shrink(amount);
        setAt(inventory, slot, current.isEmpty() ? ItemStack.EMPTY : current);
        return removed;
    }

    private static JsonObject slotEntry(int slot, String area, ItemStack stack) {
        JsonObject entry = new JsonObject();
        entry.addProperty("slot", slot);
        entry.addProperty("area", area);
        entry.add("item", JsonUtil.item(stack));
        return entry;
    }

    private static String normalizeItem(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static JsonObject positionSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        return ToolSchemas.schema(properties, "x", "y", "z");
    }

    private static JsonObject singleInt(String name, String description, int min, int max) {
        JsonObject properties = new JsonObject();
        properties.add(name, ToolSchemas.integer(description, min, max));
        return properties;
    }

    private static JsonObject singleString(String name, String description) {
        JsonObject properties = new JsonObject();
        properties.add(name, ToolSchemas.string(description));
        return properties;
    }

    private static JsonObject findSchema() {
        JsonObject properties = new JsonObject();
        properties.add("item", ToolSchemas.string("Optional item id to count, e.g. minecraft:oak_log"));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject dropSchema() {
        JsonObject properties = new JsonObject();
        properties.add("slot", ToolSchemas.integer("Slot to drop from; -1 means the selected hotbar slot", -1, 40));
        properties.add("count", ToolSchemas.integer("How many to drop", 1, 64));
        properties.add("all", ToolSchemas.bool("Drop the whole stack"));
        return ToolSchemas.schema(properties);
    }

    private static JsonObject moveItemSchema() {
        JsonObject properties = new JsonObject();
        properties.add("from_slot", ToolSchemas.integer("Source slot", 0, 40));
        properties.add("to_slot", ToolSchemas.integer("Destination slot", 0, 40));
        return ToolSchemas.schema(properties, "from_slot", "to_slot");
    }

    private static JsonObject transferSchema() {
        JsonObject properties = new JsonObject();
        ToolSchemas.integerPosition(properties);
        properties.add("direction", ToolSchemas.enumeration("take = container to player, put = player to container", "take", "put"));
        properties.add("item", ToolSchemas.string("Optional item id filter"));
        properties.add("count", ToolSchemas.integer("Maximum items to transfer", 1, 64));
        return ToolSchemas.schema(properties, "x", "y", "z", "direction");
    }
}
