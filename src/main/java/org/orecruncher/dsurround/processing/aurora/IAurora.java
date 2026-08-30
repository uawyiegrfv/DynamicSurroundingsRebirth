package org.orecruncher.dsurround.processing.aurora;

/**
 * Implemented by an aurora so that it can go through its life cycle. Ported
 * from 1.12.2 Dynamic Surroundings (MIT).
 */
public interface IAurora {

    /**
     * Instructs the aurora to start the process of decay (i.e. start to fade)
     */
    void setFading(final boolean flag);

    /**
     * Indicates if the aurora is in the process of dying
     */
    boolean isDying();

    /**
     * Perform the necessary housekeeping for the aurora. Occurs once a tick.
     */
    void update();

    /**
     * Indicates if an aurora has completed its life cycle and can be removed.
     */
    boolean isComplete();

    /**
     * Render the aurora to the client screen. It is possible that other updates
     * can occur to the state, such as doing the transformations to animate.
     */
    void render(final float partialTick);

}
