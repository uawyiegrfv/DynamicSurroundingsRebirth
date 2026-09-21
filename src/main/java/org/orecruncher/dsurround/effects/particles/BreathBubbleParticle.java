package org.orecruncher.dsurround.effects.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

        // Pop at the surface, using vanilla's own rule.
        //
        // Without this the bubble rose through the water surface and kept going: hasPhysics is false
        // and the 0.05/tick velocity decays slowly, so a 40-tick life carried it roughly 1.2 blocks
        // past the surface before it expired. The 1.12.2 original extended vanilla's ParticleBubble,
        // which carries this check; the port replaced that with a plain particle and lost it.
        //
        // This is the same test WaterDropParticle uses (its tick, the final statement): if the block
        // has a collision surface or a fluid height, anything below that height is inside it and the
        // particle is done. For a water source the fluid height is 0.875, so a bubble rising from an
        // entity's eye pops just under the surface.
        final BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
        final double surface = Math.max(
                this.level.getBlockState(pos).getCollisionShape(this.level, pos)
                        .max(Direction.Axis.Y, this.x - pos.getX(), this.z - pos.getZ()),
                this.level.getFluidState(pos).getHeight(this.level, pos));
        if (surface > 0.0D && this.y < pos.getY() + surface)
            this.remove();
    }
}
