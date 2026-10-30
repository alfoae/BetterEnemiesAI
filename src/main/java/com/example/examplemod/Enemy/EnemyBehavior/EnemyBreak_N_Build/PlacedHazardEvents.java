package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import com.example.examplemod.BetterEnemiesAI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * НАЙНИЖЧА впевненість з усього, що написано в цій фазі — {@code PlayerInteractEvent.RightClickBlock}
 * ніде більше в цьому проєкті не використовується, тож і звірити нема з чим (на відміну від
 * {@code LevelTickEvent}/{@code LivingKnockBackEvent}, де я спирався на вже наявний у коді
 * {@code LivingDeathEvent} з того самого пакета). Якщо ця подія не спрацює або матиме інші назви
 * методів у вашій точній версії NeoForge — це ізольовано ламає ЛИШЕ файл, ловити (розмежування
 * "гравцем/не гравцем" деінде не постраждає), спокійно кажіть, поправлю.
 */
@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public final class PlacedHazardEvents {

    private PlacedHazardEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        ItemStack held = event.getItemStack();
        if (!isHazardItem(held.getItem())) return;

        Direction face = event.getFace();
        if (face == null) return;

        BlockPos target = event.getPos().relative(face);
        PlacedHazardRegistry.markPlayerPlaced(level, target, level.getGameTime());
    }

    private static boolean isHazardItem(Item item) {
        return item == Items.LAVA_BUCKET || item == Items.WATER_BUCKET || item == Items.FLINT_AND_STEEL;
    }
}
