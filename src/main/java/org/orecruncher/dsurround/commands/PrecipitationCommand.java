package org.orecruncher.dsurround.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.processing.weather.PrecipitationRenderer;
import org.orecruncher.dsurround.processing.weather.SkyEventScheduler;

/**
 * Diagnostics for the graded precipitation system.
 *
 * <p>There are two locks and they answer different questions.</p>
 *
 * <p><b>Locking an intensity</b> - {@code /dsprecip lock 0.5} - removes the
 * roll. Intensity is drawn once per rain event, so watching real weather means
 * waiting for the roll to land where you want to look, and the roll has spread,
 * so two consecutive storms differ for reasons that have nothing to do with
 * biome or season. A lock holds one level frame after frame, and draws even
 * when the world is not raining.</p>
 *
 * <p><b>Locking a channel</b> - {@code /dsprecip lock sky 0.5} - removes the
 * curve. The channels are what actually reach the renderer, so pinning one is
 * the only way to look at a single effect in isolation. Compare two locked
 * channels at the same intensity to see whether they agree; if they do not,
 * that is a real disagreement and not a matter of taste.</p>
 *
 * <p>Locks take effect immediately and do not ease. A lock that faded into
 * place would be indistinguishable from a channel that is merely slow, which is
 * precisely the confusion a lock exists to remove.</p>
 *
 * <p>Channels: sky, cloud, fog, fogColor, skyLight, sun, storm, geometry,
 * surface, audio, splash, wet, drip, overcast, clear. The last two are the
 * dry-weather sky events - locking one holds the event's channel so the
 * look can be inspected without waiting for a roll.</p>
 */
class PrecipitationCommand extends AbstractClientCommand {

    private static final String COMMAND = "dsprecip";
    private static final String LEVEL_PARAMETER = "level";
    private static final String CHANNEL_PARAMETER = "channel";
    private static final String EVENT_PARAMETER = "event";

    public void register(CommandDispatcher<ClientCommandRegistrationEvent.ClientCommandSourceStack> dispatcher,
                         CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommandRegistrationEvent.literal(COMMAND)
                .executes(this::status)
                .then(ClientCommandRegistrationEvent.literal("lock")
                        .then(ClientCommandRegistrationEvent
                                .argument(LEVEL_PARAMETER, FloatArgumentType.floatArg(0F, 1F))
                                .executes(this::lockIntensity))
                        .then(ClientCommandRegistrationEvent
                                .argument(CHANNEL_PARAMETER, StringArgumentType.word())
                                .then(ClientCommandRegistrationEvent
                                        .argument(LEVEL_PARAMETER, FloatArgumentType.floatArg(0F, 1F))
                                        .executes(this::lockChannel))))
                .then(ClientCommandRegistrationEvent.literal("unlock")
                        .executes(this::unlockAll)
                        .then(ClientCommandRegistrationEvent
                                .argument(CHANNEL_PARAMETER, StringArgumentType.word())
                                .executes(this::unlockChannel)))
                .then(ClientCommandRegistrationEvent.literal("event")
                        .executes(this::eventStatus)
                        .then(ClientCommandRegistrationEvent
                                .argument(EVENT_PARAMETER, StringArgumentType.word())
                                .executes(this::eventControl))));
    }

    private int status(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        return this.execute(ctx, () -> Component.literal(PrecipitationIntensity.diagnosticText()));
    }

    private int lockIntensity(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        float value = FloatArgumentType.getFloat(ctx, LEVEL_PARAMETER);
        return this.execute(ctx, () -> {
            PrecipitationIntensity.force(value);
            return Component.literal("Precipitation locked to %.2f (%s) - /dsprecip unlock to release"
                    .formatted(value, PrecipitationRenderer.levelName(value)));
        });
    }

    private int lockChannel(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, CHANNEL_PARAMETER);
        float value = FloatArgumentType.getFloat(ctx, LEVEL_PARAMETER);
        return this.execute(ctx, () -> {
            if (PrecipitationIntensity.lockChannel(name, value))
                return Component.literal("Channel %s locked to %.2f (immediate, no easing)"
                        .formatted(name, value));
            return Component.literal("No channel named '%s'. Try: sky, cloud, fog, fogColor, "
                    .formatted(name)
                    + "skyLight, sun, storm, geometry, audio, splash, wet, drip, overcast, clear");
        });
    }

    private int unlockChannel(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, CHANNEL_PARAMETER);
        return this.execute(ctx, () -> {
            if (PrecipitationIntensity.unlockChannel(name))
                return Component.literal("Channel %s released".formatted(name));
            return Component.literal("No channel named '%s'".formatted(name));
        });
    }

    private int unlockAll(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        return this.execute(ctx, () -> {
            PrecipitationIntensity.release();
            PrecipitationIntensity.unlockChannel(null);
            return Component.literal("Intensity and all channels released");
        });
    }

    /**
     * The dry-weather sky events, run by hand. {@code /dsprecip event} alone
     * reports the scheduler state; a name starts that event (ending whatever
     * was up); {@code stop} ends the one that is running. A manual start
     * bypasses the lottery but not the weather: started into rain the event
     * yields until the rain passes, exactly as a rolled event would.
     */
    private int eventStatus(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        return this.execute(ctx, () -> Component.literal("Sky events: " + SkyEventScheduler.diagnostics()));
    }

    private int eventControl(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, EVENT_PARAMETER);
        return this.execute(ctx, () -> {
            if ("stop".equalsIgnoreCase(name) || "end".equalsIgnoreCase(name)
                    || "none".equalsIgnoreCase(name))
                return Component.literal(SkyEventScheduler.stopEvent()
                        ? "Sky event ended"
                        : "No sky event is running");
            if (SkyEventScheduler.startEvent(name)) {
                final String note = PrecipitationIntensity.rainingNow()
                        ? " (it is raining - the event yields until the rain stops)"
                        : "";
                return Component.literal("Sky event %s started%s - /dsprecip event stop to end it"
                        .formatted(name.toLowerCase(java.util.Locale.ROOT), note));
            }
            return Component.literal("No sky event named '%s'. Try: overcast, clear, stop"
                    .formatted(name));
        });
    }
}
