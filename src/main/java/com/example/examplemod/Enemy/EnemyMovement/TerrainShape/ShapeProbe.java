package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Box;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Footprint;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Support;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Міст між реальним Minecraft ({@code BlockState.getCollisionShape}) і чистою геометрією
 * {@link ShapeGeometry}. Це ЄДИНЕ місце в моді, що читає {@code VoxelShape} напряму — усе інше
 * (evaluator, мексин) працює через ці методи, а не через {@code blocksMotion()}/{@code isSolid()}.
 * <p>
 * <b>Швидкий шлях (продуктивність).</b> {@link #boxesOf} завжди питає реальну
 * {@code state.getCollisionShape(level, pos)} (без цього нема як дізнатись правду), але розкладає її
 * на окремі коробки ({@code toAabbs()}) лише коли це справді потрібно: якщо отримана форма —
 * САМЕ той самий спільний екземпляр "повний куб" ({@code Shapes.block()}), повертаємо одну готову
 * коробку на всю клітинку без розкладання. Раніше тут для цього ж рішення питався
 * {@code state.blocksMotion()} — виявилось помилкою (див. чат): цей прапорець — окрема "legacy
 * solid" властивість, не синонім "форма ≈ повний куб", і для блоків із залежною від стану формою
 * (люки, двері) лишається {@code true} незалежно від поточного стану (відкритий люк теж давав
 * {@code true}, тому форма для нього взагалі не розкладалась).
 * <p>
 * Не бере на себе {@code isPathfindable(BlockState, PathComputationType)} — окремий opt-in хук,
 * яким блок (ванільний чи модовий) сам каже "по мені не можна ходити" (сніг по висоті шарів тощо).
 * Той хук лишається за ваниллю/блоком; ми лише уточнюємо ГЕОМЕТРІЮ, коли автор блока про
 * pathfinding взагалі не думав (а форма колізії зобов'язана бути правильною для БУДЬ-ЯКОГО блока
 * будь-якого мода - інакше він не колайдиться нормально навіть із гравцем).
 */
public final class ShapeProbe {

    private ShapeProbe() {
    }

    /**
     * Мінімальна частка "сліду" мобу, яка мусить реально лежати на суцільній геометрії, щоб
     * клітинку взагалі рахувати опорою (а не проваллям). НАВМИСНО низький поріг, не "більшість
     * сліду": реальна ванільна фізика теж не вимагає повного покриття, щоб не провалитись (гравець
     * фізично балансує на горщику чи ріжку плити - лише {@code coverage>0} і потрібно). Відкритий
     * люк все одно коректно дає РІВНО {@code 0.0} (панель геометрично не перетинає footprint у
     * центрі клітинки), тож поріг лише відсікає похибку double-порівнянь, а не "замало опори".
     */
    public static final double MIN_FLOOR_COVERAGE = 0.05;

    /**
     * Те саме для "чи заважає ця клітинка тілу мобу" — поріг нижчий: навіть неширокий стовпчик
     * (паркан) чи виступ, який реально зачепить хітбокс, має рахуватись перешкодою.
     */
    public static final double MIN_BLOCKING_COVERAGE = 0.2;

    /**
     * Мінімальний запас (блоків) між знайденою опорою і стелею клітинки, щоб усередині НЕЇ ще
     * лишалось місце стояти (інакше це не "тонкий виступ із опорою", а суцільна клітинка від
     * низу до верху - тобто BLOCKED, як і раніше для звичайних блоків).
     */
    public static final double MIN_STANDING_CLEARANCE = 0.1;

    /**
     * Типова ширина мобу для перевірок без конкретного {@code Mob} під рукою (загальний ванільний
     * мексин, що працює для БУДЬ-ЯКОГО наземного моба — {@code getFloorLevel} мобо-агностичний і у
     * ваніллі). Для власного {@code GapJumpNodeEvaluator}, де {@code Mob} є, краще брати
     * {@code mob.getBbWidth()} напряму.
     */
    public static final double DEFAULT_MOB_WIDTH = 0.6;

    /**
     * Розкладає форму колізії блока на список коробок в АБСОЛЮТНИХ світових координатах.
     * Порожній список — блок геометрично прохідний наскрізь (справжнє повітря чи форма без колізії,
     * напр. квіти чи смолоскип).
     * <p>
     * <b>ВАЖЛИВО — тут НЕ використовується {@code state.blocksMotion()}</b> (раніше було, і це була
     * реальна знайдена помилка — див. чат). Цей прапорець ("legacy solid") — НЕ "форма ≈ повний куб":
     * за офіційним вікі Minecraft він "distinct from... whether the block has a collision box, is a
     * full block", і для блоків із динамічною формою (двері, люки) лишається {@code true} незалежно
     * від поточного стану. Для ВІДКРИТОГО люка це давало {@code blocksMotion()==true}, тож форма
     * взагалі не розкладалась — клітинка трактувалась як суцільний блок. Натомість звіряємо САМУ
     * отриману форму з {@link Shapes#block()} (єдиний спільний екземпляр "повний куб", яким
     * повертає {@code getCollisionShape} для дійсно повних блоків) — це робить реальний рушій
     * Minecraft для того самого питання (див. {@code VoxelShape#isFullBlock()}), тому безпечно
     * покладатись на цю саму перевірку.
     */
    public static List<Box> boxesOf(BlockGetter level, BlockPos pos) {
        List<Box> boxes = new ArrayList<>(2);
        if (level == null) {
            return boxes;
        }
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return boxes;
        }
        if (shape == Shapes.block()) {
            // дешевий і БЕЗПЕЧНИЙ шлях: САМЕ посилання на єдиний спільний "повний куб" - не
            // окремий прапорець, а звірка з тим, що ми щойно реально отримали від getCollisionShape.
            boxes.add(new Box(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0));
            return boxes;
        }
        for (AABB box : shape.toAabbs()) {
            boxes.add(new Box(
                    pos.getX() + box.minX, pos.getY() + box.minY, pos.getZ() + box.minZ,
                    pos.getX() + box.maxX, pos.getY() + box.maxY, pos.getZ() + box.maxZ));
        }
        return boxes;
    }

    /**
     * Квадрат ширини {@code width}, відцентрований по X/Z клітинки {@code cell}.
     */
    public static Footprint centeredFootprint(BlockPos cell, double width) {
        return Footprint.centered(cell.getX() + 0.5, cell.getZ() + 0.5, width);
    }

    /**
     * Реальна опора для стояння в {@code feetCell}: бере форму САМОЇ клітинки (нуб горщика/килима/
     * нижньої плити всередині неї) РАЗОМ із формою клітинки під нею (звичайна підлога/верхня плита
     * впритул) і повертає найвищу підходящу поверхню під заданим {@code footprint}-ом. Обидва
     * джерела рахуються ОДНІЄЮ геометричною задачею — жодного спеціального розбору "чи це плита/
     * горщик/килим", тому працює так само й для блоків з інших модів.
     */
    public static Support floorSupport(BlockGetter level, BlockPos feetCell, Footprint footprint) {
        if (level == null) {
            return Support.NONE;
        }
        List<Box> boxes = new ArrayList<>();
        boxes.addAll(boxesOf(level, feetCell));
        boxes.addAll(boxesOf(level, feetCell.below()));
        if (boxes.isEmpty()) {
            return Support.NONE;
        }
        return ShapeGeometry.findSupport(boxes, footprint, feetCell.getY() + 1.0);
    }

    /**
     * Чи форма клітинки {@code cell} (сама по собі, без сусідів) заважає тілу мобу там перебувати —
     * незалежно від висоти всередині клітинки. {@code level == null} (світ недоступний) — обережно
     * {@code true}, той самий принцип, що діяв і раніше.
     */
    public static boolean blocksBody(BlockGetter level, BlockPos cell, Footprint footprint) {
        if (level == null) {
            return true;
        }
        List<Box> boxes = boxesOf(level, cell);
        if (boxes.isEmpty()) {
            return false;
        }
        return ShapeGeometry.blockedFraction(boxes, footprint) >= MIN_BLOCKING_COVERAGE;
    }

    /**
     * Чи ВЛАСНА форма клітинки {@code cell} (без сусідів) тягнеться від низу клітинки щонайменше до
     * {@code MIN_STANDING_CLEARANCE} від стелі — тобто "стовпчик", що фізично заповнює майже весь
     * вертикальний діапазон, де мало б бути тіло мобу (паркан/стіна, колізія 1.5 - вища за саму
     * клітинку). {@link #floorSupport} з його стелею на рівні {@code cell.getY()+1.0} такий стовпчик
     * ВЗАГАЛІ не бачить (його верх вище за стелю) - тому це ОКРЕМА перевірка, не висновок із
     * coverage/floorSupport (інакше короткий виступ на кшталт горщика чи плити помилково теж
     * вважався б перешкодою — див. {@link ShapeGeometry#spansRange}).
     */
    public static boolean isPillarObstruction(BlockGetter level, BlockPos cell, Footprint footprint) {
        if (level == null) {
            return true;
        }
        List<Box> boxes = boxesOf(level, cell);
        if (boxes.isEmpty()) {
            return false;
        }
        double lowY = cell.getY();
        double highY = cell.getY() + 1.0 - MIN_STANDING_CLEARANCE;
        return ShapeGeometry.spansRange(boxes, footprint, lowY, highY);
    }
}
