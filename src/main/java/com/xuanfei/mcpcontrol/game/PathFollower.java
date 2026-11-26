package com.xuanfei.mcpcontrol.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Moves a player along a precomputed block path by small server-side teleports. */
public final class PathFollower {

    public enum Progress {
        MOVING,
        ARRIVED,
        STUCK
    }

    private final List<BlockPos> path;
    private final double speed;
    private final boolean sprint;

    private int index;
    private int ticks;
    private Vec3 lastCheckpoint;

    public PathFollower(List<BlockPos> path, double speed, boolean sprint) {
        this.path = path;
        this.speed = Math.max(0.05, speed);
        this.sprint = sprint;
        this.index = 0;
    }

    public int index() {
        return index;
    }

    public int pathSize() {
        return path.size();
    }

    public Progress tick(ServerLevel level, ServerPlayer player) {
        if (index >= path.size()) {
            return Progress.ARRIVED;
        }
        ticks++;
        if (ticks % 20 == 0) {
            if (lastCheckpoint != null && player.position().distanceToSqr(lastCheckpoint) < 0.09) {
                return Progress.STUCK;
            }
            lastCheckpoint = player.position();
        }

        BlockPos node = path.get(index);
        Vec3 center = Vec3.atCenterOf(node);
        double dx = center.x - player.getX();
        double dz = center.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = node.getY() - player.getY();

        if (horizontal < 0.5 && Math.abs(dy) < 1.1) {
            index++;
            return index >= path.size() ? Progress.ARRIVED : Progress.MOVING;
        }

        double actualSpeed = speed;
        if (player.isInWater()) {
            actualSpeed *= 0.55;
        }
        double step = Math.min(actualSpeed, horizontal);
        double newX = player.getX();
        double newZ = player.getZ();
        if (horizontal > 1.0E-4) {
            newX += dx / horizontal * step;
            newZ += dz / horizontal * step;
        }
        double newY = player.getY();
        if (dy > 0.05) {
            newY += Math.min(0.65, dy);
        } else if (dy < -0.05) {
            newY += Math.max(-1.2, dy);
        }

        float yaw = horizontal > 1.0E-4
                ? (float) Math.toDegrees(Math.atan2(-dx, dz))
                : player.getYRot();
        player.setSprinting(sprint && horizontal > 1.2);
        player.teleportTo(level, newX, newY, newZ, yaw, player.getXRot());
        return Progress.MOVING;
    }

    public void stop(ServerPlayer player) {
        player.setSprinting(false);
    }
}
