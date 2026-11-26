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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Continuously follows an entity, replanning while it moves. */
public final class FollowTask implements ActionTask {

    private final UUID targetUuid;
    private final String typeFilter;
    private final double searchRadius;
    private final double stopDistance;
    private final boolean announce;

    private UUID resolvedUuid;
    private String targetDescription = "";
    private PathFollower follower;
    private List<BlockPos> path = List.of();
    private int repathCooldown;

    public FollowTask(UUID targetUuid, String typeFilter, double searchRadius, double stopDistance, boolean announce) {
        this.targetUuid = targetUuid;
        this.typeFilter = typeFilter == null ? "" : typeFilter.trim();
        this.searchRadius = searchRadius;
        this.stopDistance = Math.max(1.0, stopDistance);
        this.announce = announce;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public String describe() {
        return "following " + (targetDescription.isEmpty() ? "target" : targetDescription);
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
        if (announce) {
            player.displayClientMessage(Component.literal("AI: following " + targetDescription), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        Entity entity = level.getEntity(resolvedUuid);
        if (entity == null || !entity.isAlive()) {
            return Step.success("Target is gone; follow finished.", data("gone"));
        }
        double distance = player.getEyePosition().distanceTo(entity.getEyePosition());
        if (distance <= stopDistance) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            follower = null;
            path = List.of();
            return Step.running();
        }
        if (repathCooldown-- <= 0 || follower == null || path.isEmpty()) {
            repathCooldown = 10;
            var result = PathFinder.find(level, player.blockPosition(), entity.position(),
                    pos -> Vec3.atCenterOf(pos).distanceToSqr(entity.position()) <= stopDistance * stopDistance,
                    12000, 64);
            path = result.path();
            if (path.isEmpty()) {
                return Step.running();
            }
            follower = new PathFollower(path, 0.30, true);
        }
        if (follower != null) {
            PathFollower.Progress progress = follower.tick(level, player);
            if (progress == PathFollower.Progress.STUCK) {
                follower = null;
                path = List.of();
                repathCooldown = 5;
            }
        }
        return Step.running();
    }

    private JsonObject data(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("target", targetDescription);
        data.addProperty("reason", reason);
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
        return 20 * 60 * 30;
    }
}
