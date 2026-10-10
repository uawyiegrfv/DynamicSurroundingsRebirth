package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import org.orecruncher.dsurround.processing.FoliageSnowHandler;
import org.orecruncher.dsurround.processing.weather.PrecipitationResponse;

/**
 * {@code /dsdebug foliage} - plant-layer diagnostics, trimmed to what this
 * version can actually drive.
 *
 * <p>1.20.1's version also reports the plant DRIP and DEW layers and prints the
 * scan histogram; those depend on {@code FoliageScan}'s histogram and on a
 * {@code DEBUG_FORCE} flag in the drip and dew handlers, none of which exist
 * here. Rather than port all of that, this carries the part that matters for
 * testing: <b>the snow layer</b>, which is the one that otherwise cannot be
 * seen at all without waiting for a cold biome that happens to be snowing.</p>
 *
 * <p>1.20.1's own handler says as much: "this command exists on 1.20.1 only -
 * the other two versions have no foliage diagnostics yet, which is why the snow
 * layer also answers to the config switch". Now it answers to this too.</p>
 */
class FoliageCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsdebug";
    private static final String FOLIAGE = "foliage";
    private static final String SNOW = "snow";
    private static final String UNITS = "units";
    private static final String FORCE = "force";

    public void register(CommandDispatcher<ClientCommandRegistrationEvent.ClientCommandSourceStack> dispatcher,
                         CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommandRegistrationEvent.literal(COMMAND)
                .then(ClientCommandRegistrationEvent.literal(FOLIAGE)
                        .executes(ctx -> this.execute(ctx, FoliageCommand::snowStatus))
                        .then(ClientCommandRegistrationEvent.literal(SNOW)
                                .executes(ctx -> this.execute(ctx, FoliageCommand::snowStatus))
                                .then(RequiredArgumentBuilder
                                        .<ClientCommandRegistrationEvent.ClientCommandSourceStack, Integer>argument(
                                                UNITS, IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> this.execute(ctx, () -> snowSet(
                                                IntegerArgumentType.getInteger(ctx, UNITS))))))
                        .then(ClientCommandRegistrationEvent.literal(FORCE)
                                .executes(ctx -> this.execute(ctx, FoliageCommand::toggleForce)))
                )
        );
    }

    /** Snow-layer state: depth, the forced flag, and how much is placed. */
    private static Component snowStatus() {
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow == null)
            return Component.literal("[foliage] 雪层处理器未实例化（DI 接线没跑？）");
        final var depth = PrecipitationResponse.FOLIAGE_SNOW;
        return Component.literal(String.format(
                "[foliage-snow] 深度=%.2f%s 强制=%s 每株上限=%d 植物=%d 单元=%d",
                depth.value(),
                depth.isLocked() ? "(已锁定)" : "(跟随降雪时钟)",
                FoliageSnowHandler.DEBUG_FORCE,
                FoliageSnowHandler.DEBUG_UNITS > 0 ? FoliageSnowHandler.DEBUG_UNITS : 16,
                snow.plantCount(), snow.unitCount()));
    }

    /** Set the per-plant unit cap; turns the forced gate on and rescans now. */
    private static Component snowSet(final int units) {
        FoliageSnowHandler.DEBUG_UNITS = Math.max(1, Math.min(64, units));
        FoliageSnowHandler.DEBUG_FORCE = true;
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow != null)
            snow.forceRescan();
        return Component.literal("[foliage-snow] 每株单元上限=" + FoliageSnowHandler.DEBUG_UNITS
                + "，强制开，已重扫。关闭: /dsdebug foliage force");
    }

    /** Flips the forced gate. Snow only - drip and dew have no such flag here. */
    private static Component toggleForce() {
        final boolean on = !FoliageSnowHandler.DEBUG_FORCE;
        FoliageSnowHandler.DEBUG_FORCE = on;
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow != null)
            snow.forceRescan();
        return Component.literal("[foliage-snow] 强制模式 " + (on ? "开（已重扫）" : "关"));
    }
}
