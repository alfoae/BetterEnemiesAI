package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

/**
 * Розподіл обмеженого буфера сусідів між групами стрибкових кандидатів - БЕЗ Minecraft-типів (чиста логіка, тому її
 * можна перевірити окремо від гри).
 * <p>
 * <b>Навіщо.</b> Ванільний {@code PathFinder} віддає evaluator-у масив сусідів на 32 вузли. {@link GapJumpNodeEvaluator}
 * сканує стрибки для кожного Δy окремо (від -{@code JUMP_DOWN_LIMIT_BLOCKS} до +{@code JUMP_UP_LIMIT_BLOCKS}). Раніше всі
 * Δy писали в ОДИН буфер за зростанням Δy, тож перша ж група з десятками кандидатів забирала всі слоти, а наступні не
 * отримували нічого. Реальний випадок (див. лог зонду графа): одиночний блок {@code B} на 1 вище великої платформи
 * {@code A} - посадок на неї вниз (Δy=-1) понад 32, вони займали весь буфер, і цикл ніколи не доходив до Δy=+1, де
 * лежало ребро на наступну сходинку {@code ▣▢▢▢▣▢▢▢▣}. У графі з {@code B} не було жодного стрибка вгору: A* не бачив
 * продовження, ланцюжок стрибків обривався на приземленні, і моб лишався стояти на краю.
 * <p>
 * <b>Правило.</b> Якщо кандидати вміщуються - усе як і раніше (за зростанням Δy, у порядку променів), нічого не
 * змінюється. Якщо ні - місця діляться ПОРІВНО між групами по колу, а що не взяла мала група, дістається більшим; усередині
 * групи лишаються найближчі промені (вони й так відсортовані першими). Тобто жодна непорожня група не лишається без
 * місця, поки є хоч слот.
 */
final class NeighborBudget {

    private NeighborBudget() {
    }

    /**
     * Скільки кандидатів узяти з кожної групи, щоб разом було не більше {@code room}.
     * <p>
     * Усе вміщується - беремо все. Інакше видаємо по одному з кожної непорожньої групи по колу (починаючи з групи з
     * найбільшим номером, тобто з найвищого Δy: стрибки вгору рідкісніші й не мають лишатись без слота), поки не
     * закінчиться місце.
     *
     * @param sizes скільки кандидатів знайдено в кожній групі (за зростанням Δy)
     * @param room  скільки слотів лишилось у буфері
     * @return скільки взяти з кожної групи (той самий порядок, що й у {@code sizes})
     */
    static int[] share(int[] sizes, int room) {
        int[] take = new int[sizes.length];
        if (room <= 0) {
            return take;
        }
        int total = 0;
        for (int size : sizes) {
            total += size;
        }
        if (total <= room) {
            System.arraycopy(sizes, 0, take, 0, sizes.length);
            return take;
        }
        int placed = 0;
        boolean progress = true;
        while (placed < room && progress) {
            progress = false;
            for (int i = sizes.length - 1; i >= 0 && placed < room; i--) {
                if (take[i] < sizes[i]) {
                    take[i]++;
                    placed++;
                    progress = true;
                }
            }
        }
        return take;
    }

    /**
     * Дописує кандидатів у {@code out} після {@code count} уже заповнених слотів (звичайні кроки) за правилом
     * {@link #share}. Порядок у виході - за зростанням групи, усередині групи - як у {@code buckets}.
     *
     * @param buckets кандидати по групах (елемент може бути {@code null}, якщо група порожня); у кожній групі
     *                значущі лише перші {@code sizes[i]} елементів
     * @param sizes   скільки кандидатів у кожній групі
     * @param out     буфер сусідів PathFinder
     * @param count   скільки слотів {@code out} уже зайнято
     * @return нова кількість заповнених слотів
     */
    static <T> int merge(T[][] buckets, int[] sizes, T[] out, int count) {
        int[] take = share(sizes, out.length - count);
        for (int i = 0; i < buckets.length; i++) {
            T[] bucket = buckets[i];
            if (bucket == null) {
                continue;
            }
            for (int j = 0; j < take[i]; j++) {
                out[count++] = bucket[j];
            }
        }
        return count;
    }
}
