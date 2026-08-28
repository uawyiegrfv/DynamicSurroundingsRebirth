package org.orecruncher.dsurround.mixins.audio;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

/**
 * Can't believe there isn't a toString() override
 */
@Mixin(SoundEvent.class)
public class MixinSoundEvent {

    @Shadow
    @Final
    private ResourceLocation location;

    // 1.20.1: SoundEvent stores range as a float and a newSystem flag; the
    // Optional<Float> fixedRange field only exists on 1.20.2+ (where it is a
    // stored field). Reconstruct the Optional here.
    @Shadow
    @Final
    private float range;

    @Shadow
    @Final
    private boolean newSystem;

    public String toString() {
        Optional<Float> fixedRange = this.newSystem ? Optional.empty() : Optional.of(this.range);
        return "%s{range %s}".formatted(this.location.toString(), fixedRange);
    }
}
