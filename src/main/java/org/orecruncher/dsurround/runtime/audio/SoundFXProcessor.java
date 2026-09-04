package org.orecruncher.dsurround.runtime.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
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
            ctx.exec();
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
        var data = sourceContext.dsurround_getData();
        data.ifPresent(sc -> {
            sc.stop();
            sources[sc.getId() - 1] = null;
            channelLastActive.remove(sc.getId());
        });
    }

    /**
     * Injected into SoundSource and will be invoked when a non-streaming sound data stream is attached to the
     * SoundSource.  Take the opportunity to convert the audio stream into mono format if needed.  Note that
     * conversion will take place only if it is enabled in the configuration and the sound is playing
     * non-attenuated.
     *
     * @param source SoundSource for which the audio buffer is being generated
     * @param buffer The buffer in question.
     */

    public static void doMonoConversion(final Channel source, final SoundBuffer buffer) {

        // If disabled, return
        if (!Client.Config.enhancedSounds.enableMonoConversion)
            return;

        var data = ((ISourceContext) source).dsurround_getData();
        data.ifPresent(ctx -> {
            var s = ctx.getSound();
            if (s != null && s.getAttenuation() != SoundInstance.Attenuation.NONE && !s.isRelative())
                Conversion.convert(buffer);
        });
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
            if (++diagCounter % 300 == 0)
                logPoolDiag();
        }
    }

    // Every 15s: pool occupancy (used/max per pool), registered-context count and the
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
                    top.merge(s.getIdentifier().getPath(), 1, Integer::sum);
            }
            final var entries = new ArrayList<>(top.entrySet());
            entries.sort((a, b) -> b.getValue() - a.getValue());
            final var head = new StringBuilder();
            for (int i = 0; i < Math.min(6, entries.size()); i++)
                head.append(i == 0 ? "" : ", ").append(entries.get(i).getKey()).append('x').append(entries.get(i).getValue());
            LOGGER.info("POOL_DIAG %s | registered ctxs=%d | top=[%s]", engine.getChannelDebugString(), registered, head);
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
        if (++reaperGate % 20 != 0)
            return;
        final long now = System.currentTimeMillis();
        final List<Object> reaped = new ArrayList<>();
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
                    ((IChannelHandle) o).dsurround_reap();
                    reaped.add(o);
                    channelLastActive.remove(id);
                }
            } catch (final Throwable ignore) {
            }
        }
        for (final Object o : reaped)
            handles.remove(o);
    }

    /**
     * Separate thread for evaluating the environment for the sound play.  These routines can get a little heavy
     * so offloading to a separate thread to keep it out of either the client tick or sound engine makes sense.
     */
    private static void processSounds() {
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
