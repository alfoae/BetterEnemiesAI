package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import java.util.List;

/**
 * Джерело коробок колізії у світових координатах — БЕЗ Minecraft-типів, щоб {@link ShapeWalk} і
 * {@link WaypointPlanner} можна було тестувати окремо від гри. Реальна реалізація —
 * {@code ShapeProbe.CachedSource}, у тестах — просто список коробок.
 */
public interface BoxSource {

    /**
     * Додає в {@code out} усі коробки з клітинок, які перетинають заданий паралелепіпед
     * (клітинки, до яких паралелепіпед лише ДОТИКАЄТЬСЯ межею, не беруться).
     */
    void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                 List<ShapeGeometry.Box> out);
}
