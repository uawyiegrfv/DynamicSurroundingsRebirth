package org.orecruncher.dsurround.processing.aurora;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.orecruncher.dsurround.lib.GameUtils;

import java.util.Random;

/**
 * Base class of a spawned aurora. Ported from 1.12.2 Dynamic Surroundings
 * (MIT). The original pulled the player/dimension info from a global
 * EnvironState; 26.1 resolves them through {@link GameUtils} instead, and the
 * sky height uses the level's build height since 26.1 has no per-dimension sky
 * height API.
 */
public abstract class AuroraBase implements IAurora {

    protected static final int PLAYER_FIXED_Y_OFFSET = 64;
    protected static final int PLAYER_FIXED_Z_OFFSET = 150;

    protected static final int AURORA_PEAK_AGE = 512;
    protected static final int AURORA_AGE_RATE = 1;

    protected final Random random;
    protected final AuroraBand band;
    protected final int bandCount;
    protected final float offset;
    protected final AuroraLifeTracker tracker;
    protected final AuroraColor colors;

    protected final Player player;
    protected final Level level;

    public AuroraBase(final long seed) {
        this(seed, false);
    }

    public AuroraBase(final long seed, final boolean flag) {
        this(new Random(seed), flag);
    }

    public AuroraBase(final Random rand, final boolean flag) {
        this.random = rand;
        this.bandCount = Math.min(this.random.nextInt(3) + 1, 3);
        this.offset = this.random.nextInt(20) + 20;
        this.colors = AuroraColor.get(this.random);

        final AuroraGeometry geo = AuroraGeometry.get(this.random);
        this.band = new AuroraBand(this.random, geo, flag, flag);
        this.tracker = new AuroraLifeTracker(AURORA_PEAK_AGE, AURORA_AGE_RATE);

        this.player = GameUtils.getPlayer().orElse(null);
        this.level = GameUtils.getWorld().orElse(null);
    }

    private boolean isAlive() {
        return this.tracker.isAlive();
    }

    @Override
    public void setFading(final boolean flag) {
        this.tracker.setFading(flag);
    }

    @Override
    public boolean isDying() {
        return this.tracker.isFading();
    }

    @Override
    public void update() {
        this.tracker.update();
    }

    @Override
    public boolean isComplete() {
        return !isAlive();
    }

    protected float getAlpha() {
        return (this.tracker.ageRatio() * this.band.getAlphaLimit()) / 255;
    }

    protected double getTranslationX(final float partialTick) {
        if (this.player == null)
            return 0.0D;
        return this.player.getX() - (this.player.xOld + (this.player.getX() - this.player.xOld) * partialTick);
    }

    protected double getTranslationZ(final float partialTick) {
        if (this.player == null)
            return 0.0D;
        return (this.player.getZ() - PLAYER_FIXED_Z_OFFSET)
                - (this.player.zOld + (this.player.getZ() - this.player.zOld) * partialTick);
    }

    protected double getTranslationY(final float partialTick) {
        if (this.player == null || this.level == null)
            return PLAYER_FIXED_Y_OFFSET;
        if (this.player.getY() > this.level.getSeaLevel()) {
            // 26.1: no per-dimension cloud height; approximate the band ceiling with
            // the level build height (logical height) and a cloud height ~ 192.
            final double limit = (this.level.getHeight() + 192.0D) / 2D;
            final double d1 = limit - this.level.getSeaLevel();
            final double d2 = this.player.getY() - this.level.getSeaLevel();
            return PLAYER_FIXED_Y_OFFSET * (d1 - d2) / d1;
        }

        return PLAYER_FIXED_Y_OFFSET;
    }

    protected AuroraColor.ColorF getBaseColor() {
        return this.colors.baseColor;
    }

    protected AuroraColor.ColorF getFadeColor() {
        return this.colors.fadeColor;
    }

    @Override
    public abstract void render(final float partialTick);

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("bands: ").append(this.bandCount);
        builder.append(", off: ").append(this.offset);
        builder.append(", len: ").append(this.band.length);
        builder.append(", base: ").append(getBaseColor().toString());
        builder.append(", fade: ").append(getFadeColor().toString());
        builder.append(", alpha: ").append((int) (getAlpha() * 255));
        if (!this.tracker.isAlive())
            builder.append(", DEAD");
        else if (this.tracker.isFading())
            builder.append(", FADING");
        return builder.toString();
    }
}
