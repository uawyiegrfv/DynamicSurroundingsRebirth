package org.orecruncher.dsurround.processing.echo;

import net.minecraft.world.phys.Vec3;

/**
 * One detected reflective wall: a vertical face, described as a point on it plus an outward
 * normal that points back at the listener.
 *
 * <p>Detection is deliberately separate from solving. The wall set only depends on the terrain
 * and the listener, so it is rebuilt once or twice a second; the reflection geometry depends on
 * where a particular SOUND started, so it is solved per sound when an echo is considered.</p>
 *
 * @param face      a point on the face, at ear height
 * @param normal    unit normal pointing back toward the listener
 * @param halfWidth half the width of the face along the wall, in blocks
 * @param reflectivity 0..1 material reflectance (BlockInfo sound reflectivity)
 * @param azimuthDeg azimuth of the wall from the listener, 0 = south, 90 = west, as MC yaw
 * @param distance  horizontal distance from the listener to the face
 */
public record EchoWall(Vec3 face, Vec3 normal, float halfWidth, float reflectivity,
                       float azimuthDeg, float distance) {
}
