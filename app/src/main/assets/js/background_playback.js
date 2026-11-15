// YT Ext — Background Playback Script
// Overrides the Page Visibility API to prevent YouTube from pausing on tab/app switch
(function() {
    'use strict';

    // Idempotent guard: the script is re-evaluated on SPA transitions, and the
    // listeners below must be bound exactly once.
    if (window.__ytExtBackgroundPlaybackLoaded) return;
    window.__ytExtBackgroundPlaybackLoaded = true;

    // The getters are plain data properties, so re-applying them is harmless and
    // lets us repair the state after a transition.
    function forceVisible() {
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

    console.log('[YTExt] Background playback override initialized');
})();
