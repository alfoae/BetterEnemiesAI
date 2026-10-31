package com.example.examplemod.debug;

import com.example.examplemod.BetterEnemiesAI;
import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.FallDebug;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/**
 * v21 (ДЕБАГ, категорія {@code [FALL]}): фіксує КОЖНЕ падіння моба від {@link #MIN_LOGGED_FALL} блоків за подією гри - з тією
 * відстанню й тим множником блока, які застосувала САМА гра (див. {@link FallDebug#logActualFall}). Це "правда" для порівняння
 * з прогнозом моделі, навіть коли моб зійшов з краю без жодного стрибка (ванільне ребро спуску). Нічого не змінює й нічого не
 * скасовує: подія лише читається.
 */
@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public final class FallEvents {

    /**
     * Дрібні сходинки (1-2 блоки) не цікаві й засмічували б лог.
     */
    private static final float MIN_LOGGED_FALL = 2.0F;

    private FallEvents() {
    }

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if (event.getDistance() < MIN_LOGGED_FALL) {
            return;
        }
        FallDebug.logActualFall(mob, event.getDistance(), event.getDamageMultiplier());
    }
}
