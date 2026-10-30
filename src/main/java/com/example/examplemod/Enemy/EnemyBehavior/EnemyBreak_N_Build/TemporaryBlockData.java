package com.example.examplemod.Enemy.EnemyBehavior.EnemyBreak_N_Build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Відстежує блоки, які поставили моби (міст/вежа під час копання), разом з тіком, коли кожен з
 * них має зникнути сам. Одна ціль {@code TemporaryBlockData} на КОЖЕН вимір (SavedData
 * прив'язується до конкретного {@code ServerLevel}, тож окремі виміри автоматично не плутають
 * позиції між собою — не треба власного ключа з ResourceKey).
 * <p>
 * Періодичне видалення прострочених — {@code EnemyBreak_N_BuildEvents#onLevelTick} (тік раз на секунду). "Гравець
 * ламає такий блок раніше" обробляється окремо через {@code BlockDropsEvent} — там достатньо
 * прибрати позицію звідси, самого видалення блоку вже не треба (гравець і так його зламав).
 */
public class TemporaryBlockData extends SavedData {

    public static final SavedData.Factory<TemporaryBlockData> FACTORY =
            new SavedData.Factory<>(TemporaryBlockData::new, TemporaryBlockData::load, null);

    private final Map<BlockPos, Long> expireAtTick = new HashMap<>();
    // v20: який САМЕ блок поставили в цій позиції (id реєстру). Раніше при зникненні порівнювали з блоком ПОТОЧНОГО конфігу
    // (DigBlockResolver): змінив overworldDigBlock між запуском і зникненням - старі блоки більше не збігались, не знімались,
    // а запис викидався - вони лишались у світі назавжди. Старі записи (без id) читаються й далі - за поточним конфігом.
    private final Map<BlockPos, String> placedBlockId = new HashMap<>();

    public static TemporaryBlockData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "betterenemysai_temp_blocks");
    }

    private static TemporaryBlockData load(CompoundTag tag, HolderLookup.Provider registries) {
        TemporaryBlockData data = new TemporaryBlockData();
        ListTag list = tag.getList("Entries", 10); // 10 = CompoundTag id
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            data.expireAtTick.put(pos, entry.getLong("expire"));
            if (entry.contains("block")) {
                data.placedBlockId.put(pos, entry.getString("block"));
            }
        }
        return data;
    }

    public void track(BlockPos pos, long expireAtGameTime) {
        BlockPos key = pos.immutable();
        this.expireAtTick.put(key, expireAtGameTime);
        this.placedBlockId.remove(key);
        this.setDirty();
    }

    /**
     * v20: те саме, але з ЗАПАМ'ЯТОВУВАННЯМ блока, який поставили (див. {@link #getPlacedBlock}).
     */
    public void track(BlockPos pos, long expireAtGameTime, Block block) {
        BlockPos key = pos.immutable();
        this.expireAtTick.put(key, expireAtGameTime);
        this.placedBlockId.put(key, BuiltInRegistries.BLOCK.getKey(block).toString());
        this.setDirty();
    }

    public void untrack(BlockPos pos) {
        this.placedBlockId.remove(pos);
        if (this.expireAtTick.remove(pos) != null) {
            this.setDirty();
        }
    }

    /**
     * v20: блок, який поставили в цій позиції, або {@code null} (старий запис без id / невідомий id).
     */
    public Block getPlacedBlock(BlockPos pos) {
        String id = this.placedBlockId.get(pos);
        if (id == null) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block.defaultBlockState().isAir() ? null : block;
    }

    public boolean isTracked(BlockPos pos) {
        return this.expireAtTick.containsKey(pos);
    }

    /**
     * Знімок поточних записів для тік-обробника — копія, щоб можна було модифікувати мапу під час ітерації.
     */
    public Map<BlockPos, Long> snapshot() {
        return new HashMap<>(this.expireAtTick);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Long> entry : this.expireAtTick.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putInt("x", entry.getKey().getX());
            entryTag.putInt("y", entry.getKey().getY());
            entryTag.putInt("z", entry.getKey().getZ());
            entryTag.putLong("expire", entry.getValue());
            String blockId = this.placedBlockId.get(entry.getKey());
            if (blockId != null) {
                entryTag.putString("block", blockId);
            }
            list.add(entryTag);
        }
        tag.put("Entries", list);
        return tag;
    }
}
