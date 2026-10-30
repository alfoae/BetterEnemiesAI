package com.example.examplemod.event;

import com.example.examplemod.BetterEnemiesAI;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public class ModEntityEvents {

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        // Працюємо тільки на сервері
        if (event.getLevel().isClientSide()) {
        }
    }
}