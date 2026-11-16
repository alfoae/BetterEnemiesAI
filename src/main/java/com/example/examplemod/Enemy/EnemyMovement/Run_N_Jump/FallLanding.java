package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * v22: множник шкоди приземлення = {@link FallDamage#landingMultiplier} + те, чого він не вміє: драбини, ліани, риштування
 * (тег {@code climbable}). У ваніллі моб, чия клітинка ніг - "лазна" ({@code LivingEntity#onClimbable}), на кожному тіку
 * скидає лічильник падіння й падає не швидше за 0.15 блока/тік, тож приземлення В такій клітинці не завдає шкоди.
 * <p>
 * Усі, хто питає "скільки шкоди від приземлення сюди" (стрибкові ребра, ребра спуску, дебаг), мають користуватись цим класом.
 */
public final class FallLanding {

    private FallLanding() {
    }

    /**
     * Множник шкоди приземлення в клітинку {@code feet} (під нею {@code below}, над нею {@code head}): 0 - без шкоди.
     */
    public static double multiplier(BlockState below, BlockState feet, BlockState head) {
        double multiplier = FallDamage.landingMultiplier(below, feet, head);
        if (multiplier > 0.0 && feet.is(BlockTags.CLIMBABLE)) {
            return 0.0; // драбина/ліани/риштування скидають падіння
        }
        return multiplier;
    }
}
