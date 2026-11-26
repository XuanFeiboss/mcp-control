package com.xuanfei.mcpcontrol.game;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A* path finder over block positions. It models what a survival player can walk through:
 * two-block head clearance, one-block step up, up to three-block drops, water swimming,
 * ladders/vines and avoidance of fire/lava/cactus/magma.
 */
public final class PathFinder {

    private static final int[][] HORIZONTAL = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private PathFinder() {
    }

    public record Result(List<BlockPos> path, boolean reached, BlockPos closest, int explored) {
    }

    public static Result find(ServerLevel level, BlockPos start, Vec3 goalHint, Predicate<BlockPos> goal,
                              int maxNodes, double maxHorizontalDistance) {
        BlockPos safeStart = findSafeStart(level, start);
        if (safeStart == null) {
            return new Result(List.of(), false, start, 0);
        }

        long startKey = safeStart.asLong();
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

        gScore.put(startKey, 0.0);
        open.add(new Node(startKey, 0.0, heuristic(safeStart, goalHint)));

        BlockPos closest = safeStart;
        double closestScore = heuristic(safeStart, goalHint);
        long endKey = startKey;
        int explored = 0;

        while (!open.isEmpty() && explored < maxNodes) {
            Node node = open.poll();
            if (closed.contains(node.pos)) {
                continue;
            }
            closed.add(node.pos);
            explored++;
            BlockPos pos = BlockPos.of(node.pos);

            double score = heuristic(pos, goalHint);
            if (score < closestScore) {
                closestScore = score;
                closest = pos;
            }
            if (goal.test(pos)) {
                endKey = node.pos;
                return new Result(reconstruct(cameFrom, endKey), true, pos, explored);
            }

            for (Neighbor neighbor : neighbors(level, pos)) {
                long key = neighbor.pos.asLong();
                if (closed.contains(key)) {
                    continue;
                }
                if (maxHorizontalDistance > 0) {
                    double dx = neighbor.pos.getX() - safeStart.getX();
                    double dz = neighbor.pos.getZ() - safeStart.getZ();
                    if (dx * dx + dz * dz > maxHorizontalDistance * maxHorizontalDistance) {
                        continue;
                    }
                }
                double tentative = node.g + neighbor.cost;
                if (tentative < gScore.getOrDefault(key, Double.MAX_VALUE) - 1.0E-6) {
                    gScore.put(key, tentative);
                    cameFrom.put(key, node.pos);
                    open.add(new Node(key, tentative, tentative + heuristic(neighbor.pos, goalHint)));
                }
            }
        }
        return new Result(reconstruct(cameFrom, closest.asLong()), false, closest, explored);
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> cameFrom, long endKey) {
        List<BlockPos> path = new ArrayList<>();
        long current = endKey;
        int guard = 0;
        while (guard++ < 100_000) {
            path.add(BlockPos.of(current));
            Long previous = cameFrom.get(current);
            if (previous == null) {
                break;
            }
            current = previous;
        }
        Collections.reverse(path);
        return path;
    }

    private static double heuristic(BlockPos pos, Vec3 hint) {
        if (hint == null) {
            return 0.0;
        }
        double dx = (pos.getX() + 0.5) - hint.x;
        double dy = pos.getY() - hint.y;
        double dz = (pos.getZ() + 0.5) - hint.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static BlockPos findSafeStart(ServerLevel level, BlockPos origin) {
        if (canStand(level, origin)) {
            return origin;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (canStand(level, candidate)) {
                        double distance = candidate.distSqr(origin);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    public static boolean canStand(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above())) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (!feet.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head)) {
            return false;
        }

        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        boolean solidFloor = !floor.getCollisionShape(level, below).isEmpty();
        if (solidFloor && !isDangerous(floor)) {
            return true;
        }
        if (isWater(feet) || isWater(head)) {
            return true;
        }
        return isClimbable(feet) || isClimbable(head);
    }

    public static boolean isDangerous(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) {
            return true;
        }
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(Blocks.LADDER) || state.is(Blocks.VINE) || state.is(Blocks.SCAFFOLDING);
    }

    private static List<Neighbor> neighbors(ServerLevel level, BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>(16);
        for (int[] direction : HORIZONTAL) {
            int dx = direction[0];
            int dz = direction[1];
            boolean diagonal = dx != 0 && dz != 0;

            BlockPos same = pos.offset(dx, 0, dz);
            if (canStand(level, same) && (!diagonal || cornerClear(level, pos, dx, dz))) {
                neighbors.add(new Neighbor(same, diagonal ? 1.414 : 1.0));
            }

            BlockPos up = pos.offset(dx, 1, dz);
            if (canStand(level, up) && (!diagonal || cornerClear(level, pos, dx, dz))) {
                neighbors.add(new Neighbor(up, 1.6));
            }

            for (int drop = 1; drop <= 3; drop++) {
                BlockPos down = pos.offset(dx, -drop, dz);
                if (canStand(level, down) && (!diagonal || cornerClear(level, pos, dx, dz))) {
                    neighbors.add(new Neighbor(down, 1.0 + 0.45 * drop));
                }
            }
        }
        return neighbors;
    }

    private static boolean cornerClear(ServerLevel level, BlockPos pos, int dx, int dz) {
        return canStand(level, pos.offset(dx, 0, 0)) && canStand(level, pos.offset(0, 0, dz));
    }

    private record Neighbor(BlockPos pos, double cost) {
    }

    private record Node(long pos, double g, double f) {
    }
}
