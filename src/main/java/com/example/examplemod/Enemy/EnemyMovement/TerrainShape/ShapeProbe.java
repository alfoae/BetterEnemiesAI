package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Box;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Footprint;
import com.example.examplemod.Enemy.EnemyMovement.TerrainShape.ShapeGeometry.Support;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

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

    /**
     * N, S, W, E - у цьому порядку перевіряються як кандидати, коли центр клітинки не підійшов.
     */
    private static final int[][] EDGE_DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    /**
     * Квадрат заданої ширини, притиснутий до однієї зі стінок клітинки {@code cell}
     * ({@code dirX}/{@code dirZ} - рівно одне ненульове, з {-1,0,1}: північ/південь/захід/схід).
     * Зсув від центру рахується з ширини мобу (не фіксована константа), тому коректний для мобів
     * будь-якого розміру.
     */
    public static Footprint edgeFootprint(BlockPos cell, double width, int dirX, int dirZ) {
        double half = Math.max(0.0, 0.5 - width / 2.0);
        double cx = cell.getX() + 0.5 + dirX * half;
        double cz = cell.getZ() + 0.5 + dirZ * half;
        return Footprint.centered(cx, cz, width);
    }

    /**
     * Чи {@code column} прохідна для ЦЬОГО КОНКРЕТНОГО footprint-у: не "стовпчик" (паркан і подібне)
     * і є реальна опора з достатнім просвітом до стелі клітинки. Єдине місце, де поєднуються обидві
     * перевірки - і для центру, і для країв (нижче), щоб вони завжди узгоджувались між собою.
     */
    public static boolean isWalkableAt(BlockGetter level, BlockPos column, Footprint footprint) {
        if (isPillarObstruction(level, column, footprint)) {
            return false;
        }
        Support support = floorSupport(level, column, footprint);
        if (support.coverage() < MIN_FLOOR_COVERAGE) {
            return false;
        }
        double clearance = (column.getY() + 1.0) - support.surfaceY();
        return clearance >= MIN_STANDING_CLEARANCE;
    }

    /**
     * Опора в {@code column} з урахуванням краю: спершу центр клітинки (звичайний випадок), а якщо
     * там опори нема - по черзі 4 позиції впритул до стінок (N,S,W,E). Повертає ПЕРШИЙ footprint,
     * що підходить, разом із опорою на ньому - {@code null}, якщо не підходить жоден (справжнє
     * провалля з усіх боків). Жодного знання про конкретний тип блока (люк чи інше) - чиста
     * геометрія, тому однаково працює для ванільних блоків і блоків із будь-якого мода.
     * <p>
     * ЄДИНЕ місце, де рахується ця перевірка - і загальний мексин ({@code getFloorLevel},
     * {@code getPathTypeFromState}), і {@code GapJumpNodeEvaluator} кличуть САМЕ цей метод, тому
     * обидва розумнішають одночасно, без дублювання логіки.
     * <p>
     * Приклад: відкритий люк, притиснутий до стінки - вертикальна панель на ВСЮ висоту клітинки
     * (розвернулась із горизонтального стану на всю ширину клітинки). По центру - порожньо
     * (coverage=0). На краю, де сама панель - опора знайдеться РІВНО на рівні стелі клітинки (той
     * самий рівень, що й сусідні суцільні блоки) - моб іде по верхньому ребру панелі, не спускається
     * в заглибину.
     */
    public static StandSpot resolveStandSpot(BlockGetter level, BlockPos column, double width) {
        Footprint center = centeredFootprint(column, width);
        if (isWalkableAt(level, column, center)) {
            return new StandSpot(center, floorSupport(level, column, center));
        }
        for (int[] dir : EDGE_DIRECTIONS) {
            Footprint edge = edgeFootprint(column, width, dir[0], dir[1]);
            if (isWalkableAt(level, column, edge)) {
                return new StandSpot(edge, floorSupport(level, column, edge));
            }
        }
        return null;
    }

    /**
     * Чи тримає хоч щось (покриття ≥ мінімуму) моба ШИРИНИ {@code width}, якщо його центр буде в
     * {@code (x, z)}, а ноги на висоті {@code feetY} — у межах кроку вгору/вниз 0.6. Використовується як
     * «гальмо» на краю: якщо наступна позиція без опори — вбиваємо горизонтальну швидкість.
     */
    public static boolean hasStandingSupport(BlockGetter level, double x, double z, double feetY, double width) {
        CachedSource src = new CachedSource(level, state -> false);
        Footprint fp = Footprint.centered(x, z, Math.max(0.01, width - 2.0 * ShapeWalk.SKIN));
        List<Box> list = new ArrayList<>(8);
        src.collect(fp.minX(), feetY - 1.0, fp.minZ(), fp.maxX(), feetY + 0.7, fp.maxZ(), list);
        Support s = ShapeGeometry.findSupport(list, fp, feetY + 0.6 + 1.0E-3, MIN_FLOOR_COVERAGE);
        return s.isPresent() && s.surfaceY() >= feetY - 0.6;
    }

    // ------------------------------------------------------------------------------------------
    // Адаптер до BoxSource (для ShapeWalk / WaypointPlanner) + кеш на один пошук шляху
    // ------------------------------------------------------------------------------------------

    /**
     * Результат {@link #resolveStandSpot}: на якому САМЕ footprint-і (центр чи край) знайшлась опора.
     */
    public record StandSpot(Footprint footprint, Support support) {
    }

    /**
     * Реалізація {@link BoxSource} над реальним світом із кешем «клітинка → коробки» і «клітинка →
     * нерегулярна форма». Живе рівно один пошук шляху (створюється в {@code prepare} evaluator-а), тому
     * ніколи не показує застарілий світ. Не потокобезпечна — pathfinding у 1.21.1 іде в головному потоці.
     *
     */
    public static final class CachedSource implements BoxSource {

        private final BlockGetter level;
        private final Predicate<BlockState> ignore;
        private final Long2ObjectOpenHashMap<List<Box>> boxes = new Long2ObjectOpenHashMap<>();
        /**
         * 0 — ще не питали, 1 — регулярна (порожньо / повний куб), 2 — нерегулярна форма.
         */
        private final Long2ByteOpenHashMap irregular = new Long2ByteOpenHashMap();

        public CachedSource(BlockGetter level, Predicate<BlockState> ignore) {
            this.level = level;
            this.ignore = ignore;
        }

        /**
         * Коробки однієї клітинки (кешовані). Не змінювати повернений список.
         */
        public List<Box> cell(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            List<Box> cached = boxes.get(key);
            if (cached != null) {
                return cached;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            List<Box> result;
            if (state.isAir() || ignore.test(state)) {
                result = Collections.emptyList();
            } else {
                result = boxesOf(level, pos);
            }
            boxes.put(key, result);
            return result;
        }

        /**
         * Чи має клітинка «нерегулярну» колізію: не порожню і не повний куб (плита, сходи, люк, паркан,
         * килим, горщик, двері…). Для регулярних клітинок ванільний pathfinding правий — і ми його не чіпаємо.
         */
        public boolean isIrregular(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            byte known = irregular.get(key);
            if (known != 0) {
                return known == 2;
            }
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            boolean irr = false;
            if (!state.isAir() && !ignore.test(state)) {
                VoxelShape shape = state.getCollisionShape(level, pos);
                irr = !shape.isEmpty() && shape != Shapes.block();
            }
            irregular.put(key, (byte) (irr ? 2 : 1));
            return irr;
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            List<Box> out) {
            int x0 = (int) Math.floor(minX);
            int y0 = (int) Math.floor(minY);
            int z0 = (int) Math.floor(minZ);
            int x1 = Math.max(x0, (int) Math.ceil(maxX) - 1);
            int y1 = Math.max(y0, (int) Math.ceil(maxY) - 1);
            int z1 = Math.max(z0, (int) Math.ceil(maxZ) - 1);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        out.addAll(cell(x, y, z));
                    }
                }
            }
        }
    }
}
