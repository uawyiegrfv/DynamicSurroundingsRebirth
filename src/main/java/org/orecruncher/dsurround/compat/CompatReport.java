package org.orecruncher.dsurround.compat;

import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.processing.weather.RouteFuse;

/**
 * One line, at startup, that says who owns what.
 *
 * <p>The weather system shares four things with other mods - the fog, the clouds, the rain
 * curtain and the sky - and on 1.20.1 it loses some of them, in ways that are completely silent.
 * Distant Horizons takes the fog because Forge runs its {@code onFogRender} after ours, Simple
 * Clouds takes the clouds because it cancels {@code renderClouds} at HEAD, Particle Rain takes
 * the rain because it cancels the same vanilla method we suppress. None of that produces a log
 * line, a crash, or anything a pack author can see; it produces "the weather mod does nothing".
 *</p>
 *
 * <p>So the facts are stated out loud, once, in a fixed format that is greppable and that names
 * the owner of each route. The rule this exists to serve is the one the compatibility review kept
 * coming back to: <b>a hand-off nobody can see is indistinguishable from a bug</b>. Pack authors
 * read the log; give them the sentence.</p>
 *
 * <p>Nothing here changes behaviour. It reads {@link ModIds} and {@link RouteFuse} and prints.</p>
 */
public final class CompatReport {

    private CompatReport() {
    }

    /**
     * Emits the startup summary. Called once from client initialisation, next to the season
     * provider line, so it lands in the same part of every log.
     */
    public static void logStartupSummary() {
        log("[COMPAT] " + summary());
        if (ModIds.isAny(ModIds.CACHE_MODS))
            warn("[COMPAT] " + ModIds.firstPresent(ModIds.CACHE_MODS)
                    + " is installed: it caches resources, so DSR's data files (biome config, sound"
                    + " factories, tags) can be served from a stale copy after a resource-pack or"
                    + " data change. Not fought - if the sounds or the biome fog look wrong after an"
                    + " update, clear its cache first. See the DSR docs, 现状/THIRD-PARTY-CONFLICTS.md.");
    }

    /**
     * The summary line's body. Also used by the diagnostics overlay, so the panel and the log
     * cannot disagree about who owns the fog.
     */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("DH=").append(ModIds.flag(ModIds.DISTANT_HORIZONS));
        sb.append(" voxy=").append(ModIds.flag(ModIds.VOXY));
        sb.append(" simpleclouds=").append(ModIds.flag(ModIds.SIMPLE_CLOUDS));
        sb.append(" particlerain=").append(ModIds.flag(ModIds.PARTICLE_RAIN));
        sb.append(" sounds=").append(soundsFlag());
        sb.append(" iris=").append(ModIds.irisFlag());
        sb.append(" sodium=").append(ModIds.flagAny(ModIds.SODIUM));
        sb.append(" cache-mods=").append(ModIds.flagAny(ModIds.CACHE_MODS));
        sb.append(" | owners: fog=").append(fogOwner());
        sb.append(" cloud=").append(owner(RouteFuse.Capability.CLOUD, ModIds.SIMPLE_CLOUDS));
        sb.append(" rain=").append(owner(RouteFuse.Capability.RAIN, ModIds.PARTICLE_RAIN));
        sb.append(" sky=").append(owner(RouteFuse.Capability.SKY, (String) null));
        final String notes = notes();
        if (!notes.isEmpty())
            sb.append(" | ").append(notes);
        return sb.toString();
    }

    /**
     * The fog's owner, which is the one capability whose answer depends on a setting rather than
     * on presence: Distant Horizons only takes the terrain fog while its own vanilla-fog option is
     * off. See {@link DistantHorizonsCompat} - and note that this reports what DH is doing, not
     * what it could do.
     */
    private static String fogOwner() {
        if (DistantHorizonsCompat.ownsVanillaFog())
            return ModIds.DISTANT_HORIZONS;
        return owner(RouteFuse.Capability.FOG, (String) null);
    }

    /**
     * Who owns a capability: another mod if it is present and wins that route, otherwise us -
     * unless the watchdog has already proved we are not on the code path, in which case saying
     * "dsr" would be a lie and "unknown" is the honest answer.
     */
    private static String owner(RouteFuse.Capability capability, String competingModId) {
        if (competingModId != null && ModIds.present(competingModId))
            return competingModId;
        if (RouteFuse.capabilityLost(capability))
            return "unknown";
        return "dsr";
    }

    /** The {@code sounds} field, which has two ids because the mod was renamed. */
    private static String soundsFlag() {
        final String id = ModIds.firstPresent(ModIds.SOUNDS, ModIds.SONANCE);
        return id == null ? "absent" : id;
    }

    /**
     * The part the reader needs and cannot infer: what the shader pack does to the routes we
     * still own. Stated as a consequence, not as a warning - a pack in use is a choice, and the
     * mod's object layer (drips, icicles, dew, snow) keeps working underneath it.
     */
    private static String notes() {
        StringBuilder sb = new StringBuilder();
        if (ModIds.shadersInUse())
            sb.append("shader pack in use: sky, fog and cloud COLOUR belong to the pack, the "
                    + "ground-object layer (drips/icicles/dew/snow) does not");
        if (ModIds.isAny(ModIds.CACHE_MODS))
            sb.append(sb.length() == 0 ? "" : "; ")
                    .append("caching mod present (").append(ModIds.firstPresent(ModIds.CACHE_MODS))
                    .append("): if textures or sounds look stale after a resource pack change, it is that");
        return sb.toString();
    }

    /** Warn-level sibling of {@link #log}. Same guard: reporting must never be what fails. */
    private static void warn(String message) {
        try {
            ContainerManager.resolve(IModLog.class).warn(message);
        } catch (Throwable t) {
            // Nothing to do - see log().
        }
    }

    private static void log(String message) {
        try {
            ContainerManager.resolve(IModLog.class).info(message);
        } catch (Throwable t) {
            // Reporting must never be the thing that fails.
        }
    }
}
