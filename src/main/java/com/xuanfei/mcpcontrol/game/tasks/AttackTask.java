package com.xuanfei.mcpcontrol.game.tasks;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.game.PathFinder;
import com.xuanfei.mcpcontrol.game.PathFollower;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Attacks one entity (uuid or nearest match) with charged hits. */
public final class AttackTask implements ActionTask {

    private final UUID targetUuid;
    private final String typeFilter;
    private final double searchRadius;
    private final int requestedHits;
    private final boolean announce;

    private UUID resolvedUuid;
    private String targetDescription = "";
    private int hitsLeft;
    private int cooldown;
    private int repathCooldown;
    private PathFollower follower;
    private List<BlockPos> path = List.of();
    private double totalDamage;
    private boolean everHit;

    public AttackTask(UUID targetUuid, String typeFilter, double searchRadius, int hits, boolean announce) {
        this.targetUuid = targetUuid;
        this.typeFilter = typeFilter == null ? "" : typeFilter.trim();
        this.searchRadius = searchRadius;
        this.requestedHits = Math.max(1, hits);
        this.announce = announce;
    }

    @Override
    public String name() {
        return "attack";
    }

    @Override
    public String describe() {
        return "attacking " + (targetDescription.isEmpty() ? "target" : targetDescription);
    }

    @Override
    public void begin(ServerLevel level, ServerPlayer player) {
        Entity target = resolveTarget(level, player);
        if (target == null) {
            throw new IllegalArgumentException(targetUuid != null
                    ? "Entity " + targetUuid + " was not found nearby"
                    : "No matching entity within " + String.format("%.1f", searchRadius) + " blocks");
        }
        this.resolvedUuid = target.getUUID();
        this.targetDescription = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()) + " (" + target.getUUID() + ")";
        this.hitsLeft = requestedHits;
        if (announce) {
            player.displayClientMessage(Component.literal("AI: attacking " + targetDescription), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        Entity entity = level.getEntity(resolvedUuid);
        if (entity == null || !entity.isAlive()) {
            JsonObject data = data(true);
            data.addProperty("damage_dealt", JsonUtil.round(totalDamage));
            return Step.success("Target is no longer alive.", data);
        }
        if (entity.isRemoved()) {
            return Step.success("Target disappeared.", data(true));
        }

        double distance = player.distanceTo(entity);
        if (distance > 3.0) {
            if (repathCooldown-- <= 0 || path.isEmpty()) {
                repathCooldown = 10;
                var result = PathFinder.find(level, player.blockPosition(), entity.position(),
                        pos -> Vec3.atCenterOf(pos).distanceToSqr(entity.position()) <= 2.0 * 2.0,
                        12000, 64);
                path = result.path();
                if (path.isEmpty()) {
                    return Step.failure("Could not find a path to the target");
                }
                follower = new PathFollower(path, 0.30, true);
            }
            PathFollower.Progress progress = follower.tick(level, player);
            if (progress == PathFollower.Progress.STUCK) {
                return Step.failure("Cannot reach the target; movement got stuck");
            }
            return Step.running();
        }

        if (cooldown > 0) {
            cooldown--;
            return Step.running();
        }
        float before = entity instanceof LivingEntity living ? living.getHealth() : 0.0F;
        player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.attack(entity);
        player.swing(InteractionHand.MAIN_HAND, true);
        player.resetAttackStrengthTicker();
        everHit = true;
        if (entity instanceof LivingEntity living) {
            totalDamage += Math.max(0.0, before - living.getHealth());
        }
        hitsLeft--;
        cooldown = 12;

        if (hitsLeft <= 0 || !entity.isAlive()) {
            JsonObject data = data(!entity.isAlive());
            data.addProperty("damage_dealt", JsonUtil.round(totalDamage));
            if (announce) {
                player.displayClientMessage(Component.literal("AI: attacked " + targetDescription), true);
            }
            return Step.success("Attacked " + targetDescription + " (" + String.format("%.1f", totalDamage) + " damage).", data);
        }
        return Step.running();
    }

    private JsonObject data(boolean killed) {
        JsonObject data = new JsonObject();
        data.addProperty("target", targetDescription);
        data.addProperty("killed", killed);
        data.addProperty("hits_landed", requestedHits - hitsLeft);
        data.addProperty("damage_dealt", JsonUtil.round(totalDamage));
        return data;
    }

    private Entity resolveTarget(ServerLevel level, ServerPlayer player) {
        if (targetUuid != null) {
            return level.getEntity(targetUuid);
        }
        String filter = typeFilter;
        return level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(searchRadius),
                        entity -> entity != player && entity.isAlive() && matchesType(entity, filter))
                .stream()
                .min(Comparator.comparingDouble(entity -> player.distanceToSqr(entity)))
                .orElse(null);
    }

    private boolean matchesType(Entity entity, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return id.equals(filter) || id.equals("minecraft:" + filter) || id.substring(id.indexOf(':') + 1).equals(filter);
    }

    @Override
    public void cancel(ServerLevel level, ServerPlayer player) {
        player.setSprinting(false);
    }

    @Override
    public int timeoutTicks() {
        return 20 * 60;
    }
}
