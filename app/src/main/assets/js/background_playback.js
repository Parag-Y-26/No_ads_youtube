// YT Ext — Background Playback Script
// Overrides the Page Visibility API to prevent YouTube from pausing on tab/app switch.
(function() {
    'use strict';

    // Idempotent guard: the script is re-evaluated on SPA transitions, and the
    // listeners below must be bound exactly once.
    if (window.__ytExtBackgroundPlaybackLoaded) return;
    window.__ytExtBackgroundPlaybackLoaded = true;

    function isEnabled() {
        const config = window.__ytExtConfig;
        // Before the config object arrives, do not override anything. The Kotlin
        // layer pushes the real value right after injection.
        return !config || config.backgroundPlay !== false;
    }

    // The getters are configurable own properties, so they can be removed again
    // when the user turns background playback off. Without this, switching the
    // toggle off would still keep the page reporting itself visible.
    function restore() {
        try { delete document.visibilityState; } catch (_) {}
        try { delete document.hidden; } catch (_) {}
        try { delete document.webkitHidden; } catch (_) {}
    }

    function forceVisible() {
        if (!isEnabled()) {
            restore();
            return;
        }

        Object.defineProperty(document, 'visibilityState', {
            get: () => 'visible',
            configurable: true,
        });

        Object.defineProperty(document, 'hidden', {
            get: () => false,
            configurable: true,
        });

        // Older WebView builds still expose the webkit-prefixed variant.
        Object.defineProperty(document, 'webkitHidden', {
            get: () => false,
            configurable: true,
        });
    }

    forceVisible();

    // Kill the event itself, not just the getters. Registered in the capture phase on
    // `window`, which is the very first stop on the propagation path, so the event is
    // stopped before any of YouTube's handlers (bubble or capture, on document or the
    // player element) can observe that the page was hidden.
    function stopVisibilityEvent(event) {
        if (!isEnabled()) return;
        event.stopImmediatePropagation();
    }

    ['visibilitychange', 'webkitvisibilitychange'].forEach((eventName) => {
        window.addEventListener(eventName, stopVisibilityEvent, true);
        document.addEventListener(eventName, stopVisibilityEvent, true);
    });

    // Re-assert the getters if the SPA swapped the document state back to hidden.
    ['yt-navigate-finish', 'state-navigateend'].forEach((eventName) => {
        window.addEventListener(eventName, forceVisible);
    });

    // Apply toggle changes to the live document.
    window.addEventListener('yt-ext-config', forceVisible);

    console.log('[YTExt] Background playback override initialized');
})();
