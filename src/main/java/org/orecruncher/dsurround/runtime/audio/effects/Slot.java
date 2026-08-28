package org.orecruncher.dsurround.runtime.audio.effects;

import org.lwjgl.openal.EXTEfx;
import org.orecruncher.dsurround.runtime.audio.AudioUtilities;

import java.util.function.IntConsumer;

public abstract class Slot {

    private final IntConsumer deleter;
    private int slot = EXTEfx.AL_EFFECTSLOT_NULL;

    public Slot(final IntConsumer deleter) {
        this.deleter = deleter;
    }

    public boolean isInitialized() {
        return this.slot != EXTEfx.AL_EFFECTSLOT_NULL;
    }

    public final void initialize() {
        if (this.slot == EXTEfx.AL_EFFECTSLOT_NULL) {
            AudioUtilities.execute(() -> this.slot = this.factory(), () -> "Slot factory get");
            AudioUtilities.execute(this::init0, () -> "Slot init0");
        }
    }

    /**
     * Deletes the underlying OpenAL object. Must be called on the sound engine
     * thread; merely dropping the handle would leak the object in the device.
     */
    public final void deinitialize() {
        if (this.slot != EXTEfx.AL_EFFECTSLOT_NULL) {
            final int handle = this.slot;
            this.slot = EXTEfx.AL_EFFECTSLOT_NULL;
            AudioUtilities.execute(() -> this.deleter.accept(handle), () -> "Slot delete");
        }
    }

    protected abstract int factory();

    protected abstract void init0();

    public int getSlot() {
        return this.slot;
    }
}
