// YT Ext — SponsorBlock Script
(function() {
    'use strict';

    // Idempotent guard. Without it every SPA transition would add another set of
    // listeners, so the same segment would be skipped several times per event.
    if (window.__ytExtSponsorBlockLoaded) return;
    window.__ytExtSponsorBlockLoaded = true;

    const VIDEO_ID_RE = /(?:[?&]v=|shorts\/|embed\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/;

    // A seek fires `seeked`/`timeupdate` almost immediately. Without a quiet window the
    // handler can re-enter, still see the playhead inside the same segment and seek
    // again — an infinite loop that freezes the player at the segment boundary.
    const SKIP_DEBOUNCE_MS = 250;

    // Skip only when the playhead is not already at the tail of the segment, so a seek
    // that landed a hair short cannot bounce back and forth.
    const SEGMENT_EDGE_TOLERANCE = 0.3;

    const CATEGORY_LABELS = {
        sponsor: 'Sponsor',
        selfpromo: 'Self-promo',
        interaction: 'Interaction reminder',
        intro: 'Intro',
        outro: 'Outro',
        preview: 'Preview',
        music_offtopic: 'Non-music section',
        filler: 'Filler',
    };

    function currentVideoId() {
        const match = window.location.href.match(VIDEO_ID_RE);
        return match ? match[1] : null;
    }

    window._ytExtSponsorBlock = {
        segments: [],
        skippedUUIDs: new Set(),
        overlayTimeout: null,
        // Which video the currently held timestamps belong to.
        videoId: null,
        // Timestamp of the last seek this script performed, for the debounce window.
        lastSkipAt: 0,

        setSegments: function(newSegments, videoId) {
            const id = videoId || currentVideoId();

            // A response can land after the user already moved on. Ignore it rather
            // than applying the wrong video's timestamps to the new player.
            if (id && id !== currentVideoId()) {
                console.log('[YTExt SponsorBlock] Dropped stale segments for ' + id);
                return;
            }

            this.segments = newSegments || [];
            this.videoId = id;
            this.skippedUUIDs.clear();
            console.log('[YTExt SponsorBlock] Loaded ' + this.segments.length +
                ' segments for ' + id);
        },

        resetForNavigation: function() {
            const id = currentVideoId();
            if (id === this.videoId) return;

            // New video: the old timestamps are meaningless. Clear them so nothing is
            // skipped until the Kotlin side delivers a fresh set.
            this.segments = [];
            this.skippedUUIDs.clear();
            this.videoId = id;
            this.clearOverlay();
        },

        clearOverlay: function() {
            if (this.overlayTimeout) {
                clearTimeout(this.overlayTimeout);
                this.overlayTimeout = null;
            }
            const existing = document.getElementById('yt-ext-sb-overlay');
            if (existing) existing.remove();
        },

        /**
         * Small pill above the bottom bar: informational only, never intercepts taps
         * and never covers the player controls.
         */
        showOverlay: function(category) {
            this.clearOverlay();

            const label = CATEGORY_LABELS[category] || category;
            const overlay = document.createElement('div');
            overlay.id = 'yt-ext-sb-overlay';
            overlay.textContent = 'Skipped ' + label;
            overlay.style.cssText = [
                'position: fixed',
                'bottom: 84px',
                'left: 50%',
                'transform: translateX(-50%)',
                'background: rgba(0, 0, 0, 0.72)',
                'color: #8ff08f',
                'padding: 6px 14px',
                'border-radius: 16px',
                'font-size: 12px',
                'font-family: sans-serif',
                'letter-spacing: 0.2px',
                'box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35)',
                'z-index: 2147483646',
                'pointer-events: none',
                'opacity: 0',
                'transition: opacity 0.2s ease-in',
            ].join('; ');

            (document.body || document.documentElement).appendChild(overlay);

            // Next frame, so the fade-in actually animates.
            requestAnimationFrame(function() {
                overlay.style.opacity = '1';
            });

            this.overlayTimeout = setTimeout(function() {
                overlay.style.opacity = '0';
                setTimeout(function() { overlay.remove(); }, 220);
            }, 1800);
        },

        checkAndSkip: function() {
            const video = document.querySelector('video');
            if (!video || !video.duration || video.paused || video.ended) return;

            // Debounce window. Checked before anything else so a burst of media events
            // caused by our own seek is discarded cheaply.
            const now = Date.now();
            if (now - this.lastSkipAt < SKIP_DEBOUNCE_MS) return;

            const currentTime = video.currentTime;
            for (const segment of this.segments) {
                const key = segment.start + '-' + segment.end;
                if (this.skippedUUIDs.has(key)) continue;

                if (currentTime >= segment.start && currentTime < segment.end - SEGMENT_EDGE_TOLERANCE) {
                    // Mark first: assigning currentTime synchronously queues a seek, and
                    // a second pass must never be able to act on the same segment.
                    this.skippedUUIDs.add(key);
                    this.lastSkipAt = now;

                    // Never seek past the end of a video whose segment metadata runs long.
                    const target = Math.min(segment.end, video.duration);
                    video.currentTime = target;

                    this.showOverlay(segment.category);
                    console.log('[YTExt SponsorBlock] Skipped ' + segment.category +
                        ' from ' + segment.start + ' to ' + segment.end);
                    break;
                }
            }
        },

        start: function() {
            this.checkAndSkip();
        }
    };

    const sb = window._ytExtSponsorBlock;
    sb.start();

    // Capture phase on `document`: media events do not bubble, and YouTube replaces the
    // <video> element between videos, so a listener bound to the element would go stale.
    ['timeupdate', 'seeked', 'playing'].forEach(function(eventName) {
        document.addEventListener(eventName, function() {
            sb.checkAndSkip();
        }, true);
    });

    // Bound exactly once thanks to the guard above.
    ['yt-navigate-finish', 'state-navigateend'].forEach(function(eventName) {
        window.addEventListener(eventName, function() {
            sb.resetForNavigation();
            sb.checkAndSkip();
        });
    });

    console.log('[YTExt] SponsorBlock initialized');
})();
