package com.example.examplemod.Enemy.EnemyMovement.SafeFall;

import com.example.examplemod.Enemy.EnemyMovement.Run_N_Jump.GapJumpUtils;

/**
 * v26: позначка ребра "СПУСК ВИСТУПАМИ" ({@link DropNode#ledge} у графі, {@link GapJumpUtils.GapJump#ledge()} у сегменті).
 * Саму послідовність виступів вузол не несе: виконавець ({@link LedgeDescent}) перераховує її з краю тим самим
 * детермінованим {@link LedgePlanner}, тож достатньо першого збігу.
 *
 * @param dx      зсув колонки першого збігу від клітинки краю по X (-1..1)
 * @param dz      зсув колонки першого збігу від клітинки краю по Z (-1..1; разом з dx не обидва 0)
 * @param ledges  скільки виступів у плані (для ціни ребра й логів)
 */
public record LedgeHint(int dx, int dz, int ledges) {
}
