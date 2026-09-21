package org.orecruncher.dsurround.config.libraries;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

public interface IDimensionInformation {
    /**
     * The resource location ID of the dimension
     */
    Identifier name();
    /**
     * The client level for the dimension
     */
    ClientLevel level();
    /**
     * The sea level configured for the dimension
     */
    int seaLevel();
    /**
     * Whether the dimension is considered always outside, like Nether.
     */
    boolean alwaysOutside();
    /**
     * The vertical Y level which is the threshold of outer space.
     */
    int getSpaceHeight();
    /**
     * The veritical Y level where clouds are expected to be
     */
    int getCloudHeight();

    /**
     * Indicates whether the compass should "wobble" making the bearing unreadable
     */
    boolean getCompassWobble();

    /**
     * Whether biome ambient sounds play in this dimension.
     *
     * <p>dimensions.json has carried a playBiomeSounds key all along, and DimensionInfo parsed and
     * stored it - but there was no accessor and no reader, so a pack that set it to false got biome
     * ambience anyway. This exposes it so the setting does what it says.
     */
    boolean playBiomeSounds();
}
