package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
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
            return;
        }

        this.move(this.xd, this.yd, this.zd);
        this.yd *= 0.98F;

        // Pop at the surface - meaning the fluid's TOP face, which is the only place a bubble can burst.
        //
        // Two earlier attempts at this were wrong, and the second was the bug that got reported.
        //
        // Without any check the bubble rose through the water and kept going: hasPhysics is false and
        // the 0.05/tick velocity decays slowly, so a 40-tick life carried it roughly 1.2 blocks past the
        // surface before expiring. The 1.12.2 original extended vanilla's ParticleBubble, which carries
        // the check; the port replaced that with a plain particle and lost it.
        //
        // The first fix copied WaterDropParticle's test verbatim - remove when below the current
        // block's collision surface or fluid height. For a RAIN DROP that is right, because a drop
        // lands on a top face. For a bubble rising through water it is wrong: a block with water above
        // it reports a fluid height of 1.0, so the particle is "below the surface" of its own block for
        // the entire ascent and was removed on its very first tick. Bubbles vanished the instant they
        // appeared.
        //
        // So the test is now specific to the surface: only a fluid block with NO fluid above it has an
        // exposed top face, and the bubble pops when it reaches that height. A bubble deep in a column
        // is unaffected, and one in the top water block pops exactly at the water line.
        final BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
        final var fluid = this.level.getFluidState(pos);
        if (fluid.isEmpty())
            return;   // out of the water entirely (a popped bubble already removed, or spawned in air)

        if (!this.level.getFluidState(pos.above()).isEmpty())
            return;   // still under more water; the surface is higher up

        // The top face of this block is the surface. getHeight is 0.875 for a source with air above.
        final double surfaceY = pos.getY() + fluid.getHeight(this.level, pos);
        if (this.y >= surfaceY)
            this.remove();
    }
}
