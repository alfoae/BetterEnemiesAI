package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import java.util.Set;

/**
 * {@link PathFinder}, що після звичайного A* віддає шлях на уточнення в
 * {@link ShapeAwareNodeEvaluator#refine} (точні точки стояння: край, кут) і звільняє кеші пошуку.
 * Якщо щось падає — повертає ванільний шлях, а не ламає тік.
 */
public class ShapePathFinder extends PathFinder {

    private final ShapeAwareNodeEvaluator evaluator;

    public ShapePathFinder(ShapeAwareNodeEvaluator evaluator, int maxVisitedNodes) {
        super(evaluator, maxVisitedNodes);
        this.evaluator = evaluator;
    }

    @Override
    public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
                         float maxRange, int accuracy, float searchDepthMultiplier) {
        Path path = super.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
        try {
            return this.evaluator.refine(path);
        } catch (RuntimeException e) {
            ShapeAwareNodeEvaluator.warnRefine(e);
            return path;
        } finally {
            this.evaluator.release();
        }
    }
}
