package com.example.examplemod.mobAi.Mixin;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Додає до ванільного {@link Path} точні точки стояння по вузлах ({@link ShapePathAccess}) і
 * підміняє «куди йти до вузла»: замість центру клітинки — точка, яку обрав {@code ShapeAwareNodeEvaluator}
 * (край/кут відкритого люка тощо). Для шляхів без цих даних поведінка суто ванільна.
 */
@Mixin(Path.class)
public abstract class PathMixin implements ShapePathAccess {

    @Unique
    private Vec3[] betterEnemies$spots;

    @Unique
    private boolean[] betterEnemies$tight;

    @Override
    public void betterEnemies$setWaypoints(Vec3[] spots, boolean[] tight) {
        this.betterEnemies$spots = spots;
        this.betterEnemies$tight = tight;
    }

    @Override
    public Vec3[] betterEnemies$spots() {
        return this.betterEnemies$spots;
    }

    @Override
    public boolean[] betterEnemies$tight() {
        return this.betterEnemies$tight;
    }

    @Inject(method = "getEntityPosAtNode", at = @At("HEAD"), cancellable = true)
    private void betterEnemies$useExactSpot(Entity entity, int index, CallbackInfoReturnable<Vec3> cir) {
        Vec3[] spots = this.betterEnemies$spots;
        if (spots != null && index >= 0 && index < spots.length) {
            cir.setReturnValue(spots[index]);
        }
    }
}
