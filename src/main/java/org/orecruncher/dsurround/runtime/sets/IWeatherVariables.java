package org.orecruncher.dsurround.runtime.sets;

@SuppressWarnings("unused")
public interface IWeatherVariables {

    /**
     * Is it currently raining in the player world
     *
     * @return true if it is raining, false otherwise
     */
    boolean isRaining();

    /**
     * Is it currently thundering in the player world
     *
     * @return true if it is thundering, false otherwise
     */
    boolean isThundering();

    /**
     * Inverse of isRaining();
     *
     * @return true if it is not raining, false otherwise
     */
    default boolean isNotRaining() {
        return !isRaining();
    }

    /**
     * Inverse of isThundering()
     *
     * @return true if it is not thundering, false otherwise
     */
    default boolean isNotThundering() {
        return !isThundering();
    }

    /**
     * Get the current rain intensity
     *
     * @return 0 - 1
     */
    float getRainIntensity();

    /**
     * Get the current thunder intensity
     *
     * @return 0 - 1
     */
    float getThunderIntensity();

    /**
     * Our graded storm intensity, 0 - 1. Unlike {@link #getRainIntensity()} this
     * is not the vanilla rain ramp: it is the per-storm roll shaped by the biome
     * profile and the season, so a drizzle and a downpour read differently even
     * though both sit at a vanilla rain level of 1.0.
     *
     * <p>Deliberately <em>not</em> gated on what is landing on the player: wind,
     * sky darkness and fog are properties of the weather, not of whether the
     * player happens to be under a roof. Use
     * {@link #getGradedPrecipitation()} when the question is "is precipitation
     * actually reaching here".</p>
     *
     * @return 0 - 1
     */
    float getGradedIntensity();

    /**
     * Our graded precipitation intensity, 0 - 1, gated - 0 when nothing is
     * actually falling at the player's position (dry season, a biome that does
     * not precipitate, under a roof, or not raining).
     *
     * <p>This is the one to use for anything that needs rain to physically
     * arrive: surface impact sounds, puddle splashes.</p>
     *
     * @return 0 - 1
     */
    float getGradedPrecipitation();

    /**
     * Gets the temperature at the current player location
     *
     * @return the temperature
     */
    float getTemperature();

    /**
     * Indicates if the temperature at the player location is cold enough to show frost breath, etc.
     *
     * @return true if the current temperature conditions are frosty, false otherwise
     */
    boolean isFrosty();

    /**
     * Indicaets if the temperature at the player location is cold enough for water to freeze.
     *
     * @return true if the current temperature allows water freezing, false otherwise.
     */
    boolean canWaterFreeze();
}
