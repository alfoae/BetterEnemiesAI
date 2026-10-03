package com.example.examplemod.mobAi.Mixin;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapePathAccess;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeProbe;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Куди йти» для шляхів із точними точками. Ваніль вважає вузол досягнутим, коли моб у ~0.45 блока від
 * ЦЕНТРУ клітинки, і може зрізати кут до наступного вузла — для вузької точки на краю люка це означало
 * б, що моб «дійшов» ще з центру (над діркою). Тому, якщо поточна або наступна точка «вузька»:
 * <ul>
 *   <li>вузол береться лише коли моб справді біля ТОЧКИ (або проскочив її вздовж шляху);</li>
 *   <li>зрізання кутів вимкнене;</li>
 *   <li>«гальмо»: якщо за інерцією наступна позиція вже без опори — горизонтальна швидкість гаситься
 *       (як присісти на краю).</li>
 * </ul>
 * В усіх інших випадках виконується ванільний {@code followThePath}.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Shadow
    protected Path path;

    @Shadow
    protected abstract Vec3 getTempMobPos();

    @Shadow
    protected abstract void doStuckDetection(Vec3 positionVec3);

    @Inject(method = "followThePath", at = @At("HEAD"), cancellable = true)
    private void betterEnemies$followExactSpots(CallbackInfo ci) {
        Path p = this.path;
        if (p == null || p.isDone()) {
            return;
        }
        Object holder = p;
        if (!(holder instanceof ShapePathAccess access)) {
            return;
        }
        Vec3[] spots = access.betterEnemies$spots();
        boolean[] tight = access.betterEnemies$tight();
        if (spots == null || tight == null) {
            return;
        }
        int i = p.getNextNodeIndex();
        if (i < 0 || i >= spots.length) {
            return;
        }
        boolean curTight = tight[i];
        boolean nextTight = i + 1 < tight.length && tight[i + 1];
        if (!curTight && !nextTight) {
            return; // нічого особливого — ванільна поведінка
        }

        Vec3 target = spots[i];
        double dx = this.mob.getX() - target.x;
        double dz = this.mob.getZ() - target.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float w = this.mob.getBbWidth();
        double tolerance = curTight ? 0.14 : (w > 0.75F ? w / 2.0F : 0.75F - w / 2.0F);
        boolean reached = dist < tolerance && Math.abs(this.mob.getY() - target.y) < 1.0;

        if (!reached && i > 0) {
            // проскочили точку вздовж напрямку шляху (і лишились поруч) — не ходимо колами
            Vec3 prev = spots[i - 1];
            double sx = target.x - prev.x;
            double sz = target.z - prev.z;
            double len = Math.sqrt(sx * sx + sz * sz);
            if (len > 1.0E-4) {
                double along = ((this.mob.getX() - target.x) * sx + (this.mob.getZ() - target.z) * sz) / len;
                double lateral = Math.abs(((this.mob.getX() - target.x) * -sz + (this.mob.getZ() - target.z) * sx) / len);
                reached = along > 0.0 && lateral < 0.35 && dist < 1.0;
            }
        }
        if (reached) {
            p.advance();
        }
        if (curTight || nextTight) {
            betterEnemies$brake(target);
        }
        this.doStuckDetection(this.getTempMobPos());
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.Unique
    private void betterEnemies$brake(Vec3 target) {
        if (!this.mob.onGround()) {
            return;
        }
        if (target.y < this.mob.getY() - 0.6) {
            return; // шлях свідомо веде вниз (падіння) — не гальмуємо
        }
        Vec3 v = this.mob.getDeltaMovement();
        double hx = v.x * 1.5;
        double hz = v.z * 1.5;
        if (hx * hx + hz * hz < 1.0E-6) {
            return;
        }
        boolean supported = ShapeProbe.hasStandingSupport(this.mob.level(),
                this.mob.getX() + hx, this.mob.getZ() + hz, this.mob.getY(), this.mob.getBbWidth());
        if (!supported) {
            this.mob.setDeltaMovement(0.0, v.y, 0.0);
        }
    }
}
