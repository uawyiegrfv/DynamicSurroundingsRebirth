package org.orecruncher.dsurround.processing.aurora;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Preset geometry of an aurora. A preset is selected by the server when an
 * aurora spawns. Ported from 1.12.2 Dynamic Surroundings (MIT), where it was a
 * nested class of {@code AuroraFactory}.
 */
public final class AuroraGeometry {

    public final int length;
    public final float nodeLength;
    public final float nodeWidth;
    public final int alphaLimit;

    private static final List<AuroraGeometry> PRESET = new ArrayList<>();

    static {
        PRESET.add(new AuroraGeometry(128, 30.0F, 2.0F, 96));
        PRESET.add(new AuroraGeometry(128, 15.0F, 2.0F, 96));
        PRESET.add(new AuroraGeometry(64, 30.0F, 2.0F, 96));
        PRESET.add(new AuroraGeometry(64, 15.0F, 2.0F, 96));

        PRESET.add(new AuroraGeometry(128, 30.0F, 2.0F, 80));
        PRESET.add(new AuroraGeometry(128, 15.0F, 2.0F, 80));
        PRESET.add(new AuroraGeometry(64, 30.0F, 2.0F, 80));
        PRESET.add(new AuroraGeometry(64, 15.0F, 2.0F, 80));

        PRESET.add(new AuroraGeometry(128, 30.0F, 2.0F, 64));
        PRESET.add(new AuroraGeometry(128, 15.0F, 2.0F, 64));
        PRESET.add(new AuroraGeometry(64, 30.0F, 2.0F, 64));
        PRESET.add(new AuroraGeometry(64, 15.0F, 2.0F, 64));
    }

    private AuroraGeometry(final int length, final float nodeLength, final float nodeWidth, final int alphaLimit) {
        this.length = length;
        this.nodeLength = nodeLength;
        this.nodeWidth = nodeWidth;
        this.alphaLimit = alphaLimit;
    }

    public static AuroraGeometry get(final Random random) {
        final int idx = random.nextInt(PRESET.size());
        return PRESET.get(idx);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("bandLength:").append(this.length);
        builder.append(";nodeLength:").append(this.nodeLength);
        builder.append(";nodeWidth:").append(this.nodeWidth);
        builder.append(";alphaLimit:").append(this.alphaLimit);
        return builder.toString();
    }
}
