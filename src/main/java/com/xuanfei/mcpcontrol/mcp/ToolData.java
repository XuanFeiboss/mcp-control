package com.xuanfei.mcpcontrol.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;

/** Shared JSON views of game state used by several read-only tools. */
public final class ToolData {

    private ToolData() {
    }

    public static JsonObject playerState(ServerPlayer player, MinecraftServer server) {
        JsonObject object = new JsonObject();
        object.addProperty("name", player.getScoreboardName());
        object.addProperty("uuid", player.getUUID().toString());
        object.addProperty("dimension", player.serverLevel().dimension().location().toString());
        object.add("position", JsonUtil.vec(player.position()));
        object.add("block_position", JsonUtil.blockPos(player.blockPosition()));
        object.add("eye_position", JsonUtil.vec(player.getEyePosition()));
        object.addProperty("yaw", JsonUtil.round(player.getYRot()));
        object.addProperty("pitch", JsonUtil.round(player.getXRot()));
        object.addProperty("health", JsonUtil.round(player.getHealth()));
        object.addProperty("max_health", JsonUtil.round(player.getMaxHealth()));
        object.addProperty("absorption", JsonUtil.round(player.getAbsorptionAmount()));
        object.addProperty("food", player.getFoodData().getFoodLevel());
        object.addProperty("saturation", JsonUtil.round(player.getFoodData().getSaturationLevel()));
        object.addProperty("air", player.getAirSupply());
        object.addProperty("max_air", player.getMaxAirSupply());
        object.addProperty("xp_level", player.experienceLevel);
        object.addProperty("xp_progress", JsonUtil.round(player.experienceProgress));
        object.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
        object.addProperty("on_ground", player.onGround());
        object.addProperty("sprinting", player.isSprinting());
        object.addProperty("sneaking", player.isShiftKeyDown());
        object.addProperty("swimming", player.isSwimming());
        object.addProperty("using_item", player.isUsingItem());
        object.addProperty("selected_hotbar_slot", player.getInventory().selected);
        object.add("held_item", JsonUtil.item(player.getInventory().getSelected()));
        object.add("offhand_item", JsonUtil.item(player.getOffhandItem()));
        object.addProperty("op_level", server.getProfilePermissions(player.getGameProfile()));
        object.addProperty("game_time", player.serverLevel().getGameTime());
        object.addProperty("tick", server.getTickCount());

        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("effect", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
            entry.addProperty("amplifier", effect.getAmplifier());
            entry.addProperty("duration_ticks", effect.getDuration());
            entry.addProperty("ambient", effect.isAmbient());
            effects.add(entry);
        }
        object.add("effects", effects);
        return object;
    }

    public static JsonObject inventory(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        JsonObject object = new JsonObject();
        object.addProperty("selected_hotbar_slot", inventory.selected);
        object.add("held_item", JsonUtil.item(inventory.getSelected()));
        object.add("offhand_item", JsonUtil.item(player.getOffhandItem()));

        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            slots.add(slotEntry(slot, slot < 9 ? "hotbar" : "main", stack));
        }
        for (int index = 0; index < inventory.armor.size(); index++) {
            ItemStack stack = inventory.armor.get(index);
            if (!stack.isEmpty()) {
                String area = switch (index) {
                    case 0 -> "armor_feet";
                    case 1 -> "armor_legs";
                    case 2 -> "armor_chest";
                    default -> "armor_head";
                };
                slots.add(slotEntry(36 + index, area, stack));
            }
        }
        ItemStack offhand = inventory.offhand.get(0);
        if (!offhand.isEmpty()) {
            slots.add(slotEntry(40, "offhand", offhand));
        }
        object.add("slots", slots);
        object.addProperty("used_slots", slots.size());
        return object;
    }

    private static JsonObject slotEntry(int slot, String area, ItemStack stack) {
        JsonObject entry = new JsonObject();
        entry.addProperty("slot", slot);
        entry.addProperty("area", area);
        entry.add("item", JsonUtil.item(stack));
        return entry;
    }

    public static JsonObject blockInfo(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        JsonObject object = new JsonObject();
        object.add("position", JsonUtil.blockPos(pos));
        object.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        object.addProperty("air", state.isAir());
        object.addProperty("replaceable", state.canBeReplaced());
        object.addProperty("solid", state.isSolid());
        object.addProperty("hardness", JsonUtil.round(state.getDestroySpeed(level, pos)));
        object.addProperty("collision", !state.getCollisionShape(level, pos).isEmpty());
        object.addProperty("fluid", state.getFluidState().isEmpty()
                ? ""
                : BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());

        JsonObject properties = new JsonObject();
        state.getValues().forEach((property, value) -> properties.addProperty(property.getName(), value.toString()));
        if (!properties.isEmpty()) {
            object.add("properties", properties);
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            object.addProperty("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
        }
        return object;
    }

    public static JsonObject entityInfo(Entity entity, ServerPlayer player) {
        JsonObject object = new JsonObject();
        object.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        object.addProperty("uuid", entity.getUUID().toString());
        object.addProperty("name", entity.getName().getString());
        object.add("position", JsonUtil.vec(entity.position()));
        object.addProperty("distance", JsonUtil.round(Math.sqrt(entity.distanceToSqr(player))));
        object.addProperty("alive", entity.isAlive());
        if (entity instanceof LivingEntity living) {
            object.addProperty("health", JsonUtil.round(living.getHealth()));
            object.addProperty("max_health", JsonUtil.round(living.getMaxHealth()));
            object.addProperty("armor", JsonUtil.round(living.getArmorValue()));
        }
        if (entity.getControllingPassenger() != null) {
            object.addProperty("vehicle", true);
        }
        return object;
    }

    public static JsonObject worldState(MinecraftServer server, ServerLevel level) {
        JsonObject object = new JsonObject();
        object.addProperty("dimension", level.dimension().location().toString());
        object.addProperty("game_time", level.getGameTime());
        object.addProperty("day_time", level.getDayTime());
        object.addProperty("day", level.getDayTime() / 24000L + 1L);
        object.addProperty("raining", level.isRaining());
        object.addProperty("thundering", level.isThundering());
        object.addProperty("rain_level", JsonUtil.round(level.getRainLevel(1.0F)));
        object.addProperty("thunder_level", JsonUtil.round(level.getThunderLevel(1.0F)));
        object.addProperty("difficulty", server.getWorldData().getDifficulty().name().toLowerCase());
        object.addProperty("hardcore", server.getWorldData().isHardcore());
        object.addProperty("moon_phase", level.getMoonPhase());
        return object;
    }

    public static JsonObject serverInfo(MinecraftServer server) {
        JsonObject object = new JsonObject();
        object.addProperty("name", server.getMotd());
        object.addProperty("mc_version", SharedConstants.getCurrentVersion().getName());
        object.addProperty("server_version", server.getServerVersion());
        object.addProperty("neo_version", NeoForgeVersion.getVersion());
        object.addProperty("singleplayer", server.isSingleplayer());
        object.addProperty("players", server.getPlayerCount());
        object.addProperty("max_players", server.getMaxPlayers());
        object.addProperty("tick", server.getTickCount());
        object.addProperty("average_tick_ms", JsonUtil.round(server.getAverageTickTimeNanos() / 1_000_000.0));
        object.addProperty("level_name", server.getWorldData().getLevelName());
        return object;
    }
}
