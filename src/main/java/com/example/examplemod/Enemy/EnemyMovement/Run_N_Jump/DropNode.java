package com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump;

import net.minecraft.world.level.pathfinder.Node;

/**
 * v23: ПОЗНАЧКА ребра спуску "зійти з краю й упасти" на сусідню клітинку (відстань до 1.5), яке має виконувати RNJ
 * ({@code GapJumpAssistGoal}: розбіг, збіг з краю на потрібній швидкості, корекція в польоті), а не звичайна ходьба: падіння від
 * кількох блоків, де важливі швидкість схода, шкода й влучання в м'яке приземлення (вода, сіно, драбина).
 * <p>
 * Те саме, що {@code HopNode} для "гопа": {@link Node} не вміє нести "тип ребра", а у побудованому шляху сусідні вузли за
 * відстанню не відрізнити від кроку. {@code GapJumpUtils#findJumpSegmentInPath} розпізнає такий сегмент за {@code instanceof},
 * а {@code ShapeAwareNodeEvaluator#refine} не вважає ребро до нього ходьбою.
 */
public final class DropNode extends Node {

    /**
     * v24: приземлення в м'яку клітинку (вода, сіно, драбина...) - щоб пріоритет м'якого не штрафував сам такий вузол.
     */
    public boolean soft;

    public DropNode(int x, int y, int z) {
        super(x, y, z);
    }
}
