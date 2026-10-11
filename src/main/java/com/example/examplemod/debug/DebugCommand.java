package com.example.examplemod.debug;

import com.example.examplemod.BetterEnemiesAI;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * Команда {@code /bedebug} (без вимоги прав - це інструмент розробника):
 * <ul>
 *   <li>{@code /bedebug dump} - знімок стану НАЙБЛИЖЧОГО до тебе моба (до 40 блоків) у файл логу. Роби це, коли
 *       бачиш, що моб завис/смикається, - не чекаючи сторожа;</li>
 *   <li>{@code /bedebug mark <текст>} - мітка в лозі (щоб потім знайти момент: "завис біля люка");</li>
 *   <li>{@code /bedebug on|off} - головний вимикач; {@code /bedebug watchdog on|off};
 *       {@code /bedebug floor on|off} (шумний міксин); {@code /bedebug verbose on|off} (покадровий політ);</li>
 *   <li>{@code /bedebug status} - куди пишеться файл і що ввімкнено.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BetterEnemiesAI.MODID)
public final class DebugCommand {

    private DebugCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bedebug");

        root.then(Commands.literal("dump").executes(ctx -> dump(ctx.getSource())));

        root.then(Commands.literal("mark")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String who = ctx.getSource().getTextName();
                            DebugLog.mark(who, StringArgumentType.getString(ctx, "text"));
                            reply(ctx.getSource(), "мітку записано у дебаг-лог");
                            return 1;
                        })));

        root.then(Commands.literal("status").executes(ctx -> {
            reply(ctx.getSource(), DebugLog.status());
            return 1;
        }));

        root.then(Commands.literal("on").executes(ctx -> {
            DebugLog.setEnabled(true);
            reply(ctx.getSource(), "дебаг УВІМКНЕНО (до перезапуску; конфіг не змінено)");
            return 1;
        }));
        root.then(Commands.literal("off").executes(ctx -> {
            DebugLog.setEnabled(false);
            reply(ctx.getSource(), "дебаг ВИМКНЕНО (до перезапуску; конфіг не змінено)");
            return 1;
        }));

        root.then(toggle("watchdog", DebugLog::setWatchdog, "сторож зависань"));
        root.then(toggle("floor", DebugLog::setFloorMixin, "вибірка міксина getFloorLevel (шумно)"));
        root.then(toggle("verbose", DebugLog::setVerboseJump, "покадровий вивід польоту стрибка"));

        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name,
                                                                     java.util.function.Consumer<Boolean> setter,
                                                                     String what) {
        return Commands.literal(name)
                .then(Commands.literal("on").executes(ctx -> {
                    setter.accept(true);
                    reply(ctx.getSource(), what + ": УВІМКНЕНО");
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    setter.accept(false);
                    reply(ctx.getSource(), what + ": ВИМКНЕНО");
                    return 1;
                }));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[bedebug] " + text), false);
    }

    private static int dump(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class,
                new AABB(p.x - 40, p.y - 40, p.z - 40, p.x + 40, p.y + 40, p.z + 40));
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mob m : mobs) {
            if (!m.isAlive()) {
                continue;
            }
            double d = m.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        if (best == null) {
            source.sendFailure(Component.literal("[bedebug] поблизу (40 блоків) немає живих мобів"));
            return 0;
        }
        DebugLog.mark(source.getTextName(), "/bedebug dump для " + DebugLog.tag(best));
        MobWatchdog.dumpNow(best, "РУЧНИЙ ЗНІМОК (/bedebug dump), відстань до гравця "
                + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(bestDist)) + " бл.");
        reply(source, "знімок " + DebugLog.tag(best) + " записано у дебаг-лог. " + DebugLog.status());
        return 1;
    }
}
