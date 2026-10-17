package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Клітинка, до якої моб будує шлях, коли ЖИВО переслідує гравця ({@code EnemyBreak_N_BuildUtils.resolvePathTarget}).
 * <p>
 * <b>Баг, який це лікує (v15.2).</b> Для гравця на землі ціль = {@code getOnPos().above()}: {@code getOnPos()} дає реальний блок
 * опори через колізію хітбокса, тож гравець, що стоїть на самому краю блока (центр уже за краєм), має правильну ціль. Але
 * у ПОВІТРІ опорного блока немає, і {@code getOnPos()} повертає клітинку за координатами ЦЕНТРУ. Коли гравець підстрибує на
 * краю й дрейфує на 0.2-0.3 бл, його хітбокс цілком опиняється над порожнечею, і ціль стає клітинкою над провалом. Ванільний
 * {@code GroundPathNavigation.createPath} опускає таку ціль вниз, не знаходить землі (або знаходить на дні провалу),
 * і шлях стає «частковим»: до найближчого/найвищого досяжного вузла - десь за 10 блоків від гравця. Моб біжить туди (стрибаючи
 * через розриви), гравець приземляється, ціль знову досяжна, моб біжить назад. Це й був «ходить туди-сюди й стрибає туди-сюди».
 * Те саме було, коли гравець перестрибував розрив сам: поки він у повітрі над провалом, ціль безглузда.
 * <p>
 * <b>Що тепер.</b> Гравець на землі - як і раніше. У повітрі: (1) опора ПІД ХІТБОКСОМ (до {@link #SEARCH_DEPTH} блоків униз) -
 * та сама клітинка, що була б на землі, тож стрибок на місці ціль не змінює; (2) якщо під хітбоксом порожнеча - ОСТАННЯ опорна
 * ціль (гравець стрибає через розрив: моб чекає на цьому боці, а не тікає, і перестрибує, коли гравець приземлиться);
 * (3) інакше стара поведінка.
 */
public final class PursuitTargetResolver {

    /** Скільки блоків униз шукаємо опору під хітбоксом гравця в повітрі (стрибок - до 1.25, падіння - більше). */
    static final int SEARCH_DEPTH = 6;

    /** Скільки тіків тримаємо останню опорну ціль, поки гравець у повітрі над порожнечею. */
    static final int HOLD_TICKS = 200;

    private record Grounded(BlockPos pos, int playerId, int tick) {
    }

    private static final Map<Mob, Grounded> LAST_GROUNDED = new WeakHashMap<>();

    private PursuitTargetResolver() {
    }

    /** Ціль шляху моба до {@code player}, що не стрибає разом із ним (див. клас). */
    public static BlockPos groundedTarget(Mob mob, Player player) {
        if (player.onGround()) {
            BlockPos target = player.getOnPos().above();
            LAST_GROUNDED.put(mob, new Grounded(target, player.getId(), mob.tickCount));
            return target;
        }
        BlockPos support = supportBelow(player);
        if (support != null) {
            BlockPos target = support.above();
            LAST_GROUNDED.put(mob, new Grounded(target, player.getId(), mob.tickCount));
            return target;
        }
        Grounded last = LAST_GROUNDED.get(mob);
        if (last != null && last.playerId() == player.getId() && mob.tickCount - last.tick() <= HOLD_TICKS) {
            return last.pos();
        }
        return player.getOnPos().above();
    }

    /**
     * Блок, що підтримав би гравця, якби він зараз приземлився: найвищий блок із колізією під ХІТБОКСОМ (не під центром) у межах
     * {@link #SEARCH_DEPTH}; із кількох на одній висоті - найближчий до центру (так само, як {@code mainSupportingBlockPos}).
     * {@code null}, якщо під хітбоксом порожнеча.
     */
    static BlockPos supportBelow(Player player) {
        Level level = player.level();
        AABB box = player.getBoundingBox();
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX - 1.0E-6);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ - 1.0E-6);
        int startY = Mth.floor(box.minY - 1.0E-4);   // клітинка просто під ногами
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        for (int y = startY; y > startY - SEARCH_DEPTH; y--) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getCollisionShape(level, pos).isEmpty()) {
                        continue;
                    }
                    double dx = x + 0.5 - centerX;
                    double dz = z + 0.5 - centerZ;
                    double distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }
}
