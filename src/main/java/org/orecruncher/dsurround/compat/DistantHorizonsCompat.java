package org.orecruncher.dsurround.compat;

import java.lang.reflect.Field;

/**
 * The one question we need answered about Distant Horizons: has it taken the vanilla fog?
 *
 * <p>DH's fog mixin injects at the RETURN of {@code FogRenderer.setupFog} - after Forge's
 * {@code ViewportEvent.RenderFog}, which is where this mod's fog lives - and when it decides to
 * cancel vanilla fog it pushes the fog planes to {@code 420694206942069}. Whatever we wrote a
 * moment earlier is gone. That is not a bug in either mod; it is what "DH renders its own fog"
 * means. What matters for us is knowing which of the two is in charge, because a fog value that
 * is silently overwritten every frame is exactly the kind of thing this project keeps losing
 * debugging sessions to.</p>
 *
 * <p>DH only cancels when its own {@code enableVanillaFog} option is OFF - with it on (the
 * shipping default) vanilla fog stays and our range applies as usual. So the hand-off must read
 * that option rather than the mod's presence, or every DH user loses a feature that works.</p>
 *
 * <p><b>Reflection, deliberately, and shallowly.</b> The option lives in DH's internal config
 * ({@code Config.Client.Advanced.Graphics.Fog.enableVanillaFog}), which is not API and has no
 * stability promise; linking against it would add a compile dependency on a mod we do not ship.
 * The chain is walked once, then only the resolved getter is called. Every failure path - DH
 * absent, a field renamed, a value type changed, a security manager - answers "DH does not own
 * the fog", which is the answer that leaves this mod's own behaviour alone. The project rule is
 * the same one Create's compat class states: a failed probe is never worse than not probing.</p>
 */
public final class DistantHorizonsCompat {

    /** DH's config root. Internal on purpose - see the class comment. */
    private static final String CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config";

    /**
     * The config path to {@code enableVanillaFog}, every spelling DH has shipped.
     *
     * <p>Found the hard way: the first probe used the 3.3.4 sources' capitalisation and DH 3.3.3's
     * bytecode has {@code Config.client.advanced.graphics.fog} (all lower case, all static fields),
     * so the probe failed on the very pack it was written for. Both spellings are listed now, and
     * the order does not matter because only one of them can exist.</p>
     */
    private static final String[] CLIENT_NAMES = { "client", "Client" };
    private static final String[] ADVANCED_NAMES = { "advanced", "Advanced" };
    private static final String[] GRAPHICS_NAMES = { "graphics", "Graphics" };
    private static final String[] FOG_NAMES = { "fog", "Fog" };
    private static final String[] VANILLA_FOG_NAMES = { "enableVanillaFog" };

    /** Resolved accessors, or null when the probe failed. Guarded by {@link #PROBE_LOCK}. */
    private static final Object PROBE_LOCK = new Object();
    private static volatile boolean probed = false;
    private static volatile Object option = null;
    private static volatile java.lang.reflect.Method getter = null;

    private DistantHorizonsCompat() {
    }

    /**
     * Whether Distant Horizons is cancelling the vanilla terrain fog right now.
     *
     * <p>Read live rather than cached: DH's config screen can flip the option without a restart,
     * and a cached answer would keep this mod out of the fog for the rest of the session. The call
     * is a resolved virtual {@code get()} on a boolean box - no reflection lookup, no allocation
     * beyond the box itself, once per fog pass.</p>
     */
    public static boolean ownsVanillaFog() {
        if (!ModIds.present(ModIds.DISTANT_HORIZONS))
            return false;
        probe();
        final Object target = option;
        final java.lang.reflect.Method read = getter;
        if (target == null || read == null)
            return false;
        try {
            final Object value = read.invoke(target);
            // enableVanillaFog == false means DH cancels vanilla fog. Anything else (true, null,
            // a value of an unexpected type) means it does not, and we keep our own fog.
            return value instanceof Boolean enabled && !enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Walks {@code Config.Client.Advanced.Graphics.Fog.enableVanillaFog} once.
     *
     * <p>Failure is silent by design and permanent: a mod whose config moved is not going to move
     * back mid-session, and retrying a failed reflection every frame would be worse than the
     * problem. The result is visible in the startup summary, which reports {@code fog=dsr} either
     * way - the reader can compare it with what they see.</p>
     */
    private static void probe() {
        if (probed)
            return;
        synchronized (PROBE_LOCK) {
            if (probed)
                return;
            Throwable failure = null;
            try {
                Object node = Class.forName(CONFIG_CLASS);
                node = field(node, CLIENT_NAMES);
                node = field(node, ADVANCED_NAMES);
                node = field(node, GRAPHICS_NAMES);
                node = field(node, FOG_NAMES);
                final Object resolved = field(node, VANILLA_FOG_NAMES);
                final java.lang.reflect.Method resolvedGetter = resolved.getClass().getMethod("get");
                option = resolved;
                getter = resolvedGetter;
            } catch (Throwable t) {
                option = null;
                getter = null;
                failure = t;
            } finally {
                probed = true;
            }
            // Never silent. The first version of this probe looked for one spelling of each
            // field, found none of them in DH 3.3.3 (whose fields are all lower case, and all
            // static), and answered "DH does not own the fog" for the rest of the session - which
            // is the fail-closed answer, so nothing broke, but nothing was reported either. One
            // line here is the difference between a silent no and a five-minute diagnosis.
            if (failure != null)
                log("Distant Horizons fog probe failed (" + failure
                        + "); DSR keeps its own fog range. This is expected if DH changes its config layout.");
        }
    }

    /**
     * Reads a field by any of its known spellings.
     *
     * <p>Two things vary across DH's own versions and both are handled here rather than guessed
     * at: the capitalisation ({@code Config.client} in 3.3.3's bytecode against
     * {@code Config.Client} in the 3.3.4 sources) and whether a node is a static field or an
     * instance one (3.3.3 declares every level static). A name that does not resolve simply moves
     * on to the next candidate, and a chain that resolves nothing fails the whole probe, which the
     * caller treats as "not DH's fog" - today's behaviour, now with a line in the log.</p>
     */
    private static Object field(final Object owner, final String[] names) throws Exception {
        Exception last = null;
        for (final String name : names) {
            try {
                final Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
                final Field found = type.getField(name);
                return java.lang.reflect.Modifier.isStatic(found.getModifiers())
                        ? found.get(null)
                        : found.get(owner);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new NoSuchFieldException(java.util.Arrays.toString(names));
    }

    private static void log(final String message) {
        try {
            org.orecruncher.dsurround.lib.di.ContainerManager
                    .resolve(org.orecruncher.dsurround.lib.logging.IModLog.class)
                    .info("[COMPAT] " + message);
        } catch (Throwable t) {
            // Nothing left to report with - see every other logging guard in this batch.
        }
    }
}
