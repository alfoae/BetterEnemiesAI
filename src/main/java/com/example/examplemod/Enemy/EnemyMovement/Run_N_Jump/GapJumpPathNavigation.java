package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Той самий {@code GroundPathNavigation}, який мобу дав би ванільний {@code createNavigation},
 * тільки з {@link GapJumpNodeEvaluator} замість стандартного {@code WalkNodeEvaluator}. Це
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
        this.nodeEvaluator = new GapJumpNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
