package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import net.minecraft.world.level.pathfinder.Node;

/**
 * Вузол-"ГОП": приземлення на СУСІДНЮ клітинку (відстань 1), у яку пішки не пройти.
 * <p>
 * Типовий випадок - відкриті люки: опора кожного - вузька смужка 0.19 на краю клітинки, між смужками 0.81
 * порожнього, а хітбокс моба має лише 0.6, тож по землі між сусідніми смужками (або між смужкою й поставленим
 * блоком) пройти неможливо: посередині є ділянка без опори. Звичайні стрибкові ребра ({@code GapJumpNodeEvaluator})
 * починаються лише від відстані 2 клітини ({@code GapJumpRays.MIN_JUMP_DISTANCE = 1.5}), тож цього кроку не було
 * в графі взагалі.
 * <p>
 * Це ПОЗНАЧКА: сам {@link Node} не вміє нести "тип ребра", а відстань 1 за порогом 1.5 стрибком не вважається.
 * Тому {@code GapJumpUtils.findJumpSegmentInPath} розпізнає такий сегмент за {@code instanceof HopNode}, а
 * {@code ShapeAwareNodeEvaluator.refine} не вважає ребро до нього ходьбою.
 */
public final class HopNode extends Node {

    public HopNode(int x, int y, int z) {
        super(x, y, z);
    }
}
