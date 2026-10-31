package com.example.examplemod.debug;

import com.example.examplemod.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * ЄДИНЕ місце, куди йде весь дебаг-вивід мода (замість {@code System.out.println}).
 * <p>
 * <b>Навіщо.</b> Раніше дебаг ішов у stdout: міксин {@code getFloorLevel} друкував рядок на КОЖЕН виклик
 * pathfinding-у для КОЖНОГО наземного моба - консоль (напр. IntelliJ, у якої кільцевий буфер ~1 МБ) переповнювалась,
 * і старі рядки (весь корисний дебаг стрибків) просто стирались. Тепер:
 * <ul>
 *   <li>вивід іде в ОКРЕМИЙ файл {@code logs/betterenemiesai-debug.log} (а не в консоль / latest.log), тож
 *       консоль більше не переповнюється, а зв'язний контекст зберігається;</li>
 *   <li>запис іде з окремого потоку через обмежену чергу - тік сервера не чекає на диск;</li>
 *   <li>файл має обмеження розміру (за замовчуванням 16 МБ): при перевищенні поточний файл стає
 *       {@code .prev.log}, пишеться новий - тобто завжди лишається ОСТАННІЙ шматок (саме він і цікавий);</li>
 *   <li>глобальний ліміт рядків/сек + дедуплікація/тротлінг за ключем - шумні місця не можуть затопити файл;</li>
 *   <li>категорії: шумні ({@link Cat#FLOOR}, {@link Cat#JUMP_TICK}) за замовчуванням ВИМКНЕНІ.</li>
 * </ul>
 * Керування: секція {@code Debug} у конфігу мода або команда {@code /bedebug}.
 */
public final class DebugLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockingQueue<Line> QUEUE = new ArrayBlockingQueue<>(40_000);

    // ------------------------------------------------------------------------------------------
    // Прапорці (кешуються; конфіг перечитується не частіше ніж раз на секунду)
    // ------------------------------------------------------------------------------------------
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong WRITTEN = new AtomicLong();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LINES_PER_SECOND = 250;
    private static final Map<String, Long> THROTTLE = new ConcurrentHashMap<>();
    private static final LongAdder FLOOR_CALLS = new LongAdder();
    private static final LongAdder FLOOR_NO_SUPPORT = new LongAdder();
    private static final LongAdder FLOOR_INTERESTING = new LongAdder();
    private static final LongAdder TRAPDOOR_CALLS = new LongAdder();
    private static final Map<Long, Long> FLOOR_SEEN = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > 512;
        }
    };
    private static volatile boolean enabled = true;
    private static volatile boolean watchdog = true;
    private static volatile boolean floorMixin = false;

    // ------------------------------------------------------------------------------------------
    // Запис у файл (окремий потік)
    // ------------------------------------------------------------------------------------------
    private static volatile boolean verboseJump = false;
    private static volatile long maxBytes = 16L * 1024 * 1024;
    /**
     * Після зміни командою значення з конфігу більше не перечитуються (до перезапуску).
     */
    private static volatile boolean overriddenByCommand = false;
    private static volatile long lastRefreshMs = 0L;
    private static volatile Thread writerThread;
    private static volatile Path logPath;
    /**
     * Виставляється хуком вимкнення JVM: писальний потік дописує залишок черги й виходить (без interrupt - він ламає NIO-канали).
     */
    private static volatile boolean shutdown = false;
    private static long windowStartMs = 0L;
    private static int windowCount = 0;
    private static int suppressedInWindow = 0;
    private static volatile long floorLastSummaryMs = 0L;

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 1000L) {
            return;
        }
        lastRefreshMs = now;
        if (overriddenByCommand) {
            return;
        }
        try {
            enabled = Config.DEBUG_ENABLED.get();
            watchdog = Config.DEBUG_WATCHDOG.get();
            floorMixin = Config.DEBUG_FLOOR_MIXIN.get();
            verboseJump = Config.DEBUG_VERBOSE_JUMP.get();
            maxBytes = Config.DEBUG_MAX_FILE_MB.get() * 1024L * 1024L;
        } catch (RuntimeException configNotLoadedYet) {
            // конфіг ще не завантажений (ранній виклик) - лишаються значення за замовчуванням
        }
    }

    /**
     * Дешева перевірка: чи варто взагалі будувати рядок для цієї категорії.
     */
    public static boolean on(Cat cat) {
        refreshIfNeeded();
        if (!enabled) {
            return false;
        }
        return switch (cat) {
            case FLOOR -> floorMixin;
            case JUMP_TICK -> verboseJump;
            case WATCHDOG -> watchdog;
            default -> true;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Ліміт частоти (захисний клапан) і тротлінг за ключем
    // ------------------------------------------------------------------------------------------

    public static void setEnabled(boolean value) {
        overriddenByCommand = true;
        enabled = value;
    }

    public static void setWatchdog(boolean value) {
        overriddenByCommand = true;
        watchdog = value;
    }

    public static void setFloorMixin(boolean value) {
        overriddenByCommand = true;
        floorMixin = value;
    }

    public static void setVerboseJump(boolean value) {
        overriddenByCommand = true;
        verboseJump = value;
    }

    private static void ensureStarted() {
        if (writerThread != null) {
            return;
        }
        synchronized (DebugLog.class) {
            if (writerThread != null) {
                return;
            }
            Thread t = new Thread(DebugLog::writerLoop, "BetterEnemiesAI-DebugLog");
            t.setDaemon(true);
            writerThread = t;
            t.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Thread w = writerThread;
                shutdown = true;
                if (w != null) {
                    try {
                        w.join(1500L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "BetterEnemiesAI-DebugLog-flush"));
        }
    }

    private static BufferedWriter open(Path dir, Path file, Path prev) throws IOException {
        Files.createDirectories(dir);
        if (Files.exists(file)) {
            try {
                Files.move(file, prev, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException cannotRotate) {
                // не вдалось перейменувати (файл зайнятий) - просто перезапишемо
            }
        }
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        logPath = file.toAbsolutePath();
        return w;
    }

    private static void writerLoop() {
        Path dir = Paths.get("logs");
        Path file = dir.resolve("betterenemiesai-debug.log");
        Path prev = dir.resolve("betterenemiesai-debug.prev.log");
        BufferedWriter w;
        long bytes = 0L;
        try {
            w = open(dir, file, prev);
            LOGGER.info("[BetterEnemiesAI] debug log -> {}", logPath);
            String head = "==== BetterEnemiesAI debug log, початок сесії ====\n";
            w.write(head);
            bytes += head.length();
        } catch (IOException e) {
            LOGGER.error("[BetterEnemiesAI] не вдалось відкрити debug-лог, дебаг у файл вимкнено", e);
            enabled = false;
            overriddenByCommand = true;
            return;
        }
        try {
            while (true) {
                Line line;
                try {
                    line = QUEUE.poll(250L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    shutdown = true;
                    line = null;
                }
                if (line == null) {
                    w.flush();
                    if (shutdown) {
                        Line rest;
                        while ((rest = QUEUE.poll()) != null) {
                            bytes += writeLine(w, rest);
                        }
                        w.flush();
                        break;
                    }
                    continue;
                }
                bytes += writeLine(w, line);
                WRITTEN.incrementAndGet();
                long dropped = DROPPED.getAndSet(0L);
                if (dropped > 0L) {
                    bytes += writeLine(w, new Line(System.currentTimeMillis(),
                            "[SYSTEM] !! черга запису переповнилась, втрачено рядків: " + dropped));
                }
                if (bytes > maxBytes) {
                    w.flush();
                    w.close();
                    w = open(dir, file, prev);
                    bytes = writeLine(w, new Line(System.currentTimeMillis(),
                            "[SYSTEM] файл досяг ліміту розміру, попередня частина тепер у " + prev.getFileName()));
                }
            }
        } catch (IOException e) {
            LOGGER.error("[BetterEnemiesAI] помилка запису debug-логу, дебаг у файл вимкнено", e);
            enabled = false;
            overriddenByCommand = true;
        } finally {
            try {
                w.flush();
                w.close();
            } catch (IOException ignored) {
                // нічого не вдієш
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Публічний API логування
    // ------------------------------------------------------------------------------------------

    private static long writeLine(BufferedWriter w, Line line) throws IOException {
        String ts = Instant.ofEpochMilli(line.ms()).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME);
        String out = ts + " " + line.text() + "\n";
        w.write(out);
        return out.length();
    }

    private static void enqueue(String text) {
        ensureStarted();
        if (!QUEUE.offer(new Line(System.currentTimeMillis(), text))) {
            DROPPED.incrementAndGet();
        }
    }

    private static synchronized boolean allowByRate() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 1000L) {
            if (suppressedInWindow > 0) {
                enqueue("[SYSTEM] ліміт частоти: пропущено " + suppressedInWindow + " рядків за попередню секунду");
            }
            windowStartMs = now;
            windowCount = 0;
            suppressedInWindow = 0;
        }
        if (windowCount >= MAX_LINES_PER_SECOND) {
            suppressedInWindow++;
            return false;
        }
        windowCount++;
        return true;
    }

    /**
     * {@code true}, якщо для цього ключа минуло щонайменше {@code minIntervalMs} від останнього дозволу.
     */
    public static boolean throttle(String key, long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (THROTTLE.size() > 4096) {
            THROTTLE.clear();
        }
        Long last = THROTTLE.get(key);
        if (last != null && now - last < minIntervalMs) {
            return false;
        }
        THROTTLE.put(key, now);
        return true;
    }

    /**
     * Короткий ідентифікатор моба для рядків логу: {@code zombie#123}.
     */
    public static String tag(Mob mob) {
        if (mob == null) {
            return "-";
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath() + "#" + mob.getId();
    }

    public static void log(Cat cat, String msg) {
        if (!on(cat) || !allowByRate()) {
            return;
        }
        enqueue("[" + cat + "] " + msg);
    }

    // ------------------------------------------------------------------------------------------
    // Дзеркало чату
    // ------------------------------------------------------------------------------------------

    public static void log(Cat cat, Mob mob, String msg) {
        if (!on(cat) || !allowByRate()) {
            return;
        }
        enqueue("[" + cat + "] " + tag(mob) + " t=" + (mob == null ? -1 : mob.tickCount) + " | " + msg);
    }

    /**
     * Рядок будується ЛИШЕ якщо категорія ввімкнена й тротлінг дозволяє.
     */
    public static void logThrottled(Cat cat, Mob mob, String key, long minIntervalMs, Supplier<String> msg) {
        if (!on(cat) || !throttle(key, minIntervalMs)) {
            return;
        }
        log(cat, mob, msg.get());
    }

    /**
     * Багаторядковий блок (знімок стану): минає ліміт частоти, пишеться одним шматком, щоб не перемішатись з
     * іншими рядками.
     */
    public static void forceBlock(String text) {
        if (!enabled) {
            return;
        }
        enqueue(text);
    }

    /**
     * Мітка в лозі, коли користувач бачить проблему: {@code /bedebug mark текст}.
     */
    public static void mark(String who, String text) {
        enqueue("[SYSTEM] ########## МІТКА від " + who + ": " + text + " ##########");
    }

    // ------------------------------------------------------------------------------------------
    // Вибірка міксина getFloorLevel / getPathTypeFromState (замість println на кожен виклик)
    // ------------------------------------------------------------------------------------------

    /**
     * Викликається з {@code PursuitEnemyBehavior.debugMsg}: дублює повідомлення в файл і каже, чи слати його в
     * чат. Однакові повідомлення частіше ніж раз на секунду в чат не йдуть (раніше, напр.,
     * {@code [DEBUG findNearestOpenArea]} друкувався щотіку й топив і чат, і latest.log).
     * Якщо дебаг вимкнено - завжди {@code true}, тобто стара поведінка.
     */
    public static boolean chat(String player, String msg) {
        if (!chatAllowed(msg)) {
            return false;
        }
        if (on(Cat.CHAT)) {
            log(Cat.CHAT, "чат->" + player + ": " + msg);
        }
        return true;
    }

    /**
     * Чи слати це повідомлення в чат (однакові - не частіше разу на секунду). Дебаг вимкнено -> завжди так.
     */
    public static boolean chatAllowed(String msg) {
        if (!on(Cat.CHAT)) {
            return true;
        }
        return throttle("chat:" + msgKey(msg), msgInterval(msg));
    }

    /**
     * Шумні повідомлення (findNearestOpenArea, isNavigationBlocked, PATH DEBUG) щоразу різні за текстом
     * (координати, лічильники), тож дедуплікація за повним текстом їх не ловила. Для них ключ - лише тег у
     * квадратних дужках, тобто не частіше ~1.5с на тип. Решта повідомлень дедуплікується за повним текстом.
     */
    public static String msgKey(String msg) {
        if (msg.startsWith("[DEBUG findNearestOpenArea]") || msg.startsWith("[DEBUG isNavigationBlocked]")
                || msg.startsWith("[PATH DEBUG]")) {
            return msg.substring(0, msg.indexOf(']') + 1);
        }
        return msg;
    }

    public static long msgInterval(String msg) {
        return msgKey(msg).length() != msg.length() ? 1500L : 1000L;
    }

    private static boolean floorFirstTimeRecently(long key, long windowMs) {
        long now = System.currentTimeMillis();
        synchronized (FLOOR_SEEN) {
            Long last = FLOOR_SEEN.get(key);
            if (last != null && now - last < windowMs) {
                return false;
            }
            FLOOR_SEEN.put(key, now);
            return true;
        }
    }

    private static void floorSummaryIfDue() {
        long now = System.currentTimeMillis();
        if (now - floorLastSummaryMs < 10_000L) {
            return;
        }
        floorLastSummaryMs = now;
        log(Cat.FLOOR, "підсумок за ~10с: getFloorLevel викликів=" + FLOOR_CALLS.sumThenReset()
                + ", без опори=" + FLOOR_NO_SUPPORT.sumThenReset()
                + " (з них 'цікавих', де в клітинці/під нею щось є, а опори нема: " + FLOOR_INTERESTING.sumThenReset() + ")"
                + ", getPathTypeFromState(люк)=" + TRAPDOOR_CALLS.sumThenReset());
    }

    /**
     * Міксин getFloorLevel: порахувати виклик. Викликати лише під {@code on(Cat.FLOOR)}.
     */
    public static void floorCall() {
        FLOOR_CALLS.increment();
        floorSummaryIfDue();
    }

    /**
     * Міксин getFloorLevel: опори нема. Повітря над повітрям (переважна більшість викликів) НЕ друкується
     * взагалі - лише рахується; клітинки з реальним блоком, що не дає опори (відкритий люк тощо), друкуються
     * один раз на ~5с на позицію.
     */
    public static void floorNoSupport(BlockGetter level, BlockPos pos, double vanillaAnswer, boolean shapeAwareEnabled,
                                      double coverage) {
        FLOOR_NO_SUPPORT.increment();
        BlockState here = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());
        if (here.isAir() && below.isAir()) {
            return;
        }
        FLOOR_INTERESTING.increment();
        long key = pos.asLong() * 31L + System.identityHashCode(here) * 17L + System.identityHashCode(below);
        if (!floorFirstTimeRecently(key, 5000L)) {
            return;
        }
        log(Cat.FLOOR, "getFloorLevel NO-SUPPORT pos=" + pos.toShortString()
                + " here=" + here + " below=" + below
                + " vanilla=" + vanillaAnswer + " shapeAware=" + shapeAwareEnabled
                + " coverage=" + String.format(java.util.Locale.ROOT, "%.3f", coverage));
    }

    /**
     * Міксин getPathTypeFromState: ванільна відповідь вже "люк". Один рядок на ~5с на позицію й стан.
     */
    public static void floorTrapdoor(BlockGetter level, BlockPos pos, Object vanillaType, boolean shapeAwareEnabled,
                                     double coverage, double surfaceY, boolean overriddenToBlocked) {
        TRAPDOOR_CALLS.increment();
        BlockState state = level.getBlockState(pos);
        long key = pos.asLong() * 31L + System.identityHashCode(state) * 17L + (overriddenToBlocked ? 1 : 0);
        if (!floorFirstTimeRecently(key, 5000L)) {
            return;
        }
        log(Cat.FLOOR, "getPathTypeFromState pos=" + pos.toShortString() + " state=" + state
                + " vanillaType=" + vanillaType + " shapeAware=" + shapeAwareEnabled
                + " coverage=" + String.format(java.util.Locale.ROOT, "%.3f", coverage)
                + " surfaceY=" + String.format(java.util.Locale.ROOT, "%.3f", surfaceY)
                + " ->BLOCKED=" + overriddenToBlocked);
    }

    public static String status() {
        refreshIfNeeded();
        return "debug=" + enabled + " watchdog=" + watchdog + " floorMixin=" + floorMixin
                + " verboseJump=" + verboseJump + " файл=" + (logPath == null ? "(ще не створено)" : logPath)
                + " записано=" + WRITTEN.get() + " у_черзі=" + QUEUE.size();
    }

    /**
     * Категорії виводу. Префікс у файлі = назва категорії.
     */
    public enum Cat {
        /**
         * службові повідомлення самого логера, команди, мітки.
         */
        SYSTEM,
        /**
         * події стрибків (старт/відрив/приземлення/зупинка), без покадрового шуму.
         */
        JUMP,
        /**
         * покадровий шум стрибків (політ щотіку). Вимкнено за замовчуванням.
         */
        JUMP_TICK,
        /**
         * побудований шлях (вузли, стрибкові сегменти, вузькі точки).
         */
        PATH,
        /**
         * будівництво/копання: рішення поставити блок і результат.
         */
        BUILD,
        /**
         * навігатор: досягнення вузлів, гальмо на краю.
         */
        NAV,
        /**
         * сторож зависань/смикання, знімки стану.
         */
        WATCHDOG,
        /**
         * міксин getFloorLevel / getPathTypeFromState. Дуже шумний, вимкнено за замовчуванням.
         */
        FLOOR,
        /**
         * дзеркало повідомлень, що йдуть у чат гравцю.
         */
        CHAT,
        /**
         * справжній граф сусідів evaluator-а на стартовому вузлі (GraphProbe).
         */
        GRAPH,
        /**
         * падіння: профіль моба (HP/чари/ефекти), нижній предел, кандидати стрибка вниз і їхня шкода, спуски в шляху (СТРИБОК чи
         * ПІШКИ-ЗРИВ), відрив/приземлення з прогнозом проти факту, будь-яке падіння від 2 блоків (див. FallDebug).
         */
        FALL
    }

    // ------------------------------------------------------------------------------------------
    // Стан для команди /bedebug status
    // ------------------------------------------------------------------------------------------

    private record Line(long ms, String text) {
    }
}
