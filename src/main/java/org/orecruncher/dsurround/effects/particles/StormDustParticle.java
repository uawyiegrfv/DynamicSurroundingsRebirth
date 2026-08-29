package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.resources.ResourceLocation;
import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

/**
 * A17: Ambient dust particle for the sandstorm/nether dust rain. Blows with a wind
 * drift (strong for the desert sandstorm, gentle for the nether dust rain), falls
 * with gravity, fades out. The rain-scale dust effect is rendered by
 * WeatherStormHandler's world-space veil; these particles are the light ambience.
 */
public class StormDustParticle extends TextureSheetParticle {
    private static final IRandomizer RANDOM = Randomizer.current();

    private final float windX;
    private final float windZ;

    public StormDustParticle(ClientLevel world, double x, double y, double z, float r, float g, float b, boolean windy) {
        super(world, x, y, z, 0.0, 0.0, 0.0);
        this.setSprite(ParticleUtils.getSprite(new ResourceLocation("minecraft", "generic_5")));
        this.gravity = 0.6F;
        // Sandstorm particles are larger and more visible than the nether dust.
        this.quadSize *= windy ? 0.12F : 0.06F;
        this.lifetime = 40 + this.random.nextInt(40);
        this.setColor(r, g, b);
        this.setAlpha(0.7F);

        // Wind: strong directional drift for a sandstorm, gentle for dust rain.
        this.windX = windy ? (this.random.nextFloat() - 0.25F) * 0.16F : (this.random.nextFloat() - 0.5F) * 0.04F;
        this.windZ = windy ? (this.random.nextFloat() - 0.25F) * 0.16F : (this.random.nextFloat() - 0.5F) * 0.04F;
        this.xd = this.windX;
        this.yd = 0.0D;
        this.zd = this.windZ;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        this.yd -= 0.04D * this.gravity;
        this.move(this.xd, this.yd, this.zd);
        // Constant wind keeps the storm blowing.
        this.xd = this.windX;
        this.zd = this.windZ;

        // Fade out over the last 30% of the lifetime.
        float life = this.age / (float) this.lifetime;
        this.setAlpha(life > 0.7F ? 0.7F * (1.0F - (life - 0.7F) / 0.3F) : 0.7F);
    }
}
