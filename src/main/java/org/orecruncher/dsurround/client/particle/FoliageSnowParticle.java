package org.orecruncher.dsurround.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.effects.particles.ParticleUtils;

/**
 * A snow unit resting on a plant - the snow layer's particle.
 *
 * <p>Everything about the geometry is inherited from {@link FoliageDripParticle}
 * (the plane basis, the贴面 quad, the slide and the evaporate phases). Three
 * things differ, and they are the three the maintainer's review pinned down:</p>
 *
 * <ol>
 * <li><b>Colour.</b> The parent's constructor ends with {@code setDew(false)},
 *     which paints the eave blue - and it has to, because vanilla's drip sheet
 *     is GREYSCALE and an untinted drop renders white (that was a real bug).
 *     Snow is explicitly white-and-opaque, so it overwrites the tint here.</li>
 * <li><b>Render layer.</b> {@code PARTICLE_SHEET_TRANSLUCENT} since round 7.
 *     It was OPAQUE, and {@code PARTICLE_SHEET_OPAQUE} calls
 *     {@code disableBlend()} - a vertex alpha there is DISCARDED, which is why
 *     melting could only ever be a SHRINK (the parent's {@code beginEvaporate}
 *     scales {@code size} by {@code 1 - t}). The maintainer asked for the
 *     opposite: slide a long way down the blade, then fade. A fade needs the
 *     blending sheet, so the unit moved. The sprite bodies are still fully
 *     opaque, so nothing looks different at rest.</li>
 * <li><b>Size.</b> Pinned per unit type (no vanilla variance): a blocky sprite
 *     that gets randomly scaled turns to mush, and the定标 page's coverage
 *     figures assume fixed sizes.</li>
 * </ol>
 */
public class FoliageSnowParticle extends FoliageDripParticle {

    /**
     * Unit types, in the order the handler asks for them. The定标 page
     * (snow_anim.html) settled on: dot &rarr; clump &rarr; sheet as the snow
     * deepens, with the sheet doing the coverage work.
     */
    public static final int KIND_DOT_A = 0;
    public static final int KIND_DOT_B = 1;
    public static final int KIND_CLUMP_A = 2;
    public static final int KIND_CLUMP_B = 3;
    public static final int KIND_SHEET_A = 4;
    public static final int KIND_SHEET_B = 5;
    public static final int KIND_COUNT = 6;

    private static final Identifier[] SPRITES = {
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_dot_a"),
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_dot_b"),
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_clump_a"),
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_clump_b"),
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_sheet_a"),
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "snow_sheet_b")
    };

    /**
     * The quad size of each unit, in blocks - v2, after the maintainer's
     * "最小的贴图太小了，至少要有半个草纹理的像素点大". The v1 numbers were
     * set against the FULL quad, but the sprites only fill part of their 8x8:
     * the dot's 2x2 sliver inside a 0.09 quad rendered at 0.36 of a plant
     * texture pixel - smaller than the half-pixel he asked for. v2 both
     * enlarges the sprites (4x4..8x3 opaque spans) and re-scales the quads,
     * measured on the render page: the dot now reads at 1.3 plant pixels,
     * full depth lands at ~50% white coverage with the blade structure still
     * legible and float still ~20%. The sheet is nearly unchanged ON PURPOSE:
     * at its scale the binding constraint is leaves only 4-6 pixels wide -
     * wider sheets just hang off the blade.
     */
    /**
     * Round 10: how a broken plant's snow gets down - "破坏后掉落速度太慢了，
     * 还有为啥要加飘落动画，直接竖直下落不好吗，速度比自由落体慢一点就行了".
     * So: it IS a plain vertical fall now. Same drag as the vanilla drip, same
     * release; only the gravity is lighter (two thirds of it), which puts a
     * drop from a tall fern's two blocks at half a second against the drip's
     * 0.40s - slower, but the same kind of motion. The sideways drift round 9
     * gave it is gone: he asked for it not to drift.
     */
    private static final double SNOW_FALL_GRAVITY = 0.040;
    private static final double SNOW_FALL_DRAG = 0.98;
    /** No sideways nudge - round 10 asked for a straight drop. */
    private static final double SNOW_FALL_DRIFT = 0.0;
    /** Same release as the vanilla drip. */
    private static final double SNOW_FALL_KICK = 0.02;

    private static final float[] SIZES = {
            0.18F, 0.18F,       // dot_a, dot_b   (~1.5 plant px; the vanilla drip hangs at 0.20)
            0.32F, 0.32F,       // clump_a, clump_b - round 12: 0.26 -> 0.32, which is the
                                // width of the 5x5 window a room-2 anchor guarantees
                                // (5/16 = 0.3125 blocks). The middle size was only ~3 plant
                                // pixels across against a sheet's 53, so a coat read as
                                // "specks and slabs" with nothing in between.
            0.56F, 0.56F        // sheet_a, sheet_b (~6.7 px, thin)
    };

    FoliageSnowParticle(final ClientLevel level, final double x, final double y, final double z,
                        final int restTicks, final double sizeScale, final TextureAtlasSprite sprite,
                        final int kind) {
        super(level, x, y, z, restTicks, sizeScale, sprite);
        // Explicitly white and fully opaque. The parent defaults to the eave's
        // blue (see the class note) - snow must overwrite it, not inherit it.
        this.setColor(1.0F, 1.0F, 1.0F);
        this.alpha = 1.0F;
        // A snow unit in free fall is still a snow unit. The parent swaps in
        // vanilla's drip_fall sprite the moment a drop leaves the surface -
        // which is right for water and wrong here: a clump falling off a broken
        // blade wearing a droplet is exactly the sort of thing that reads as a
        // bug. Keep this unit's own sprite for the fall.
        this.fallSprite = sprite;
        // Round 9: "破坏后雪块掉落速度能慢些就好了，这样看起来感觉轻盈蓬松".
        // A third of the drip's gravity and a much thicker air: terminal speed
        // comes out at about a third of a block a second instead of three
        // blocks a second, so a unit let go at head height takes its time
        // getting down. The drift is a tenth of what the fall covers - enough
        // that it does not track straight down a wire, not enough to read as
        // wind. Measured offline: see _snow_v9_report.html.
        this.fallGravity = SNOW_FALL_GRAVITY;
        this.fallDrag = SNOW_FALL_DRAG;
        this.fallDrift = SNOW_FALL_DRIFT;
        this.fallKick = SNOW_FALL_KICK;
        // ...and when it lands it bursts into crumbs instead of a water splash.
        // A dot gives two, a clump three, a sheet four.
        this.breakParticle = ModParticles.SNOW_BREAK;
        this.breakCount = 2 + Mth.clamp(kind, 0, KIND_COUNT - 1) / 2;
    }

    /**
     * 26.1 dispatches by group, and the group owns the pipeline: this is the
     * one carrying {@code RenderPipelines.OPAQUE_PARTICLE} (see {@link
     * FoliageSnowParticleGroup}). A particle cannot change its layer - only
     * which group it was assigned at add time - so the opaque choice lives
     * there, not here.
     */
    @Override
    public ParticleRenderType getGroup() {
        return FoliageSnowParticleGroup.type();
    }

    /** The quad size for a unit type, in blocks. */
    public static float sizeOf(final int kind) {
        return SIZES[Mth.clamp(kind, 0, KIND_COUNT - 1)];
    }

    /**
     * Spawn params: unused, {@code unitIndex} (which unit shape this is),
     * unused. The handler casts the result back and drives it.
     *
     * <p>The size is derived here rather than passed in: the parent derives
     * {@code baseSize} from {@code sizeScale x VANILLA_DRIP_QUAD_SIZE}, so the
     * scale handed to {@code super} is the wanted size divided by that base.</p>
     */
    public record Provider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                       final double x, final double y, final double z,
                                       final double restTicks, final double unitIndex, final double unused,
                                       final net.minecraft.util.RandomSource random) {
            final int kind = Mth.clamp((int) unitIndex, 0, KIND_COUNT - 1);
            final double scale = sizeOf(kind) / VANILLA_DRIP_QUAD_SIZE;
            return new FoliageSnowParticle(level, x, y, z, (int) restTicks, scale,
                    ParticleUtils.getSprite(SPRITES[kind]), kind);
        }
    }
}
