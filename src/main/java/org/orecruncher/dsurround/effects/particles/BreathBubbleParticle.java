package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ParticleTypes;

/**
 * A small translucent breath bubble spawned at the entity's mouth while underwater,
 * ported from the original 1.12.2 BubbleBreathParticle. Follows the safe
 * FrostBreathParticle pattern: the sprite comes from the engine's sprite set for the
 * vanilla bubble particle and movement uses Particle#move, avoiding the earlier
 * TRANSLUCENT render crash.
 */
public class BreathBubbleParticle extends SingleQuadParticle {

    public BreathBubbleParticle(ClientLevel world, double x, double y, double z) {
        super(world, x, y, z, 0.0D, 0.0D, 0.0D,
                ParticleUtils.getSpriteProvider(ParticleTypes.BUBBLE).first());

        this.quadSize = 0.06F;
        this.setAlpha(0.35F);
        this.xd = 0.0D;
        this.yd = 0.05D;
        this.zd = 0.0D;
        this.lifetime = 40;
        this.hasPhysics = false;
    }

    @Override
    public SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            this.move(this.xd, this.yd, this.zd);
            this.yd *= 0.98F;
        }
    }
}
