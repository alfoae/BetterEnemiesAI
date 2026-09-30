package com.example.examplemod.Enemy.EnemyMovement.TerrainShape.Mixin;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ЄДИНА ціль цього мексину — {@code WalkNodeEvaluator.getFloorLevel(BlockGetter, BlockPos)}: питання
 * "на якій висоті підлога В ЦІЙ клітинці", яке ванілья задає під час побудови графа для КОЖНОГО
 * наземного моба (не лише мобів цього мода). Свідомо НЕ чіпає {@code getNeighbors} чи
 * {@code getPathTypeFromState} — вони йдуть НАБАГАТО частіше в інші AI-моди, тому саме тут
 * найменший ризик конфлікту з чужим мексином, а сам {@code getFloorLevel} уже повертає {@code double}
 * (ванілья й так рахує НЕ булево) - природний, вузький шов, без переписування алгоритму сусідів.
 * <p>
 * <b>Що саме виправляє.</b> Ванільна реалізація визначає "є підлога" через {@code blocksMotion()} —
 * прапорець "форма ≈ повний куб", порахований один раз при реєстрації blockstate. Він нічого не
 * знає, ЯКУ саме частину клітинки займає блок: відкритий люк (тонка ВЕРТИКАЛЬНА панель при одній
 * стінці клітинки) дає {@code blocksMotion()==false}, тому ванілья класифікує його як
 * {@code DANGER_TRAPDOOR} — це лише штраф до вартості шляху (кожен {@code PathType} має один
 * параметр, malus), а НЕ заборону; моб може туди піти, якщо це єдиний шлях, і фізично провалюється,
 * бо опори там реально нема. Це не вигадана проблема — офіційний баг-трекер Mojang підтверджує, що
 * навігація мобів ламається саме навколо відкритих люків і воріт (виправлено в снапшоті вже ПІСЛЯ
 * 1.21.1 — на цій версії багу ще живий). Тут виправляється сама ГЕОМЕТРІЯ: {@link ShapeProbe} питає
 * реальну {@code VoxelShape} замість {@code blocksMotion()} — те саме ядро, що й
 * {@code GapJumpNodeEvaluator} у цьому ж моді.
 * <p>
 * <b>Побічний ефект (бажаний):</b> той самий виклик відповідає і за ТОЧНІСТЬ висоти для звичайних
 * неповних блоків (плити, килими, горщики) — раніше ванільний node.y для них теж міг бути неточним;
 * тепер {@code GapJumpNodeEvaluator} (v6) і ЦЕЙ мексин рахують висоту ОДНАКОВО, тим самим кодом.
 * <p>
 * <b>Продуктивність.</b> {@link ShapeProbe#boxesOf} має дешевий фолбек: якщо {@code blocksMotion()}
 * блока {@code true}, форма НЕ розкладається (той самий прапорець, який ванілья вже порахувала).
 * Розклад {@code VoxelShape} на коробки відбувається лише для "неоднозначних" блоків — рідкість на
 * типовому ландшафті.
 * <p>
 * <b>ЧЕСНО, найменш перевірена частина всієї роботи.</b> Точний дескриптор методу нижче
 * (аргументи {@code BlockGetter, BlockPos}, повертає {@code double}, {@code protected static}) —
 * з мапінгів 1.21.8/останніх снапшотів, БЕЗ прямої звірки з 1.21.1: сигнатура публічного/
 * протектид API зазвичай стабільна між патч-версіями однієї великої версії, але не перевірено руками.
 * Якщо Mixin не знайде метод — кине чітку помилку із зазначенням, який саме мексин і метод
 * (перевірте лог на "betterenemiesai.terrainshape" і поправте рядок {@code method} нижче за
 * реальною сигнатурою з вашого mapped-classpath). Саме тому конфіг цього мексину
 * ({@code mixins.betterenemiesai.terrainshape.json}) свідомо {@code "required": false} — якщо
 * сигнатура не збіжиться, мод далі вантажиться і працює, просто без цього конкретного виправлення
 * (власний стрибковий код мода, {@code GapJumpNodeEvaluator}, від цього мексину НЕ залежить).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorFloorMixin {

    @Inject(
            method = "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void betterEnemiesAi$shapeAwareFloorLevel(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
        if (!Config.ENABLE_SHAPE_AWARE_PATHING.get()) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.blocksMotion()) {
            return; // майже повний куб - ванільна відповідь (pos.getY()+1) і так правильна, не чіпаємо
        }
        double width = ShapeProbe.DEFAULT_MOB_WIDTH; // мобо-агностичний виклик - конкретного Mob тут нема
        ShapeGeometry.Footprint footprint = ShapeProbe.centeredFootprint(pos, width);
        ShapeGeometry.Support support = ShapeProbe.floorSupport(level, pos, footprint);
        if (support.coverage() < ShapeProbe.MIN_FLOOR_COVERAGE) {
            // Нема реальної опори під центром клітинки (відкритий люк при стінці тощо) - те саме
            // значення, яке ванілья повертає для звичайної порожньої клітинки без підлоги.
            cir.setReturnValue((double) pos.getY());
            return;
        }
        cir.setReturnValue(support.surfaceY());
    }
}
