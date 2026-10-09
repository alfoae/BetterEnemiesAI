package com.example.examplemod.Enemy.EnemyMovement.TerrainShape.Mixin;

import com.example.examplemod.Config;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeProbe;
import com.example.examplemod.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ДВІ цілі в цьому мексині — обидва методи {@code WalkNodeEvaluator} питають про ОДНУ й ту саму
 * клітинку під час побудови графа для КОЖНОГО наземного моба (не лише мобів цього мода):
 * {@code getFloorLevel(BlockGetter, BlockPos)} — "на якій висоті підлога тут", і
 * {@code getPathTypeFromState(BlockGetter, BlockPos)} — "яка це категорія клітинки". Свідомо НЕ
 * чіпає {@code getNeighbors} — той метод ідуть НАБАГАТО частіше правити інші AI-моди, тому саме тут
 * найменший ризик конфлікту з чужим мексином, а обидва цільові методи вже повертають не-булеве
 * значення (ванілья й так рахує детальніше за просте "блокує/не блокує") - природний, вузький шов,
 * без переписування алгоритму сусідів.
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
 * <b>Продуктивність.</b> {@link ShapeProbe#boxesOf} розкладає {@code VoxelShape} на коробки лише
 * коли форма НЕ є спільним екземпляром "повний куб" ({@code Shapes.block()}) — рідкість на типовому
 * ландшафті. Раніше тут замість цього питався {@code blocksMotion()} — саме це й ламало люки, див.
 * клас {@code ShapeProbe} за деталями.
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
 * <p>
 * <b>Другий інжект нижче — {@code getPathTypeFromState} — з ІНШОЇ, надійнішої причини.</b>
 * Перший інжект (на {@code getFloorLevel}) виправляє ВИСОТУ підлоги, але покладається на здогад:
 * яке саме число цей метод повинен повернути, щоб виклик ВСЕРЕДИНІ {@code WalkNodeEvaluator}
 * розпізнав "тут підлоги немає взагалі" й не запропонував цю клітинку як звичайний крок. Це не
 * підтверджено (вихідний код методу-викликача недоступний тут для звірки) — і саме тому відкритий
 * люк усе ще міг потрапляти в підрахунок сусідів як щось "прохідне на тому ж рівні". Другий інжект
 * діє на РІВЕНЬ ВИЩЕ й НЕ залежить від цього здогаду: {@code PathType.BLOCKED} — одне з
 * найфундаментальніших значень усього переліку, і його призначення ("тут не можна бути") не
 * залежить від внутрішньої реалізації жодного конкретного виклику. Спрацьовує ТІЛЬКИ коли
 * ванільна відповідь уже {@code TRAPDOOR}/{@code DANGER_TRAPDOOR} (тобто це вже категорія "люк") —
 * навмисно вузько, щоб не зачепити виклики цього самого методу для ІНШИХ ролей (перевірка місця
 * над головою тощо), де відповідь ніколи не буде цією категорією.
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
        // ТУТ НЕ перевіряємо state.blocksMotion() (раніше було - виявилось помилкою, див. чат: для
        // люків/дверей цей прапорець лишається true незалежно від open/closed). Швидкий шлях для
        // дійсно повних блоків усе одно є - всередині ShapeProbe.boxesOf, звіркою самої форми.
        boolean enabled = Config.ENABLE_SHAPE_AWARE_WALKING.get();
        double width = ShapeProbe.DEFAULT_MOB_WIDTH; // мобо-агностичний виклик - конкретного Mob тут нема
        ShapeGeometry.Footprint footprint = ShapeProbe.centeredFootprint(pos, width);
        ShapeGeometry.Support support = ShapeProbe.floorSupport(level, pos, footprint);
        // DEBUG: раніше тут був System.out.println на КОЖЕН виклик "без опори" - тобто практично на кожну
        // повітряну клітинку графа кожного моба; він і переповнював консоль. Тепер: за замовчуванням
        // вимкнено (debugFloorMixin=false), а коли ввімкнено - лише лічильники + 'цікаві' клітинки (де щось
        // є, а опори нема) раз на ~5с на позицію, у ОКРЕМИЙ файл (див. DebugLog.floorNoSupport).
        boolean debugFloor = DebugLog.on(DebugLog.Cat.FLOOR);
        if (debugFloor) {
            DebugLog.floorCall();
        }
        if (support.coverage() < ShapeProbe.MIN_FLOOR_COVERAGE) {
            if (debugFloor) {
                DebugLog.floorNoSupport(level, pos, cir.getReturnValue(), enabled, support.coverage());
            }
            if (enabled) {
                // Нема реальної опори під центром клітинки (відкритий люк при стінці тощо) - те саме
                // значення, яке ванілья повертає для звичайної порожньої клітинки без підлоги.
                cir.setReturnValue((double) pos.getY());
            }
            return;
        }
        if (enabled) {
            cir.setReturnValue(support.surfaceY());
        }
    }

    /**
     * Надійніший запобіжник саме для люків — див. javadoc класу вище. Діє на рівень категорії
     * ({@code PathType}), а не висоти, тому не залежить від того, як саме виклик-джерело трактує
     * повернене число {@link #betterEnemiesAi$shapeAwareFloorLevel} вище.
     */
    @Inject(
            method = "getPathTypeFromState(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void betterEnemiesAi$blockUnsupportedTrapdoor(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        PathType vanilla = cir.getReturnValue();
        if (vanilla != PathType.TRAPDOOR && vanilla != PathType.DANGER_TRAPDOOR) {
            return; // не наш кейс (двері, паркани тощо мають свою окрему логіку) - не логуємо, забагато шуму
        }
        // DEBUG: якщо рядка "getPathTypeFromState" НЕ видно у файлі debug-логу при тесті біля люка (з увімкненим
        // debugFloorMixin) - міксин не застосувався (перевір лог завантаження на "terrainshape").
        boolean enabled = Config.ENABLE_SHAPE_AWARE_WALKING.get();
        ShapeGeometry.Footprint footprint = ShapeProbe.centeredFootprint(pos, ShapeProbe.DEFAULT_MOB_WIDTH);
        ShapeGeometry.Support support = enabled
                ? ShapeProbe.floorSupport(level, pos, footprint)
                : ShapeGeometry.Support.NONE;
        boolean overridden = enabled && support.coverage() < ShapeProbe.MIN_FLOOR_COVERAGE;
        if (DebugLog.on(DebugLog.Cat.FLOOR)) {
            DebugLog.floorTrapdoor(level, pos, vanilla, enabled, support.coverage(), support.surfaceY(), overridden);
        }
        if (overridden) {
            cir.setReturnValue(PathType.BLOCKED);
        }
    }
}
