package com.example.examplemod.Enemy.EnemyInventory.EnemyStorage;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * "Інвентар" мобу для блоків, які він сам викопав під час копання до гравця. Наповнюється, коли
 * моб ламає блок ({@code addDugBlock}), спорожняється при смерті (дроп) — див.
 * {@code EnemyBreak_N_BuildEvents#onLivingDeath}. Блоки, які мод поставив сам (тимчасові), сюди не потрапляють - див.
 * {@code EnemyBreak_N_BuildUtils#breakBlock}. Той самий підхід, що й {@link IWeaponStorage} — Mixin на
 * {@code Mob.class} глобально, реалізовано в {@code MobBlockStorageMixin}.
 */
public interface IMobBlockStorage {

    List<ItemStack> getDugBlocks();

    /**
     * Додає один зламаний блок у "інвентар" — зливає в існуючий стек того самого предмета,
     * якщо є місце (з урахуванням {@code ItemStack#getMaxStackSize()}), інакше додає новий стек.
     */
    void addDugBlock(ItemStack stack);

    void clearDugBlocks();
}
