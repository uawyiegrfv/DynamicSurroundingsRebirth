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
     * The config path to {@code enableVanillaFog}, as DH actually stores it.
     *
     * <p><b>Two failed attempts are recorded here, because the third one is only obvious after
     * them.</b> DH builds its config as a tree of <em>holder classes</em>: each category is a
     * {@code ConfigCategory} whose payload is the class that declares the next level's fields
     * (bytecode: {@code new ConfigCategory.Builder().set(Config$Client.class).build()}), and the
     * entries are {@code public static} fields on those classes. So:</p>
     *
     * <ul>
     * <li>Walking <em>instances</em> ({@code Config.client.getClass().getField("advanced")}) cannot
     *     work: the value is a plain {@code ConfigCategory}, and the field lives on
     *     {@code Config$Client} - a class the instance does not reveal. That was attempt one, and it
     *     failed with {@code NoSuchFieldException: Advanced} on the very pack it was written for.</li>
     * <li>Guessing at capitalisation does not help either, because the two spellings live in
     *     different places: the <em>classes</em> are {@code Client/Advanced/Graphics/Fog} while
     *     3.3.3's <em>fields</em> are {@code client/advanced/graphics/fog}. Attempt two mixed them
     *     up.</li>
     * <li>Walking the <em>nested classes</em> by name is the path DH's own source uses
     *     ({@code Config.Client.Advanced.Graphics.Fog.enableVanillaFog}), and the jar confirms the
     *     classes are {@code Config$Client$Advanced$Graphics$Fog} in 3.3.3 as well.</li>
     * </ul>
     *
     * <p>Both spellings are still tried at every level, so a rename in either direction degrades to
     * the other one rather than to nothing.</p>
     */
    private static final String[][] HOLDER_LEVELS = {
            { "Client", "client" },
            { "Advanced", "advanced" },
            { "Graphics", "graphics" },
            { "Fog", "fog" }
    };
    /** The leaf field, camelCase in every version seen. */
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
                // Nested classes, not instances: see HOLDER_LEVELS. Each level is a holder class
                // whose static fields are the next level, so the walk never looks at a value.
                Class<?> holder = Class.forName(CONFIG_CLASS);
                for (final String[] level : HOLDER_LEVELS)
                    holder = nestedClass(holder, level);
                final Object entry = field(holder, VANILLA_FOG_NAMES);
                // Prefer DH's own API wrapper when it is there: IDhApiConfigValue.getValue() is the
                // documented read, and it answers with an API override if another mod set one - the
                // raw ConfigEntry.get() would report the file's value and we would hand the fog off
                // (or not) based on a number nobody is using. Falls back to the raw entry when the
                // wrapper is absent, which is today's behaviour.
                final Object resolved = wrapAsApiValue(entry);
                option = resolved;
                getter = readMethod(resolved);            } catch (Throwable t) {
                option = null;
                getter = null;
                failure = t;
            } finally {
                probed = true;
            }            if (failure != null)
                log("Distant Horizons fog probe failed (" + failure
                        + "); DSR keeps its own fog range. This is expected if DH changes its config layout.");
        }
    }

    /**
     * Wraps a resolved DH config entry in DH's own API value object, or returns it unchanged.
     *
     * <p>{@code DhApiConfigValue} is what every other mod talks to (Simple Clouds sets DH's cloud
     * rendering through it, and its {@code setValue(value, caller)} is what produces DH's
     * "Config [x] was set by [y]" line). It implements {@code IDhApiConfigValue}, whose
     * {@code getValue()} is the read that respects an API override - so this is the number to make a
     * hand-off decision on. Constructed reflectively; a failure simply returns the entry, and the
     * caller reads the raw {@code get()} instead.</p>
     */
    /**
     * The value read method: DH's API {@code getValue()} when the object is an API wrapper, the
     * raw {@code get()} otherwise. Resolved once, with the reason stated: which one is available is
     * exactly whether the wrapper was constructed.
     */
    private static java.lang.reflect.Method readMethod(final Object value) throws NoSuchMethodException {
        try {
            return value.getClass().getMethod("getValue");
        } catch (NoSuchMethodException notAnApiValue) {
            return value.getClass().getMethod("get");
        }
    }
    private static Object wrapAsApiValue(final Object entry) {
        try {
            final Class<?> wrapper = Class.forName(
                    "com.seibel.distanthorizons.core.config.api.DhApiConfigValue");
            // Match the constructor by assignability rather than by a guessed parameter type: the
            // wrapper takes a ConfigEntry, and the entry we walk to IS one - but a future DH could
            // hand out a subclass, and a guessed type would then silently skip the wrapper.
            for (final java.lang.reflect.Constructor<?> candidate : wrapper.getConstructors()) {
                final Class<?>[] params = candidate.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(entry))
                    return candidate.newInstance(entry);
            }
        } catch (Throwable t) {
            // Fall through to the raw entry.
        }
        return entry;
    }
    /**
     * The nested holder class of {@code outer} under any of its known spellings.
     *
     * <p>{@code Class.forName} rather than a field read, because the tree is made of classes: DH
     * stores {@code ConfigCategory}s whose payload <em>is</em> the holder class. See
     * {@link #HOLDER_LEVELS} for the two failed walks that led here.</p>
     */
    private static Class<?> nestedClass(final Class<?> outer, final String[] names)
            throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (final String name : names) {
            try {
                return Class.forName(outer.getName() + "$" + name);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException(java.util.Arrays.toString(names));
    }

    /**
     * Reads a public field (static or not) from a class by any of its known spellings.
     *
     * <p>Static is the normal case here - every level of DH's tree is a static field on its holder
     * class - and a static field is read with a null receiver. The instance case is kept because the
     * leaf entries are values, not classes, and a future DH could hand one out per instance.</p>
     */    private static Object field(final Object owner, final String[] names) throws Exception {
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
