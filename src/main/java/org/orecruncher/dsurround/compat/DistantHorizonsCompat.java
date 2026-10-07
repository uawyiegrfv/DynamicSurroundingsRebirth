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
            try {
                Object node = Class.forName(CONFIG_CLASS);
                node = staticField(node, "Client");
                node = instanceField(node, "Advanced");
                node = instanceField(node, "Graphics");
                node = instanceField(node, "Fog");
                final Object resolved = instanceField(node, "enableVanillaFog");
                final java.lang.reflect.Method resolvedGetter = resolved.getClass().getMethod("get");
                option = resolved;
                getter = resolvedGetter;
            } catch (Throwable t) {
                option = null;
                getter = null;
            } finally {
                probed = true;
            }
        }
    }

    /** A public static field's value, or a failure the caller's catch will treat as "no probe". */
    private static Object staticField(final Object owner, final String name) throws Exception {
        if (owner instanceof Class<?> type) {
            final Field field = type.getField(name);
            return field.get(null);
        }
        final Field field = owner.getClass().getField(name);
        return field.get(owner);
    }

    /** A public instance field's value on a config node. */
    private static Object instanceField(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getField(name);
        return field.get(owner);
    }
}
