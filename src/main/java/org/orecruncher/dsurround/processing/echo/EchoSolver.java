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
        // A canyon's characteristic "slap-back" is a second bounce between the two walls. Only
        // attempted for the strongest pair, and only kept when both reflection points land on
        // the faces that were actually detected.
        if (walls.size() >= 2) {
            final EchoTap second = secondOrder(walls.get(0), walls.get(1), source, ear, direct);
            if (second != null)
                taps.add(second);
        }
        taps.sort(Comparator.comparingDouble(EchoTap::gain).reversed());
        return taps.size() > MAX_TAPS ? taps.subList(0, MAX_TAPS) : taps;
    }

    /** Order penalty: a double bounce has crossed two faces and two extra legs. */
    private static final float SECOND_ORDER_PENALTY = 0.5F;

    /**
     * One double bounce S -> A -> B -> E, by unfolding: mirror the source across A, then across B,
     * and the straight line from the ear to that image crosses B then A at the two reflection
     * points. The same existence test applies to each plane, and both points must land on the
     * detected faces.
     */
    private static EchoTap secondOrder(final EchoWall a, final EchoWall b,
                                       final Vec3 source, final Vec3 ear, final double direct) {
        final double aA = source.subtract(a.face()).dot(a.normal());
        if (aA <= 0.05D)
            return null;
        final Vec3 imageA = source.subtract(a.normal().scale(2.0D * aA));
        final double aB = imageA.subtract(b.face()).dot(b.normal());
        if (aB <= 0.05D)
            return null;
        final Vec3 imageAB = imageA.subtract(b.normal().scale(2.0D * aB));
        final double eB = ear.subtract(b.face()).dot(b.normal());
        if (eB <= 0.05D || eB <= aB)
            return null;
        final Vec3 p2 = ear.add(imageAB.subtract(ear).scale(eB / (aB + eB)));
        // Walk back from P2 toward the A-side image to find the bounce on A.
        final double numerator = p2.subtract(a.face()).dot(a.normal());
        final double denominator = imageA.subtract(p2).dot(a.normal());
        if (Math.abs(denominator) < 1.0E-6D)
            return null;
        final double u = numerator / denominator;
        if (u < 0D || u > 1D)
            return null;
        final Vec3 p1 = p2.add(imageA.subtract(p2).scale(u));

        final Vec3 tangentA = new Vec3(-a.normal().z, 0, a.normal().x).normalize();
        final Vec3 tangentB = new Vec3(-b.normal().z, 0, b.normal().x).normalize();
        if (Math.abs(p1.subtract(a.face()).dot(tangentA)) > a.halfWidth())
            return null;
        if (Math.abs(p2.subtract(b.face()).dot(tangentB)) > b.halfWidth())
            return null;

        final double path = ear.distanceTo(p2) + p2.distanceTo(p1) + p1.distanceTo(source);
        final double extra = path - direct;
        final float delayMs = (float) (extra / SPEED_OF_SOUND * 1000.0D);
        if (delayMs < MIN_DELAY_MS || delayMs > MAX_DELAY_MS)
            return null;
        final float physical = (float) (a.reflectivity() * b.reflectivity() * direct / path)
                * SECOND_ORDER_PENALTY;
        final float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, physical * GAME_GAIN));
        final float cutoff = Math.max(0.12F, Math.min(0.95F,
                1.0F - 0.007F * (float) extra - 0.30F * (1.0F - Math.min(a.reflectivity(), b.reflectivity()))));
        // Heard from the second wall, which is where the ear's arrival comes from.
        return new EchoTap(p2, delayMs, gain, cutoff, 2, b.azimuthDeg());
    }
}
