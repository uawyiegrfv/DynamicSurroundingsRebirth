package org.orecruncher.dsurround.processing.aurora;

import net.minecraft.util.Mth;

/**
 * A single node of an aurora band. Ported from the 1.12.2 Dynamic Surroundings
 * (MIT). The original math was expressed via OreCruncher's MathStuff; here we
 * use vanilla Mth helpers (cos/sin take radians).
 */
final class Panel {

    private static final float COS_DEG90_FACTOR = Mth.cos(Mth.PI / 2.0F);
    private static final float COS_DEG270_FACTOR = Mth.cos(Mth.PI / 2.0F + Mth.PI);
    private static final float SIN_DEG90_FACTOR = Mth.sin(Mth.PI / 2.0F);
    private static final float SIN_DEG270_FACTOR = Mth.sin(Mth.PI / 2.0F + Mth.PI);

    public float dZ = 0.0F;
    public float dY = 0.0F;

    public float cosDeg90 = 0.0F;
    public float cosDeg270 = 0.0F;
    public float sinDeg90 = 0.0F;
    public float sinDeg270 = 0.0F;

    public float angle;
    public float posX;
    public float posY;
    public float posZ;

    public float tetX = 0.0F;
    public float tetX2 = 0.0F;
    public float tetZ = 0.0F;
    public float tetZ2 = 0.0F;

    public Panel(final Panel template, final int offset) {
        final float rads = Mth.DEG_TO_RAD * (90.0F + template.angle);
        this.posX = template.posX + Mth.cos(rads) * offset;
        this.posY = template.posY - 2.0F;
        this.posZ = template.posZ + Mth.sin(rads) * offset;
        this.angle = template.angle;
    }

    public Panel(final float x, final float y, final float z, final float theta) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.angle = theta;
    }

    public void setDeltaZ(final float f) {
        this.dZ = f;
    }

    public void setDeltaY(final float f) {
        this.dY = f;
    }

    public float getModdedZ() {
        return this.posZ + this.dZ;
    }

    public float getModdedY() {
        final float y = this.posY + this.dY;
        return y < 0.0F ? 0.0F : y;
    }

    public void setWidth(final float w) {
        this.cosDeg270 = COS_DEG270_FACTOR * w;
        this.cosDeg90 = COS_DEG90_FACTOR * w;
        this.sinDeg270 = SIN_DEG270_FACTOR * w;
        this.sinDeg90 = SIN_DEG90_FACTOR * w;

        this.tetX = this.posX + this.cosDeg90;
        this.tetX2 = this.posX + this.cosDeg270;
    }

}
