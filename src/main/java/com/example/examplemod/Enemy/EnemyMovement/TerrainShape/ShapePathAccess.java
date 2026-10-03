package com.example.examplemod.Enemy.EnemyMovement.TerrainShape;

import net.minecraft.world.phys.Vec3;

/**
 * Інтерфейс, який {@code PathMixin} домішує до ванільного {@code Path}: точні точки, у які моб
 * має прийти на кожному вузлі (замість центру клітинки), і прапорці «вузька» точка (край/кут).
 * Масиви однакової довжини з кількістю вузлів шляху.
 */
public interface ShapePathAccess {

    void betterEnemies$setWaypoints(Vec3[] spots, boolean[] tight);

    Vec3[] betterEnemies$spots();

    boolean[] betterEnemies$tight();
}
