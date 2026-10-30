package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import com.example.examplemod.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Усе, що для розрахунку шкоди від падіння треба прочитати з Minecraft: стан моба ({@link #profileOf}) і блоки
 * приземлення ({@link #landingMultiplier}). Сама арифметика - у {@link FallMath}. Цей клас навмисно маленький: якщо якесь
 * ім'я API відрізнятиметься у твоїй версії - правити доведеться лише тут.
 */
final class FallDamage {

    private FallDamage() {
    }

    /**
     * Знімок моба: здоров'я, поглинання (золоті серця), безпечна висота падіння й множник шкоди (атрибути; ефект Стрибучості
     * вже входить у безпечну висоту), Опір, захист чарами всього спорядження (ванільний {@code getDamageProtection} - сума
     * Невагомості й Захисту; БРОНЯ САМА падіння не зменшує) та імунітет (тип, Повільне падіння, невразливість).
     */
    static FallMath.Profile profileOf(Mob mob) {
        DamageSource fall = mob.damageSources().fall();
        float protection = 0.0F;
        if (mob.level() instanceof ServerLevel serverLevel) {
            protection = EnchantmentHelper.getDamageProtection(serverLevel, mob, fall);
        }
        MobEffectInstance resistance = mob.getEffect(MobEffects.DAMAGE_RESISTANCE);
        boolean immune = mob.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE)
                || mob.hasEffect(MobEffects.SLOW_FALLING)
                || mob.isInvulnerableTo(fall);
        return new FallMath.Profile(
                mob.getHealth(),
                mob.getAbsorptionAmount(),
                Config.FALL_DAMAGE_RESERVE_HP.get().floatValue(),
                mob.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                mob.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER),
                resistance == null ? 0 : resistance.getAmplifier() + 1,
                protection,
                immune);
    }

    /**
     * Множник шкоди за тим, ЩО під ногами при приземленні (як {@code Block.fallOn} у ваніллі та скидання падіння):
     * вода/павутиння - 0 (падіння скидається), слайм - 0 (відскок), сіно й мед - 0.2, ліжко - 0.5, решта - 1.
     *
     * @param below блок під клітинкою приземлення (опора)
     * @param feet  клітинка ніг
     * @param head  клітинка над нею (глибша вода)
     */
    static double landingMultiplier(BlockState below, BlockState feet, BlockState head) {
        if (feet.getFluidState().is(FluidTags.WATER) || head.getFluidState().is(FluidTags.WATER)) {
            return 0.0;
        }
        if (feet.is(Blocks.COBWEB) || head.is(Blocks.COBWEB)) {
            return 0.0;
        }
        if (below.is(Blocks.SLIME_BLOCK)) {
            return 0.0;
        }
        if (below.is(Blocks.HAY_BLOCK) || below.is(Blocks.HONEY_BLOCK)) {
            return 0.2;
        }
        if (below.is(BlockTags.BEDS)) {
            return 0.5;
        }
        return 1.0;
    }
}
