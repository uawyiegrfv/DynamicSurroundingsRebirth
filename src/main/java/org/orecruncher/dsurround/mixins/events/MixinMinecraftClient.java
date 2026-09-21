package org.orecruncher.dsurround.mixins.events;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import org.orecruncher.dsurround.mixinutils.MixinHelpers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets biome background music play while the player is in creative mode.
 *
 * <p>Vanilla's {@code Minecraft.getSituationalMusic()} picks between the creative music track and the
 * current biome's own background music with
 * {@code ... && player.getAbilities().instabuild && player.getAbilities().mayfly ? Musics.CREATIVE
 * : holder.value().getBackgroundMusic().orElse(Musics.GAME)}. In creative mode the biome's music is
 * therefore unreachable - correct vanilla behaviour, and exactly what the
 * {@code playBiomeMusicWhileCreative} option exists to override.
 *
 * <p>Returning an {@code Abilities} instance whose flags are false makes vanilla take the biome branch.
 * The bytecode has TWO {@code getAbilities()} calls in that expression - {@code instabuild} at offset
 * 141 and {@code mayfly} at 154 - and only the first is redirected, because {@code &&} short-circuits:
 * once {@code instabuild} is false the second is never evaluated. The cached instance is deliberate;
 * allocating per call would run on every music tick.
 *
 * <p>Uses the base {@code @Redirect} rather than mixinextras' {@code @WrapOperation}, which this port
 * does not have - the same substitution MixinEntityArrow documents.
 *
 * <p>This is the music half of the old {@code MixinMinecraftClient.java.disabled} file. The rest of
 * that file raised the client tick/start/stop events, which 1.20.1 already gets from Forge's client
 * events in {@code DSurround}. Until now the option was dead in this port, because nothing read it.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftClient {

    @Unique
    private Abilities dsurround_cachedAbilities;

    @Redirect(method = "getSituationalMusic()Lnet/minecraft/sounds/Music;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getAbilities()Lnet/minecraft/world/entity/player/Abilities;",
                    ordinal = 0))
    private Abilities dsurround_instabuildCheck(LocalPlayer instance) {
        if (MixinHelpers.soundOptions.playBiomeMusicWhileCreative) {
            if (this.dsurround_cachedAbilities == null) {
                this.dsurround_cachedAbilities = new Abilities();
            }
            return this.dsurround_cachedAbilities;
        }
        return instance.getAbilities();
    }
}
