package org.orecruncher.dsurround.runtime.sets;

@SuppressWarnings("unused")
public interface ISeasonVariables {
    boolean isSpring();
    boolean isSummer();
    boolean isAutumn();
    boolean isWinter();

    /**
     * Sub-season tests (each season splits into thirds). When no seasonal driver is
     * installed, or the driver has no sub-season concept, all three report true - the
     * contract upstream documents as "for modpacks without seasonal mods, each will
     * return true". Pack scripts of the form "season.isEarly()" therefore still fire
     * instead of silently never matching.
     */
    boolean isEarly();
    boolean isMiddle();
    boolean isLate();

    default boolean isWarm() {
        return this.isSpring() || this.isSummer();
    }

    default boolean isCool() {
        return this.isAutumn() || this.isWinter();
    }
}
