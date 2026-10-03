package com.example.examplemod.mobAi.Mixin;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeAwareNodeEvaluator;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathFinder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Універсальна підміна для ВСІХ наземних мобів (ванільних і модових): будь-який
 * {@code GroundPathNavigation}, який сам не перевизначає {@code createPathFinder}, отримує
 * {@link ShapeAwareNodeEvaluator} + {@link ShapePathFinder}. Навігації, що перевизначають
 * {@code createPathFinder} повністю (наприклад, {@code GapJumpPathNavigation}), цього інжекта не
 * зачіпають — вони підключають ті самі класи самі. Скелелази (павуки) пропускаються.
 * <p>
 * Вимикається в конфігу ({@code enableShapeAwareWalking}) і чорним списком мобів — тоді evaluator
 * поводиться як ванільний.
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin extends PathNavigation {

    private GroundPathNavigationMixin(Mob mob, Level level) {
        super(mob, level);
    }

    @Inject(method = "createPathFinder", at = @At("HEAD"), cancellable = true)
    private void betterEnemies$shapePathFinder(int maxVisitedNodes, CallbackInfoReturnable<PathFinder> cir) {
        Object self = this;
        if (self instanceof WallClimberNavigation) {
            return;
        }
        ShapeAwareNodeEvaluator evaluator = new ShapeAwareNodeEvaluator();
        this.nodeEvaluator = evaluator;
        this.nodeEvaluator.setCanPassDoors(true);
        cir.setReturnValue(new ShapePathFinder(evaluator, maxVisitedNodes));
    }
}
