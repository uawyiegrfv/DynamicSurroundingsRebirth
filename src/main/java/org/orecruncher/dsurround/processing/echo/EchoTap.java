package org.orecruncher.dsurround.processing.echo;

import net.minecraft.world.phys.Vec3;

/**
 * One solved reflection: where the echo is heard from, how late, how loud and how dark.
 *
 * @param reflection the reflection point on the wall (where the copy is played from)
 * @param delayMs    extra travel time of the reflected path, milliseconds
 * @param gain       amplitude relative to the direct sound, after the game curve
 * @param cutoff     low-pass multiplier for the echo source, 0..1
 * @param order      1 for a single bounce, 2 for a double slap
 * @param azimuthDeg direction of the reflecting wall, for diagnostics
 */
public record EchoTap(Vec3 reflection, float delayMs, float gain, float cutoff, int order,
                      float azimuthDeg) {
}
