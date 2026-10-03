package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * Єдине місце, де вирішується «чи застосовувати форм-орієнтовану ходьбу до цього моба»:
 * глобальний вимикач і чорний список ID типів мобів із конфіга. Якщо конфіг ще не завантажений
 * (ранній виклик) — вважаємо увімкненим, нічого не ламаючи.
 */
public final class ShapeSettings {

    private ShapeSettings() {
    }

    public static boolean enabledFor(Mob mob) {
        try {
            if (!Config.ENABLE_SHAPE_AWARE_WALKING.get()) {
                return false;
            }
            String blacklist = Config.SHAPE_WALK_BLACKLIST.get();
            if (blacklist == null || blacklist.isBlank()) {
                return true;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
            String idStr = id.toString();
            for (String entry : blacklist.split(",")) {
                if (entry.trim().equals(idStr)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException configNotLoaded) {
            return true;
        }
    }
}
