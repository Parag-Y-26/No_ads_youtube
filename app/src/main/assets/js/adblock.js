// YT Ext — Smooth Ad Blocker
//
// The previous version re-seeked the ad video to its end every 250 ms. Every seek
// made the player buffer, which is exactly the "video freezes after an ad" symptom.
// This version:
//   * fast-forwards the ad instead of seeking it (no buffer flush),
//   * seeks at most once per ad, as a fallback,
//   * observes only the player's class attribute instead of the whole document,
//   * hides ad UI with CSS instead of deleting nodes the player owns.
(function() {
    'use strict';

    // Idempotent guard. m.youtube.com re-evaluates injected scripts on the same
    // document, so without this every SPA transition would stack another observer.
    if (window.__ytExtAdBlockLoaded) return;
    window.__ytExtAdBlockLoaded = true;

    const STYLE_ID = 'yt-ext-adblock-style';
    const PLAYER_SELECTOR = '#movie_player, .html5-video-player';

    // Clicking one of these ends the ad cleanly.
    const SKIP_BUTTON_SELECTORS = [
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button',
    ];

    // Ads ignore normal playback-rate changes on some builds; re-apply while the
    // ad class is present, but at a low frequency so it never competes with video
    // rendering.
    const AD_PLAYBACK_RATE = 16;
    const RATE_REAPPLY_MS = 500;
    const WATCH_INTERVAL_MS = 250;
    const SEEK_FALLBACK_MS = 1800;
    const ATTACH_INTERVAL_MS = 1500;

    // CSS to hide ad containers. `opacity: 0` on the ad video replaces the old
    // `visibility: hidden`: it keeps the element in the render tree so the main
    // video does not have to relayout when the ad ends.
    const adCSS = `
        .html5-video-player.ad-showing video,
        .html5-video-player.ad-interrupting video {
            opacity: 0 !important;
        }
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-text-overlay,
        .ytp-ad-skip-button-container,
        .ytp-ad-player-overlay,
        .ytp-ad-image-overlay,
        .video-ads,
        #player-ads,
        #masthead-ad,
        #ad_creative_3,
        .ytd-banner-promo-renderer,
        .ytd-statement-banner-renderer,
        .ytd-in-feed-ad-layout-renderer,
        .ytd-ad-slot-renderer,
        .ytd-display-ad-renderer,
        .ytd-promoted-sparkles-web-renderer,
        .ytd-companion-slot-renderer,
        .ytd-action-companion-ad-renderer,
        ytd-promoted-video-renderer,
        ytd-display-ad-renderer,
        ytd-ad-slot-renderer,
        .ad-container,
        .ad-div,
        .masthead-ad-control,
        .sparkles-light-cta,
        [layout="display-ad-layout-key"],
        .ytd-merch-shelf-renderer,
        .ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],
        #related ytd-promoted-sparkles-web-renderer {
            display: none !important;
        }
    `;

    let enabled = true;
    let observer = null;
    let observedPlayer = null;
    let attachInterval = null;
    let watchTimer = null;

    let mutedByUs = false;
    let adActive = false;
    let adStartedAt = 0;
    let soughtThisAd = false;
    let adPlaybackRateBefore = 1;
    let lastRateAt = 0;

    function isEnabled() {
        const config = window.__ytExtConfig;
        // Before the config object arrives, behave like the old default (enabled).
        return !config || config.adBlock !== false;
    }

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = adCSS;
        (document.head || document.documentElement).appendChild(style);
    }

    function removeStyle() {
        const style = document.getElementById(STYLE_ID);
        if (style) style.remove();
    }

    function playerElement() {
        return document.querySelector(PLAYER_SELECTOR);
    }

    function videoElement() {
        return document.querySelector('video');
    }

    function isAdShowing(player) {
        return !!player && (player.classList.contains('ad-showing') ||
            player.classList.contains('ad-interrupting'));
    }

    // --- audio ---------------------------------------------------------------

    // Muting is not free to undo: setting `video.muted` alone makes YouTube believe
    // the *user* muted, and the next video then starts silent. Use the player API
    // when it exists so the persisted state is restored too.
    function playerApi() {
        const player = document.getElementById('movie_player');
        return player && typeof player.mute === 'function' ? player : null;
    }

    function isPlayerMuted(video) {
        const player = playerApi();
        if (player && typeof player.isMuted === 'function') return player.isMuted();
        return !!(video && video.muted);
    }

    function muteForAd(video) {
        if (mutedByUs) return;
        if (isPlayerMuted(video)) return;

        mutedByUs = true;
        const player = playerApi();
        if (player) player.mute();
        else if (video) video.muted = true;
    }

    function restoreAudio(video) {
        if (!mutedByUs) return;
        mutedByUs = false;

        const player = playerApi();
        if (player && typeof player.unMute === 'function') player.unMute();
        else if (video) video.muted = false;
    }

    // --- fast-forward ---------------------------------------------------------

    function applyAdRate() {
        const video = videoElement();
        const player = playerApi();

        if (video) {
            try {
                if (video.playbackRate !== AD_PLAYBACK_RATE) {
                    video.playbackRate = AD_PLAYBACK_RATE;
                }
            } catch (_) {
            }
        }

        if (player && typeof player.setPlaybackRate === 'function') {
            try {
                player.setPlaybackRate(AD_PLAYBACK_RATE);
            } catch (_) {
            }
        }

        lastRateAt = Date.now();
    }

    function restoreRate() {
        const rate = adPlaybackRateBefore > 0 ? adPlaybackRateBefore : 1;
        const video = videoElement();
        const player = playerApi();

        if (video) {
            try {
                video.playbackRate = rate;
            } catch (_) {
            }
        }

        if (player && typeof player.setPlaybackRate === 'function') {
            try {
                player.setPlaybackRate(rate);
            } catch (_) {
            }
        }
    }

    function clickSkipButton() {
        for (const selector of SKIP_BUTTON_SELECTORS) {
            const button = document.querySelector(selector);
            if (button && !button.disabled) {
                button.click();
                return true;
            }
        }
        return false;
    }

    // --- ad lifecycle ---------------------------------------------------------

    function enterAd() {
        if (adActive) return;

        adActive = true;
        adStartedAt = Date.now();
        soughtThisAd = false;

        const video = videoElement();
        adPlaybackRateBefore =
            video && video.playbackRate > 0 ? video.playbackRate : 1;

        muteForAd(video);
        applyAdRate();
        clickSkipButton();
        scheduleWatch(0);
    }

    function leaveAd() {
        if (!adActive) return;

        adActive = false;
        if (watchTimer !== null) {
            clearTimeout(watchTimer);
            watchTimer = null;
        }

        restoreAudio(videoElement());
        restoreRate();
    }

    /**
     * Runs only while an ad is on screen. It re-applies the fast-forward until the
     * ad ends and falls back to a single seek for players that cap playbackRate.
     * There is no standing interval after the ad disappears.
     */
    function watch() {
        watchTimer = null;

        if (!enabled) {
            leaveAd();
            return;
        }

        const player = playerElement();
        if (!isAdShowing(player)) {
            leaveAd();
            return;
        }

        if (!adActive) enterAd();

        // The skip button can appear a moment after the ad class.
        clickSkipButton();

        if (Date.now() - lastRateAt >= RATE_REAPPLY_MS) {
            applyAdRate();
        }

        const video = videoElement();
        if (!soughtThisAd && video &&
            isFinite(video.duration) && video.duration > 0 &&
            Date.now() - adStartedAt >= SEEK_FALLBACK_MS &&
            video.currentTime < video.duration - 0.5
        ) {
            soughtThisAd = true;
            try {
                video.currentTime = Math.max(0, video.duration - 0.1);
            } catch (_) {
            }
        }

        scheduleWatch(WATCH_INTERVAL_MS);
    }

    function scheduleWatch(delayMs) {
        if (watchTimer !== null || !adActive) return;
        watchTimer = setTimeout(watch, delayMs);
    }

    // --- observers -----------------------------------------------------------

    function attachObserver() {
        const player = playerElement();
        if (!player) return;
        if (observedPlayer === player && observer) return;

        if (observer) observer.disconnect();

        // Only the player's class changes matter. Watching document.body with
        // `subtree: true` fired on every comment, thumbnail and counter update and
        // was a measurable source of dropped frames.
        observer = new MutationObserver(function() {
            if (!enabled) return;

            const currentPlayer = playerElement();
            if (isAdShowing(currentPlayer)) {
                if (!adActive) enterAd();
                scheduleWatch(0);
            } else if (adActive) {
                leaveAd();
            }
        });

        observer.observe(player, {
            attributes: true,
            attributeFilter: ['class'],
        });
        observedPlayer = player;
    }

    function startAttachInterval() {
        if (attachInterval !== null) return;

        // YouTube replaces the player element on some navigations. This cheap check
        // re-binds the observer without a document-wide MutationObserver.
        attachInterval = setInterval(function() {
            if (!enabled) return;
            if (observedPlayer && observedPlayer.isConnected) return;
            attachObserver();
        }, ATTACH_INTERVAL_MS);
    }

    function stopAttachInterval() {
        if (attachInterval === null) return;
        clearInterval(attachInterval);
        attachInterval = null;
    }

    function shutdown() {
        leaveAd();
        if (observer) {
            observer.disconnect();
            observer = null;
        }
        observedPlayer = null;
        stopAttachInterval();
        removeStyle();
    }

    function startup() {
        enabled = isEnabled();
        if (!enabled) {
            shutdown();
            return;
        }

        ensureStyle();
        attachObserver();
        startAttachInterval();

        const player = playerElement();
        if (isAdShowing(player)) {
            enterAd();
            scheduleWatch(0);
        }
    }

    // --- wiring --------------------------------------------------------------

    window.addEventListener('yt-ext-config', function() {
        const nextEnabled = isEnabled();
        if (nextEnabled === enabled) return;

        enabled = nextEnabled;
        if (enabled) startup();
        else shutdown();
    });

    // Re-applies the stylesheet and observer after an SPA transition.
    ['yt-navigate-finish', 'state-navigateend'].forEach(function(eventName) {
        window.addEventListener(eventName, function() {
            if (!enabled) return;
            ensureStyle();
            attachObserver();
        });
    });

    startup();

    console.log('[YTExt] Ad blocker initialized');
})();
