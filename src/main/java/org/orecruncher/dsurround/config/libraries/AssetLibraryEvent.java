package org.orecruncher.dsurround.config.libraries;

import org.orecruncher.dsurround.lib.events.EventingFactory;
import org.orecruncher.dsurround.lib.events.IPhasedEvent;
import org.orecruncher.dsurround.lib.resources.ResourceUtilities;

/**
 * Holder for the asset-library reload event.
 *
 * <p>There used to be a chat line here ("Reloaded configurations") sent after every
 * reload when debug logging was on. The libraries also reload on world join, so that
 * line appeared in chat every time a world loaded - which is not what it was for, and
 * the /dsreload command already reports its own result. The event stays; the message
 * does not.</p>
 */
public class AssetLibraryEvent {

    public static final IPhasedEvent<IReloadEvent> RELOAD = EventingFactory.createPrioritizedEvent(callbacks -> (resourceUtilities, scope) -> {
        for (var callback : callbacks) {
            callback.onReload(resourceUtilities, scope);
        }
    });
}
