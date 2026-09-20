package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
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

public final class SoundFXProcessor {

    private static final IModLog LOGGER = ContainerManager.resolve(IModLog.class);
    private static final Configuration.EnhancedSounds CONFIG =
            ContainerManager.resolve(Configuration.EnhancedSounds.class);
    private static final org.orecruncher.dsurround.sound.IAudioPlayer ECHO_PLAYER =
            ContainerManager.resolve(org.orecruncher.dsurround.sound.IAudioPlayer.class);
    private static final int SOUND_PROCESS_ITERATION = 1000 / 20;   // Match MC client tick rate
    // Upper bound on how many sources are evaluated per processing pass. Dense sound
    // scenes (cave full of mobs) otherwise queue every source's ray-trace work at once,
    // saturating the background pool and stealing CPU from the render thread.
    private static final int MAX_SOURCES_PER_PASS = 32;

    // CHANNEL REAPER: the CountingChannelPool mints a fresh OpenAL source per
    // play and only reclaims it when the sweep observes AL_STOPPED. Channels that
    // never reach that state (buffer-load failure leaves them AL_INITIAL, etc.) hold
    // their source forever; once the pool runs dry every new sound fails silently and
    // only already-playing ambience survives. This reaper force-reclaims channels with
    // no playback activity for REAPER_STUCK_MS, letting vanilla's own sweep pathway
    // (handle.release) return them safely. Runs on the sound engine thread.
    private static final Map<Integer, Long> channelLastActive = new ConcurrentHashMap<>();
    private static final long REAPER_STUCK_MS = 10_000L;
    private static int reaperGate = 0;
    private static int diagCounter = 0;

    static volatile boolean isAvailable;
    // Sparse array to hold references to the SoundContexts of playing sounds. Written by the
    // sound engine thread, read by the background processing worker - hence volatile.
    private static volatile SourceContext[] sources;
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

    static {
        ClientEventHooks.COLLECT_DIAGNOSTICS.register(SoundFXProcessor::onGatherText);
        ClientState.TICK_START.register(SoundFXProcessor::clientTick);
    }

    public static WorldContext getWorldContext() {
        return worldContext;
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

        sources = new SourceContext[AudioUtilities.getMaxSounds()];

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
            if (sources != null) {
                Arrays.fill(sources, null);
                sources = null;
            }
            Effects.deinitialize();
        }
    }

    // ------------------------------------------------------------------ delayed-copy echo
    //
    // A real echo is a second arrival of the sound, so it is produced by playing a second copy later. See
    // One aux send carries one effect and all four are reverb zones, so an echo cannot be an aux send at
    // all - and OpenAL's EAXREVERB early reflections are diffused into the tail by design. A second copy of
    // the sound is the only mechanism that keeps all four zones. See HANDOFF 8.83 and 8.89.
    //
    // Bounds are deliberate. MAX_PENDING caps the queue so a burst of sounds cannot multiply into hundreds
    // of voices; ECHO_MIN_DELAY_MS is the Haas fusion window - a reflection arriving sooner than this is
    // heard as part of the space, not as a separate event, and playing a copy there would only make the
    // sound louder and phasey.
    private static final int MAX_PENDING_ECHOES = 12;
    /**
     * Below this the copy is heard as the sound PLAYING TWICE rather than as an echo.
     *
     * <p>90 ms was too low: measured, a cave's reflections arrive at 92-129 ms, which is the classic
     * slap-back range - long enough to separate from the direct sound, short enough to read as a defect. A
     * discrete echo only sounds like an echo beyond roughly 150 ms, and a cave should get its space from the
     * reverb zones instead.
     */
    private static final long ECHO_MIN_DELAY_MS = 150L;
    private static final long ECHO_MAX_DELAY_MS = 900L;
    /**
     * Overall level of the echo. The geometry already supplies the relative strength, so this only sets how
     * loud the effect is as a whole; a reflection is always quieter than the sound that caused it.
     */
    private static final float ECHO_GAIN = 2.0F;
    /**
     * Below this, the reflection is inaudible against the direct sound and scheduling it is pure waste.
     *
     * <p>Masking, not geometry: a reflection 25 dB down is not heard however late it arrives. At ECHO_GAIN
     * 2.0 this admits a stony valley (~-15 dB) and rejects a wide grassy one (~-33 dB), which is the
     * distinction the ear makes.
     */
    private static final float ECHO_MIN_VOLUME = 0.045F;

    private record PendingEcho(SoundInstance sound, long playAtMs) {}

    /**
     * A delayed copy of a sound, marked so the echo scheduler can recognise it.
     *
     * <p>Without a marker the copy is an ordinary sound instance, so playing it runs it back through
     * {@code onSoundPlay} and it schedules an echo of itself - a decaying train of copies rather than one
     * answer. The marker is the whole reason this is a subclass and not a plain SimpleSoundInstance.
     */
    private static final class EchoSoundInstance extends SimpleSoundInstance {
        EchoSoundInstance(net.minecraft.resources.ResourceLocation location, SoundSource source, float volume,
                float pitch, double x, double y, double z) {
            super(location, source, volume, pitch,
                    org.orecruncher.dsurround.lib.random.Randomizer.current(),
                    false, 0, SoundInstance.Attenuation.NONE, x, y, z, false);
        }
    }

    private static final java.util.ArrayDeque<PendingEcho> pendingEchoes = new java.util.ArrayDeque<>();

    /**
     * Plays any echo whose delay has elapsed. Called once per client tick, like the footstep echo queue.
     */
    private static void drainPendingEchoes() {
        if (pendingEchoes.isEmpty())
            return;
        final long now = System.currentTimeMillis();
        while (!pendingEchoes.isEmpty() && pendingEchoes.peek().playAtMs() <= now) {
            final SoundInstance echo = pendingEchoes.poll().sound();
            try {
                ECHO_PLAYER.play(echo);
            } catch (final Throwable t) {
                // A copy that fails to start must never take the sound system with it.
                LOGGER.debug("ECHO_PLAY failed: %s", t.getClass().getSimpleName());
            }
        }
    }

    /**
     * Schedules a delayed copy of a sound when the geometry says its reflection returns to the listener.
     *
     * <p>Called from {@code SoundFXUtils.calculate} with THAT source's own measurements. It used to be called
     * from the sound-play hook and read them from a static "most recent value", which is whatever source was
     * evaluated last rather than the source being played - the same defect already removed from the
     * early-reflection tap.
     *
     * <p>An evaluation runs about twice a second per source, so the echo is scheduled only once per sound:
     * {@code SourceContext} remembers that this sound has been answered.
     */
    /** Rate limit for the rejection diagnostics; see reject(). */
    private static long lastEchoRejectLogAt = 0L;
    private static final long ECHO_REJECT_LOG_INTERVAL_MS = 1500L;

    /**
     * Reports why an echo was not scheduled.
     *
     * <p>Added because the feature was silent with no evidence: six guards can each reject a candidate and
     * from outside they are indistinguishable, so "no echo" could not be turned into an action. Rate limited
     * so a busy scene cannot flood the log.
     */
    private static void reject(final String reason, final String detail) {
        final long now = System.currentTimeMillis();
        if (now - lastEchoRejectLogAt < ECHO_REJECT_LOG_INTERVAL_MS)
            return;
        lastEchoRejectLogAt = now;
        LOGGER.info("ECHO_REJECT %s | %s", reason, detail);
    }

    public static boolean scheduleEcho(final SoundInstance sound, final WorldContext ctx, final Vec3 soundPos,
            final float share, final float reflectivity) {
        if (!CONFIG.enableDelayedEcho) {
            reject("disabled", "config enableDelayedEcho=false");
            return false;
        }
        if (sound == null) {
            reject("no-sound", "");
            return false;
        }
        // An echo must never echo. The copy is a real sound instance, so it comes back through the FX
        // pipeline; without this guard a valley would answer with a decaying train of copies.
        if (sound instanceof EchoSoundInstance)
            return false;
        if (pendingEchoes.size() >= MAX_PENDING_ECHOES) {
            reject("queue-full", "pending=" + pendingEchoes.size());
            return false;
        }
        // A looping sound has no end to echo, and music or weather is not a discrete event.
        if (sound.isLooping()
                || sound.getSource() == SoundSource.MASTER
                || sound.getSource() == SoundSource.MUSIC
                || sound.getSource() == SoundSource.WEATHER) {
            reject("sound-type", "looping=" + sound.isLooping() + " source=" + sound.getSource());
            return false;
        }
        // A plain's reflections are its own ground, so its share collapses to ~0.015 and it gets no echo.
        if (share <= 0.02F) {
            reject("low-share", String.format("share=%.4f (need >0.02)", share));
            return false;
        }

        final SoundFXUtils.EchoPath path = SoundFXUtils.findEchoPath(ctx, soundPos, share, reflectivity);
        if (path == null) {
            reject("no-path", String.format("share=%.4f refl=%.3f pos=%.1f,%.1f,%.1f ear=%.1f,%.1f,%.1f",
                    share, reflectivity, soundPos.x(), soundPos.y(), soundPos.z(),
                    ctx.playerEyePosition.x(), ctx.playerEyePosition.y(), ctx.playerEyePosition.z()));
            return false;
        }

        // Delay is the extra distance the reflection travels, at the speed of sound.
        final long delayMs = Math.round(path.extraDistance / SoundFXUtils.speedOfSound() * 1000.0D);
        if (delayMs < ECHO_MIN_DELAY_MS || delayMs > ECHO_MAX_DELAY_MS) {
            reject("delay-range", String.format("delay=%dms extra=%.1f blocks (want %d..%d ms)",
                    delayMs, path.extraDistance, ECHO_MIN_DELAY_MS, ECHO_MAX_DELAY_MS));
            return false;
        }

        final float volume = sound.getVolume() * path.gain * ECHO_GAIN;
        // A reflection more than about 25 dB below the direct sound is inaudible however long its delay, so
        // scheduling one only costs a voice. The delay test alone cannot catch that: it says "separate
        // event", not "audible event".
        if (volume < ECHO_MIN_VOLUME) {
            reject("too-quiet", String.format("vol=%.4f gain=%.4f (want >=%.3f)",
                    volume, path.gain, ECHO_MIN_VOLUME));
            return false;
        }

        // Placed at the reflecting surface, played without attenuation: the copy IS the sound arriving from
        // the wall, and the geometry's spreading term already set its level. That also gives correct stereo
        // placement for free - the echo arrives from the direction of the wall, not from the original sound.
        final SoundInstance copy = new EchoSoundInstance(
                sound.getLocation(),
                sound.getSource(),
                volume,
                sound.getPitch(),
                path.surface.x(),
                path.surface.y(),
                path.surface.z());

        pendingEchoes.add(new PendingEcho(copy, System.currentTimeMillis() + delayMs));

        // Logged at INFO on purpose while the feature is being verified: the debug level is off by default,
        // and without this line "I heard nothing" cannot distinguish "no wall was found" from "too quiet".
        LOGGER.info("ECHO_SCHEDULE sound=%s delay=%dms gain=%.3f vol=%.3f at=%.1f,%.1f,%.1f",
                sound.getLocation(), delayMs, path.gain, volume,
                path.surface.x(), path.surface.y(), path.surface.z());
        return true;
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
            if (sources != null && id > 0 && id <= sources.length)
                sources[id - 1] = ctx;
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
            if (sources != null && sc.getId() > 0 && sc.getId() <= sources.length)
                sources[sc.getId() - 1] = null;
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
        drainPendingEchoes();
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
            for (final SourceContext ctx : sources) {
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
        // with no null check (1.20.1 ChannelAccess line 82), so any nulled handle left in
        // the set is an instant NPE on the very next tick - and at world teardown that NPE
        // takes the whole client down with it.
        //
        // The correlation is exact across the retained logs: every session with 0 reaps has
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
                    LOGGER.warn("REAPER: reclaiming stuck channel id=%d (no playback activity for %.1fs)", id, (now - last) / 1000.0);
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
        // The worker can still be draining its queue while deinitialize() nulls the
        // sources array (Worker.stop does not await termination) - bail out quietly.
        if (sources == null)
            return;
        try {
            final ExecutorService pool = threadPool.get();
            assert pool != null;

            final ObjectArray<Future<?>> tasks = new ObjectArray<>(64);

            // Collect the sources due for an update this pass. See
            // SourceContext.UPDATE_FREQUENCY_TICKS for the interval. If the player just
            // entered/left water every source is due so the damping snaps immediately.
            final boolean immediate = immediateUpdateRequested;
            immediateUpdateRequested = false;

            final ObjectArray<SourceContext> due = new ObjectArray<>(64);
            for (final SourceContext ctx : sources) {
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
