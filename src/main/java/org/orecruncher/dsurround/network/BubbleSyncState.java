package org.orecruncher.dsurround.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side handoff for /bubble command payloads: the payload handler (which must
 * stay loadable on the dedicated server, so no client classes here) enqueues here,
 * and the client-side SpeechBubbleHandler drains the queue on its tick and injects
 * the bubbles above the sender's head.
 */
public final class BubbleSyncState {

    public record Pending(UUID sender, String text, int seconds) {}

    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();

    private BubbleSyncState() {
    }

    public static void enqueue(UUID sender, String text, int seconds) {
        QUEUE.add(new Pending(sender, text, seconds));
    }

    public static List<Pending> drain() {
        if (QUEUE.isEmpty())
            return List.of();
        final List<Pending> out = new ArrayList<>();
        Pending pending;
        while ((pending = QUEUE.poll()) != null)
            out.add(pending);
        return out;
    }

    public static void clear() {
        QUEUE.clear();
    }
}
