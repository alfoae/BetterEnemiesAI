package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathFinder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Той самий {@code GroundPathNavigation}, який мобу дав би ванільний {@code createNavigation},
 * тільки з {@link GapJumpNodeEvaluator} (який успадковує форм-орієнтований
 * {@code ShapeAwareNodeEvaluator}) і {@code ShapePathFinder} замість стандартних. Це
 * єдина зміна — весь інший функціонал (recompute, stuck-detection, canOpenDoors тощо)
 * лишається ванільним, успадкованим без змін.
 * <p>
 * Патерн підтверджений документацією (createPathFinder — protected override point, оголошений
 * саме в GroundPathNavigation; поле nodeEvaluator успадковане з PathNavigation) — це найменш
 * ризикована частина з усього, що тут дописано.
 */
public class GapJumpPathNavigation extends GroundPathNavigation {

    public GapJumpPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        GapJumpNodeEvaluator evaluator = new GapJumpNodeEvaluator();
        this.nodeEvaluator = evaluator;
        this.nodeEvaluator.setCanPassDoors(true);
        return new ShapePathFinder(evaluator, maxVisitedNodes);
    }
}
