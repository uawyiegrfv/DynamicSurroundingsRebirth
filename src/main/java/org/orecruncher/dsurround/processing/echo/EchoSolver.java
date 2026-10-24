package org.orecruncher.dsurround.processing.echo;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a wall set plus a sound position into echo taps, by the closed-form image-source
 * construction.
 *
 * <p>For a plane through {@code face} with normal {@code n} (pointing at the listener), the source
 * distance to the plane is {@code a} and the ear's is {@code e}. The mirror source sits at
 * {@code S' = S - 2a n}, and the extra path length is {@code |S'-E| - |S-E|}. The reflection point
 * is where the line E->S' crosses the plane, which is {@code E + e/(a+e) * (S'-E)}.</p>
 *
 * <p>Two consequences are physics, not tuning: the reflection only exists on the same side as the
 * source, and the ear must be the FARTHER of the two ({@code e > a}) - a wall closer to the ear
 * than to the source cannot return a specular reflection to it. That is why a valley echo needs the
 * listener more than ~26 blocks from the wall (a 150 ms delay).</p>
 *
 * <p>The level is the physical one, then compressed: at physics level a 30-block wall lands around
 * -26 dB, which is masked by any ambience, so a game gain is applied and documented rather than
 * hidden.</p>
 */
public final class EchoSolver {

    private static final float SPEED_OF_SOUND = 343F;
    private static final float MIN_DELAY_MS = 60F;
    private static final float MAX_DELAY_MS = 900F;
    /** Physics gives about -26 dB at 30 blocks; this pulls the audible cases toward -13 dB. */
    private static final float GAME_GAIN = 6.0F;
    private static final float MIN_GAIN = 0.04F;
    private static final float MAX_GAIN = 0.45F;
    private static final int MAX_TAPS = 3;

    private EchoSolver() {
    }

    public static List<EchoTap> plan(final List<EchoWall> walls, final Vec3 source, final Vec3 ear) {
        final List<EchoTap> taps = new ArrayList<>(walls.size());
        final double direct = source.distanceTo(ear);
        for (final EchoWall wall : walls) {
            final double a = source.subtract(wall.face()).dot(wall.normal());
            final double e = ear.subtract(wall.face()).dot(wall.normal());
            if (a <= 0.05D || e <= a)
                continue;
            final Vec3 image = source.subtract(wall.normal().scale(2.0D * a));
            final double reflected = image.distanceTo(ear);
            final double extra = reflected - direct;
            final float delayMs = (float) (extra / SPEED_OF_SOUND * 1000.0D);
            if (delayMs < MIN_DELAY_MS || delayMs > MAX_DELAY_MS)
                continue;
            final double t = e / (a + e);
            final Vec3 point = ear.add(image.subtract(ear).scale(t));
            // The reflection must land on the face we found, not somewhere along its infinite plane.
            final Vec3 tangent = new Vec3(-wall.normal().z, 0, wall.normal().x).normalize();
            if (Math.abs(point.subtract(wall.face()).dot(tangent)) > wall.halfWidth())
                continue;
            final float physical = (float) (wall.reflectivity() * direct / reflected);
            final float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, physical * GAME_GAIN));
            // Longer and softer walls darken more: a hard 1 kHz cutoff for a distant, grassy face.
            final float cutoff = Math.max(0.15F, Math.min(0.95F,
                    1.0F - 0.006F * (float) extra - 0.30F * (1.0F - wall.reflectivity())));
            taps.add(new EchoTap(point, delayMs, gain, cutoff, 1, wall.azimuthDeg()));
        }
        taps.sort(Comparator.comparingDouble(EchoTap::gain).reversed());
        return taps.size() > MAX_TAPS ? taps.subList(0, MAX_TAPS) : taps;
    }
}
