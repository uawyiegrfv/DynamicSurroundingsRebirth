package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.apache.commons.lang3.StringUtils;
import org.orecruncher.dsurround.Client;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.eventing.ClientEventHooks;
import org.orecruncher.dsurround.eventing.CollectDiagnosticsEvent;
import org.orecruncher.dsurround.lib.Singleton;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.threading.Worker;
import org.orecruncher.dsurround.mixinutils.IChannelHandle;
import org.orecruncher.dsurround.runtime.audio.effects.Effects;
import org.orecruncher.dsurround.mixinutils.ISourceContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class SoundFXProcessor {

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    private static final int SOUND_PROCESS_ITERATION = 1000 / 20;   // Match MC client tick rate
    // Upper bound on how many sources are evaluated per processing pass. Dense sound
    // scenes (cave full of mobs) otherwise queue every source's ray-trace work at once,
    // saturating the background pool and stealing CPU from the render thread.
    private static final int MAX_SOURCES_PER_PASS = 32;

    // CHANNEL REAPER (1.21.1): the CountingChannelPool mints a fresh OpenAL source per
    // play and only reclaims it when the sweep observes AL_STOPPED. Channels that never
    // reach that state (buffer-load failure leaves them AL_INITIAL, etc.) hold their
    // source forever; once the pool runs dry every new sound fails silently and only
    // already-playing ambience survives. This reaper force-reclaims channels with no
    // playback activity for REAPER_STUCK_MS, letting vanilla's own sweep pathway
    // (handle.release) return them safely. Runs on the sound engine thread.
    private static final Map<Integer, Long> channelLastActive = new ConcurrentHashMap<>();
    private static final long REAPER_STUCK_MS = 10_000L;
    private static int reaperGate = 0;
    private static int diagCounter = 0;

    static volatile boolean isAvailable;
    // Sparse array to hold references to the SoundContexts of playing sounds. Written by the
    // sound engine thread, read by the background processing worker.
    //
    // An AtomicReferenceArray, not a plain array: volatile on the REFERENCE says nothing about the
    // ELEMENTS. A context is built on the sound thread (new SourceContext, attachSound, enable) and
    // only then published into the array, so without a volatile element write the worker has no
    // happens-before edge and may observe a non-null context whose fields are still default. x86
    // hides this in practice, which is exactly why it would be a nasty one to debug if it ever did
    // surface - and this class already has a history of teardown races (see inFlightEvaluations).
    private static volatile AtomicReferenceArray<SourceContext> sources;
    private static Worker soundProcessor;
    private static volatile String diagnosticString = StringUtils.EMPTY;
    // Set on the client thread when the player enters or leaves water. The background
    // processor drains it, re-evaluates every source immediately and snaps the water
    // damping, so entering/exiting water reacts with no audible lag.
    private static volatile boolean immediateUpdateRequested;
    private static boolean lastPlayerUnderWater;
    // The listener's skylight drives the openness term, which decides how muffled everything is. Sources are
    // only re-evaluated every UPDATE_FREQUENCY_TICKS (0.5 s), which made walking out of a cave mouth step
    // rather than fade: the openness moved in 0.5 s jumps. Skylight changes exactly when the transition is
    // happening, so a change forces the same immediate re-evaluation that entering water does. Standing still
    // changes nothing, so this costs nothing in normal play.
    private static int lastListenerSkyLight = -1;
    private static long lastSkyLightUpdateAt;
    /** Shortest gap between skylight-triggered re-evaluations, so a boundary cannot thrash. */
    private static final long SKY_LIGHT_UPDATE_INTERVAL_MS = 100L;

    // Use our own thread pool avoiding the common pool.  Thread allocation is better controlled, and we won't run
    // into/cause any problems with other tasks in the common pool. Daemon threads so a pool that outlives the
    // sound system teardown (the pool itself is cached in a Singleton and reused) never blocks JVM shutdown.
    private static final Singleton<ExecutorService> threadPool = new Singleton<>(() -> {
        var config = ContainerManager.resolve(Configuration.EnhancedSounds.class);
        int threads = config.backgroundThreadWorkers;
        if (threads == 0)
            threads = 2;
        LOGGER.info("Threads allocated to enhanced sound processor: %d", threads);
        final AtomicInteger counter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(threads, r -> {
            final var t = new Thread(r, "dsurround-soundfx-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    });

    // Snapshot of world/player state for the background threads. Rebuilt every client tick;
    // volatile so the worker always observes a fully constructed snapshot.
    private static volatile WorldContext worldContext = new WorldContext();

    /**
     * Monotonic count of sound-processing passes, incremented once per processSounds() iteration
     * (20 a second - the worker runs at the client tick rate). SourceContext reads it to measure how
     * many ticks actually elapsed between two evaluations of the same source, which is what lets the
     * smoothing hold a constant response time in SECONDS while the evaluation interval still varies
     * with distance.
     */
    private static volatile long passCounter;

    /**
     * The evaluations submitted by the most recent pass, so teardown can wait for them.
     *
     * <p>Without this there is a use-after-free window. {@code processSounds} submits each evaluation to
     * {@link #threadPool}, a SEPARATE executor from the one {@code Worker} owns - so {@code Worker.stop()}
     * awaiting termination waits for the worker THREAD, not for the evaluations that thread submitted. If
     * the worker is interrupted on stop (its 2 s await expires) its evaluations keep running, and
     * {@code deinitialize} then calls {@code Effects.deinitialize()}, which deletes the OpenAL filter and
     * effect objects those evaluations upload to. A slot's own {@code isInitialized} check cannot close
     * that window, because the deletion happens after the check has passed.
     *
     * <p>The sound system is torn down and rebuilt on a config reload, a device change and a
     * reverb/occlusion toggle, so this is a reachable path rather than a theoretical one. It would present
     * as a rare crash or a silent OpenAL error rather than a reproducible failure, which is the worst kind.
     */
    private static volatile ObjectArray<Future<?>> inFlightEvaluations;

    static {
        ClientEventHooks.COLLECT_DIAGNOSTICS.register(SoundFXProcessor::onGatherText);
        ClientState.TICK_START.register(SoundFXProcessor::clientTick);
    }

    public static WorldContext getWorldContext() {
        return worldContext;
    }

    /** Passes completed since startup; see {@link #passCounter}. */
    public static long passCounter() {
        return passCounter;
    }

    /**
     * Indicates if the SoundFX feature is available.
     *
     * @return true if the feature is available, false otherwise.
     */
    public static boolean isAvailable() {
        return isAvailable;
    }

    public static void initialize() {
        Effects.initialize();

        sources = new AtomicReferenceArray<>(AudioUtilities.getMaxSounds());

        if (soundProcessor == null) {
            soundProcessor = new Worker(
                    "Enhanced Sound Processor",
                    SoundFXProcessor::processSounds,
                    SOUND_PROCESS_ITERATION,
                    LOGGER
            );
            soundProcessor.start();
        }

        isAvailable = true;
    }

    public static void deinitialize() {
        if (isAvailable()) {
            isAvailable = false;
            if (soundProcessor != null) {
                soundProcessor.stop();
                soundProcessor = null;
            }
            // Wait for evaluations that stop() may not have covered. They run on threadPool, which is a
            // different executor from the worker's, and they upload to the OpenAL objects that
            // Effects.deinitialize() is about to delete. See inFlightEvaluations.
            awaitInFlightEvaluations();
            if (sources != null) {
                for (int i = 0; i < sources.length(); i++)
                    sources.set(i, null);
                sources = null;
            }
            Effects.deinitialize();
        }
    }

    /**
     * Waits for the evaluations of the last pass, so nothing is still uploading when the effects are
     * deleted. Bounded, because a hung evaluation must not hang the client's shutdown; each evaluation is
     * a ray trace and a handful of OpenAL calls, so this returns immediately in practice.
     */
    private static void awaitInFlightEvaluations() {
        final ObjectArray<Future<?>> pending = inFlightEvaluations;
        inFlightEvaluations = null;
        if (pending == null)
            return;
        pending.forEach(task -> {
            try {
                task.get(1, TimeUnit.SECONDS);
            } catch (final Throwable ignored) {
                // Timed out, cancelled or failed: either way it is no longer something to wait on.
            }
        });
    }

    private static boolean shouldIgnoreSound(SoundInstance sound) {
        if (sound.isRelative()
                || sound.getSource() == SoundSource.MASTER
                || sound.getSource() == SoundSource.MUSIC
                || sound.getSource() == SoundSource.WEATHER)
            return true;
        // Non-attenuated (NONE) sounds are still processed when they carry a real position -
        // the player's own footsteps render centered (stereo, no distance attenuation) yet still
        // benefit from reverb zones and water damping, matching the original 1.12.2 where
        // footsteps ran through the sound effect processing. NONE sounds at the origin (config
        // preview, background loops) stay ignored.
        if (sound.getAttenuation() == SoundInstance.Attenuation.NONE)
            return sound.getX() == 0.0D && sound.getY() == 0.0D && sound.getZ() == 0.0D;
        return false;
    }

    /**
     * Callback hook from an injection.  This callback is made on the client thread after the sound source
     * is created, but before it is configured.
     *
     * @param sound The sound that is going to play
     * @param entry The ChannelManager.Entry instance for the sound play
     */
    public static void onSoundPlay(final SoundInstance sound, final ChannelAccess.ChannelHandle entry) {

        if (!isAvailable())
            return;

        if (shouldIgnoreSound(sound))
            return;

        ISourceContext source = (ISourceContext)(((IChannelHandle) entry).dsurround_getSource());
        assert source != null;
        int id = source.dsurround_getId();
        if (id > 0) {
            final SourceContext ctx = new SourceContext(id);
            ctx.attachSound(sound);
            ctx.enable();
            source.dsurround_setData(ctx);
            channelLastActive.put(id, System.currentTimeMillis());
        }
    }

    /**
     * Invoked when the sound source is played.  This will cause the environment to be evaluated
     * before the sound instance is processed.
     */
    public static void onSourcePlay(final Channel source) {
        var context = (ISourceContext) source;
        var data = context.dsurround_getData();
        data.ifPresent(ctx -> {
            var id = ctx.getId();
            if (AudioTuning.evaluateOnSoundThread()) {
                // Default. Evaluate before the sound is processed so the very first frame already
                // has the right occlusion/reverb. This is the only place the DSP still runs on the
                // sound engine thread, and it is per sound start - a cave full of mobs or rapid
                // block breaking starts many at once and serialises them here.
                ctx.exec();
            } else {
                // Defer to the worker. The sound engine's per-source tick uploads whatever has been
                // computed, so the source runs with default settings for at most one worker cycle
                // (50 ms) and then settles. Less sound-thread work, at the cost of a short settle.
                ctx.markImmediate();
            }
            if (sources != null && id > 0 && id <= sources.length())
                sources.set(id - 1, ctx);
        });
    }

    /**
     * Callback hook from an injection.  Will be invoked by the sound processing thread when checking status, which
     * essentially is a "tick".
     *
     * @param source SoundSource being ticked
     */
    public static void tick(final Channel source) {
        var src = (ISourceContext) source;
        var data = src.dsurround_getData();
        data.ifPresent(SourceContext::tick);
    }

    /**
     * Injected into SoundSource and will be invoked when a sound source is being terminated.
     * Runs on the sound engine thread, which makes it the right place to release the
     * source's OpenAL filter objects.
     *
     * @param source SoundSource that is stopping
     */
    public static void stopSoundPlay(final Channel source) {
        var sourceContext = (ISourceContext) source;
        final int id = sourceContext.dsurround_getId();
        // Always drop the reaper's activity timestamp - even context-less channels get
        // one (afterChannelSweep refreshes every playing channel), and a stale entry can
        // mis-time a future channel that recycles the same OpenAL source id.
        channelLastActive.remove(id);
        var data = sourceContext.dsurround_getData();
        data.ifPresent(sc -> {
            sc.stop();
            if (sources != null && sc.getId() > 0 && sc.getId() <= sources.length())
                sources.set(sc.getId() - 1, null);
        });
    }

    /**
     * Gate for the upload-point mono buffer selection (see MixinSource
     * dsurround_selectAlBuffer): positioned static sounds bind a derived mono AL
     * buffer so OpenAL can localize them, while the local player's own sounds keep
     * the stereo buffer and its stereo image.
     */
    public static boolean isMonoSelectionEnabled() {
        return Client.Config.enhancedSounds.enableMonoConversion;
    }

    /**
     * Invoked on a client tick. Establishes the current world context for further computation..
     */
    public static void clientTick(Minecraft client) {
        if (isAvailable()) {
            worldContext = new WorldContext();
            final boolean underWater = worldContext.player != null && worldContext.player.isUnderWater();
            if (underWater != lastPlayerUnderWater) {
                lastPlayerUnderWater = underWater;
                immediateUpdateRequested = true;
            }
            // The openness term reads the listener's skylight, so a change there means the muffling is mid
            // transition and the next scheduled evaluation is up to half a second away. Force it now.
            final int skyLight = SoundFXUtils.listenerSkyLight(worldContext);
            if (skyLight != lastListenerSkyLight) {
                final long now = System.currentTimeMillis();
                if (now - lastSkyLightUpdateAt >= SKY_LIGHT_UPDATE_INTERVAL_MS) {
                    lastSkyLightUpdateAt = now;
                    immediateUpdateRequested = true;
                }
                lastListenerSkyLight = skyLight;
            }
            if (++diagCounter % 1200 == 0)
                logPoolDiag();
        }
    }

    // Every 60s: pool occupancy (used/max per pool), registered-context count and the
    // top registered sound events. Detects channel leaks long before the pool starves.
    private static void logPoolDiag() {
        try {
            var engine = AudioUtilities.soundEngine();
            if (engine == null)
                return;
            int registered = 0;
            final Map<String, Integer> top = new HashMap<>();
            for (int i = 0; i < sources.length(); i++) {
                final SourceContext ctx = sources.get(i);
                if (ctx == null)
                    continue;
                registered++;
                var s = ctx.getSound();
                if (s != null)
                    top.merge(s.getLocation().getPath(), 1, Integer::sum);
            }
            final var entries = new ArrayList<>(top.entrySet());
            entries.sort((a, b) -> b.getValue() - a.getValue());
            final var head = new StringBuilder();
            for (int i = 0; i < Math.min(6, entries.size()); i++)
                head.append(i == 0 ? "" : ", ").append(entries.get(i).getKey()).append('x').append(entries.get(i).getValue());
            LOGGER.info("POOL_DIAG %s | registered ctxs=%d | top=[%s]", engine.getDebugString(), registered, head);
        } catch (final Throwable ignore) {
        }
    }

    /**
     * Called from MixinChannelAccess at scheduleTick TAIL - runs on the sound engine
     * thread after vanilla's own sweep, so walking/mutating the channel set is safe.
     * Channels with no playback activity for REAPER_STUCK_MS are force-released
     * (handle.release -> library.releaseChannel) and removed from the channel set, so
     * the next sweep cannot trip over their nulled channel.
     */
    public static void afterChannelSweep(final Set<?> handles) {
        if (!isAvailable())
            return;
        // DISABLED BY DEFAULT. Setting a handle's channel to null while it is still in
        // vanilla's live set is what crashes the client: ChannelAccess' own sweep does
        //     handle.channel.stop();
        // with no null check, so any nulled handle left in the set is an instant NPE on the
        // very next tick - and at world teardown that NPE takes the whole client down.
        //
        // The correlation is exact across the retained logs: every session with 0 reaps had
        // 0 of these NPEs, and the two sessions that reaped (113 and 322 times) produced
        // 20 583 and 940 of them.
        //
        // The stuck channels this was written for are real, but losing a few OpenAL sources
        // to them is strictly better than crashing. Left switchable for anyone who wants to
        // experiment with the reclamation.
        if (!ContainerManager.resolve(Configuration.EnhancedSounds.class).enableChannelReaper)
            return;
        // While the game is paused every live channel sits in AL_PAUSED and playing()
        // reports false - the reaper would mistake the whole lot for stuck channels and
        // destroy them. Skip entirely while paused (the sweep resumes on unpause).
        if (GameUtils.isPaused())
            return;
        if (++reaperGate % 20 != 0)
            return;
        final long now = System.currentTimeMillis();
        final List<Object> reaped = new ArrayList<>();
        final List<Integer> reapedIds = new ArrayList<>();
        for (final Object o : handles) {
            try {
                Channel channel = ((IChannelHandle) o).dsurround_getSource();
                if (channel == null)
                    continue;
                int id = ((ISourceContext) channel).dsurround_getId();
                if (channel.playing()) {
                    channelLastActive.put(id, now);
                    continue;
                }
                Long last = channelLastActive.get(id);
                if (last != null && now - last > REAPER_STUCK_MS) {
                    String soundName = "<no ctx>";
                    if (id > 0 && sources != null && id - 1 < sources.length() && sources.get(id - 1) != null && sources.get(id - 1).getSound() != null)
                        soundName = sources.get(id - 1).getSound().getLocation().toString();
                    LOGGER.warn("REAPER: reclaiming stuck channel id=%d (no playback activity for %.1fs) sound=%s", id, (now - last) / 1000.0, soundName);
                    reaped.add(o);
                    reapedIds.add(id);
                }
            } catch (final Throwable ignore) {
            }
        }
        // Remove from vanilla's live set BEFORE releasing, and only after the iteration is
        // done. The old order (release first, remove after) left a window in which a handle
        // with a nulled channel was still in the set - which is exactly the crash above.
        for (int i = 0; i < reaped.size(); i++) {
            final Object o = reaped.get(i);
            try {
                handles.remove(o);
                ((IChannelHandle) o).dsurround_reap();
                channelLastActive.remove(reapedIds.get(i));
            } catch (final Throwable ignore) {
            }
        }
    }

    /**
     * Separate thread for evaluating the environment for the sound play.  These routines can get a little heavy
     * so offloading to a separate thread to keep it out of either the client tick or sound engine makes sense.
     */
    private static void processSounds() {
        // Take ONE snapshot of the array reference. deinitialize() nulls this field from another thread,
        // so reading it twice - once to test, once to iterate - leaves a window where the second read
        // sees null and the iteration throws. Holding the reference also keeps the array identity steady
        // while the sound thread registers and unregisters contexts in it.
        final AtomicReferenceArray<SourceContext> snapshot = sources;
        // The worker can still be draining its queue while deinitialize() nulls the
        // sources array (Worker.stop does not await termination) - bail out quietly.
        if (snapshot == null)
            return;
        // One pass = one worker iteration = one client tick (SOUND_PROCESS_ITERATION).
        passCounter++;
        try {
            final ExecutorService pool = threadPool.get();
            assert pool != null;

            final ObjectArray<Future<?>> tasks = new ObjectArray<>(64);
            // Published so deinitialize() can wait for these before the effects are deleted.
            inFlightEvaluations = tasks;

            // Collect the sources due for an update this pass. See
            // SourceContext.UPDATE_FREQUENCY_TICKS for the interval. If the player just
            // entered/left water every source is due so the damping snaps immediately.
            final boolean immediate = immediateUpdateRequested;
            immediateUpdateRequested = false;

            final ObjectArray<SourceContext> due = new ObjectArray<>(64);
            for (int i = 0; i < snapshot.length(); i++) {
                final SourceContext ctx = snapshot.get(i);
                if (ctx != null && (immediate || ctx.shouldExecute())) {
                    due.add(ctx);
                }
            }

            // New pass: the listener openness cache is invalidated here, so the 32 openness rays are cast
            // once for the whole batch instead of once per source.
            SoundFXUtils.beginPass();

            // In a dense sound scene (e.g. a cave full of mobs) many sources come due at
            // once; saturating the pool queues work behind distant sources whose reverb is
            // barely audible anyway. Keep the closest sources first so near-field occlusion
            // stays responsive, and cap per-pass work to bound pool/CPU usage.
            if (due.size() > MAX_SOURCES_PER_PASS) {
                final var listener = worldContext.playerEyePosition;
                due.sort((a, b) -> Float.compare(
                        (float) a.getPosition().distanceToSqr(listener),
                        (float) b.getPosition().distanceToSqr(listener)));
            }
            final int limit = Math.min(due.size(), MAX_SOURCES_PER_PASS);
            for (int i = 0; i < limit; i++) {
                if (immediate)
                    due.get(i).markImmediate();
                tasks.add(pool.submit(due.get(i)));
            }

            diagnosticString = "(ticked: %d)".formatted(tasks.size());

            tasks.forEach(task -> {
                try {
                    // This will cause this thread to block waiting for
                    // a result. Since they are processed in order, the amount
                    // of time spent blocking will be minimal.
                    task.get();
                } catch (InterruptedException | ExecutionException ignored) {
                }
            });

            // The pass is done, so nothing is left for teardown to wait on. Clearing it keeps the
            // reference from pinning the last batch's Futures for the life of the session.
            if (inFlightEvaluations == tasks)
                inFlightEvaluations = null;

        } catch (final Throwable t) {
            LOGGER.error(t, "Error in enhanced sound processor worker");
        }
    }

    /**
     * Gather diagnostics for the display
     */
    private static void onGatherText(CollectDiagnosticsEvent event ) {
        if (isAvailable() && soundProcessor != null) {
            final String msg = soundProcessor.getDiagnosticString() + " " + diagnosticString;
            event.add(CollectDiagnosticsEvent.Section.Systems, msg);
        } else {
            event.getSectionText(CollectDiagnosticsEvent.Section.Systems).add(Component.literal("Enhanced sound processing disabled"));
        }
    }
}
