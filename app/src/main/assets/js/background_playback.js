// YT Ext — Background Playback Script
// Overrides the Page Visibility API to prevent YouTube from pausing on tab/app switch
(function() {
    'use strict';

    // Override document.visibilityState to always report 'visible'
    Object.defineProperty(document, 'visibilityState', {
        get: function() { return 'visible'; },
        configurable: true,
    });

    // Override document.hidden to always report false
    Object.defineProperty(document, 'hidden', {
        get: function() { return false; },
        configurable: true,
    });

    // Override document.webkitHidden for older WebView
    Object.defineProperty(document, 'webkitHidden', {
        get: function() { return false; },
        configurable: true,
    });

    // Block visibilitychange events
    const originalAddEventListener = document.addEventListener;
    document.addEventListener = function(type, listener, options) {
        if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
            // Don't register the listener — this prevents YouTube from
            // detecting that the tab/app has been backgrounded
            return;
        }
        return originalAddEventListener.call(this, type, listener, options);
    };

    // Also prevent pause caused by the Page Lifecycle API
    const originalDispatchEvent = document.dispatchEvent;
    document.dispatchEvent = function(event) {
        if (event.type === 'visibilitychange' || event.type === 'webkitvisibilitychange') {
            return false;
        }
        return originalDispatchEvent.call(this, event);
    };

    console.log('[YTExt] Background playback override initialized');
})();
