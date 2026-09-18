package org.orecruncher.dsurround.commands.handlers;

import net.minecraft.network.chat.Component;
import org.orecruncher.dsurround.runtime.audio.AudioTuning;

public final class TuneCommandHandler {

    private TuneCommandHandler() {
    }

    public static Component describe() {
        return Component.literal("Dynamic Surroundings audio tuning (session overrides):\n"
                + AudioTuning.describeAll());
    }

    public static Component reset() {
        return Component.literal(AudioTuning.reset());
    }

    public static Component set(final String key, final String value) {
        return Component.literal(AudioTuning.set(key, value));
    }
}
