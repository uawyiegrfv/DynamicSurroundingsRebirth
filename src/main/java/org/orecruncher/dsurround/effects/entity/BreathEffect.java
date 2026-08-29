package org.orecruncher.dsurround.effects.entity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.effects.particles.BreathBubbleParticle;
import org.orecruncher.dsurround.effects.particles.FrostBreathParticle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.lib.system.ITickCount;
import org.orecruncher.dsurround.lib.random.MurmurHash3;

public class BreathEffect extends EntityEffectBase {

    private static final ISeasonalInformation SEASONAL_INFORMATION = ContainerManager.resolve(ISeasonalInformation.class);

    // Vanilla tag covering the high mountain biomes (meadow, grove, snowy slopes,
    // jagged/frozen/stony peaks).  Frost breath also shows in these biomes at
    // altitude even when the biome temperature is not below the cold threshold.
    private static final TagKey<Biome> IS_MOUNTAIN = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", "is_mountain"));

    // Minimum altitude at which the mountain breath mist can appear.
    private static final int MOUNTAIN_BREATH_MIN_Y = 100;

    private final ITickCount tickCount;
    private int seed;

    public BreathEffect(ITickCount tickCount) {
        this.tickCount = tickCount;
    }

    @Override
    public void activate(final EntityEffectInfo info) {
        if (info.isRemoved())
            this.seed = 0;
        else
            this.seed = MurmurHash3.hash(info.getEntity().getId()) & 0xFFFF;
    }

    @Override
    public void tick(final EntityEffectInfo info) {
        if (info.isRemoved())
            return;

        var entity = info.getEntity();
        if (!this.isBreathVisible(entity))
            return;

        final int c = (int) (this.tickCount.getTickCount() + this.seed);
        final BlockPos headPos = getHeadPosition(entity);
        final BlockState state = entity.level().getBlockState(headPos);
        if (showWaterBubbles(state)) {
            final int air = entity.getAirSupply();
            if (air > 0) {
                final int interval = c % 10;
                if (interval == 0) {
                    createBubbleParticle(entity, false);
                }
            } else if (air == 0) {
                // Need to generate a bunch of bubbles due to drowning
                for (int i = 0; i < 3; i++) {
                    createBubbleParticle(entity, true);
                }
            }
        } else {
            final int interval = (c / 10) % 8;
            if (interval < 3 && showFrostBreath(entity, state, headPos)) {
                createFrostParticle(entity);
            }
        }
    }

    protected boolean isBreathVisible(final LivingEntity entity) {
        final var player = GameUtils.getPlayer().orElseThrow();
        var settings = GameUtils.getGameSettings();
        if (entity.getId() == player.getId()) {
            return !(player.isSpectator() || settings.hideGui);
        }
        return !entity.isInvisibleTo(player) && player.hasLineOfSight(entity);
    }

    protected BlockPos getHeadPosition(final LivingEntity entity) {
        return BlockPos.containing(entity.getEyePosition());
    }

    protected boolean showWaterBubbles(final BlockState headBlock) {
        return !headBlock.getFluidState().isEmpty();
    }

    protected boolean showFrostBreath(final LivingEntity entity, final BlockState headBlock, final BlockPos pos) {
        if (headBlock.isAir()) {
            if (SEASONAL_INFORMATION.isColdTemperature(pos))
                return true;
            // High mountain biomes also produce breath mist (user request): above the
            // altitude threshold the mountain air is treated as cold.
            if (pos.getY() >= MOUNTAIN_BREATH_MIN_Y) {
                var biome = entity.level().getBiome(pos).value();
                return TAG_LIBRARY.is(IS_MOUNTAIN, biome);
            }
        }
        return false;
    }

    protected void createBubbleParticle(LivingEntity entity, boolean isDrowning) {
        if (!(entity.level() instanceof ClientLevel world))
            return;
        final var eye = entity.getEyePosition();
        final var random = entity.getRandom();
        final int count = isDrowning ? 3 : 1;
        for (int i = 0; i < count; i++) {
            // Spawn at the mouth (in front of the eye/crosshair) as a small translucent bubble.
            var particle = new BreathBubbleParticle(world,
                    eye.x + (random.nextFloat() - 0.5F) * 0.1D,
                    eye.y - 0.1D + random.nextFloat() * 0.1D,
                    eye.z + (random.nextFloat() - 0.5F) * 0.1D);
            GameUtils.getParticleManager().add(particle);
        }
    }

    protected void createFrostParticle(LivingEntity entity) {
        var particle = new FrostBreathParticle(entity);
        this.addParticle(particle);
    }

}