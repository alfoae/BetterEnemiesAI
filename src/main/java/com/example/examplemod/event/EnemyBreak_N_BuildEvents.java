package com.example.examplemod.event;

import com.example.examplemod.BetterEnemiesAI;
import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.DigBlockResolver;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.MiningTierData;
import com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build.TemporaryBlockData;
import com.example.examplemod.Enemy.EnemyInventory.EnemyStorage.IMobBlockStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;

/**
 * Всі обробники подій для системи копання/будівництва мобів в одному місці:
 * <ul>
 *   <li>{@link #onAdvancementEarn} — просуває глобальний {@link MiningTierData} тір;</li>
 *   <li>{@link #onLevelTick} — раз на секунду знімає прострочені тимчасові блоки
 *       (ставились через {@link TemporaryBlockData});</li>
 *   <li>{@link #onBlockDrops} — якщо гравець ламає ще НЕ прострочений блок мобу і конфіг каже
 *       "без дропу" — прибирає предмети (сам блок все одно ламається нормально);</li>
 *   <li>{@link #onLivingDeath} — висипає "інвентар" викопаних мобом блоків при його смерті.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public class EnemyBreak_N_BuildEvents {

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity().level().isClientSide()) return;

        ResourceLocation id = event.getAdvancement().id();
        MiningTierData data = MiningTierData.get(event.getEntity().level().getServer());
        if (data.tryUpgrade(id)) {
            BetterEnemiesAI.LOGGER.info("EnemyBreak_N_Build: mining tier піднято до {} (досягнення {})",
                    data.getTier(), id);
        }
    }

    /**
     * v20: скільки прострочених блоків знімаємо за один прохід (раз на секунду): накопичені за час, поки обробник був
     * "мертвий", записи не мають права підвісити сервер одним тіком - решта зніметься на наступних проходах.
     */
    private static final int MAX_EXPIRED_REMOVALS_PER_PASS = 256;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;

        // Раз на секунду — не кожен тік, немає сенсу перевіряти частіше.
        if (level.getGameTime() % 20 != 0) return;

        TemporaryBlockData data = TemporaryBlockData.get(level);
        Map<BlockPos, Long> snapshot = data.snapshot();
        if (snapshot.isEmpty()) return;

        long now = level.getGameTime();
        int removed = 0;
        for (Map.Entry<BlockPos, Long> entry : snapshot.entrySet()) {
            if (entry.getValue() > now) continue;
            if (removed >= MAX_EXPIRED_REMOVALS_PER_PASS) break;
            BlockPos pos = entry.getKey();
            // v20: чанк не завантажений - запис ЛИШАЄТЬСЯ: getBlockState на такій позиції форсував би завантаження чанка
            // прямо в тіку сервера. Блок зніметься, щойно чанк завантажиться.
            if (!level.hasChunkAt(pos)) continue;
            // Що саме поставили: новий запис пам'ятає блок, старий (без id) - за поточним конфігом.
            Block expected = data.getPlacedBlock(pos);
            if (expected == null) {
                expected = DigBlockResolver.getDigBlock(level);
            }
            BlockState state = level.getBlockState(pos);
            // Захисна перевірка: видаляємо лише якщо там і досі саме той блок, який поставили ми (гравець міг вже зламати його
            // раніше — тоді onBlockDrops уже прибрав запис — або поставити щось своє поверху). Земля -> трава (поширення
            // трави перетворює поставлений ґрунт на інший блок тієї ж родини) теж наш блок.
            if (state.is(expected)
                    || (expected.defaultBlockState().is(BlockTags.DIRT) && state.is(BlockTags.DIRT))) {
                level.removeBlock(pos, false);
            }
            data.untrack(pos);
            removed++;
        }
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        TemporaryBlockData data = TemporaryBlockData.get(level);
        if (data.isTracked(event.getPos())) {
            data.untrack(event.getPos());
            if (!Config.PLACED_BLOCK_DROPS_WHEN_BROKEN.get()) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(mob instanceof IMobBlockStorage storage)) return;

        for (ItemStack stack : storage.getDugBlocks()) {
            if (!stack.isEmpty()) {
                ItemEntity itemEntity = new ItemEntity(mob.level(), mob.getX(), mob.getY(), mob.getZ(), stack.copy());
                mob.level().addFreshEntity(itemEntity);
            }
        }
        storage.clearDugBlocks();
    }
}
