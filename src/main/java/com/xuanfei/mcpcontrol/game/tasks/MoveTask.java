package com.xuanfei.mcpcontrol.game.tasks;

import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.game.ActionTask;
import com.xuanfei.mcpcontrol.game.PathFollower;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Walks the player to a goal position using a precomputed path. */
public final class MoveTask implements ActionTask {

    private final List<BlockPos> path;
    private final Vec3 goal;
    private final double tolerance;
    private final double speed;
    private final boolean sprint;
    private final boolean announce;
    private final int timeoutTicks;

    private PathFollower follower;

    public MoveTask(List<BlockPos> path, Vec3 goal, double tolerance, double speed, boolean sprint,
                    boolean announce, int timeoutSeconds) {
        this.path = path;
        this.goal = goal;
        this.tolerance = tolerance;
        this.speed = speed;
        this.sprint = sprint;
        this.announce = announce;
        this.timeoutTicks = Math.max(20, timeoutSeconds * 20);
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        return "moving to " + JsonUtil.formatVec(goal);
    }

    @Override
    public void begin(ServerLevel level, ServerPlayer player) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Path is empty");
        }
        this.follower = new PathFollower(path, speed, sprint);
        player.setSprinting(sprint);
        if (announce) {
            player.displayClientMessage(Component.literal("AI: moving to " + JsonUtil.formatVec(goal)), true);
        }
    }

    @Override
    public Step tick(ServerLevel level, ServerPlayer player) {
        PathFollower.Progress progress = follower.tick(level, player);
        if (progress == PathFollower.Progress.STUCK) {
            return Step.failure("Movement got stuck near " + JsonUtil.formatVec(player.position()));
        }
        if (progress != PathFollower.Progress.ARRIVED) {
            return Step.running();
        }

        double distance = player.position().distanceTo(goal);
        JsonObject data = new JsonObject();
        data.add("final_position", JsonUtil.vec(player.position()));
        data.addProperty("distance_to_goal", JsonUtil.round(distance));
        data.addProperty("path_nodes", path.size());
        data.addProperty("sprinting", sprint);

        if (distance <= tolerance + 0.75) {
            if (announce) {
                player.displayClientMessage(Component.literal("AI: arrived at " + JsonUtil.formatVec(player.position())), true);
            }
            return Step.success("Arrived at " + JsonUtil.formatVec(player.position()) + " (" + String.format("%.2f", distance) + " blocks from goal).", data);
        }
        return Step.failure("Path ended " + String.format("%.2f", distance) + " blocks away from the goal. The destination may be blocked; try a nearby position.");
    }

    @Override
    public void cancel(ServerLevel level, ServerPlayer player) {
        if (follower != null) {
            follower.stop(player);
        } else {
            player.setSprinting(false);
        }
    }

    @Override
    public int timeoutTicks() {
        return timeoutTicks;
    }
}
