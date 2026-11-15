// YT Ext — Comprehensive Ad Blocker Script
(function() {
    'use strict';

    // Idempotent guard. m.youtube.com re-evaluates injected scripts on the same
    // document, so without this every SPA transition would stack another
    // MutationObserver on top of the previous ones.
    if (window.__ytExtAdBlockLoaded) return;
    window.__ytExtAdBlockLoaded = true;

    const STYLE_ID = 'yt-ext-adblock-style';
    const PLAYER_SELECTOR = '#movie_player, .html5-video-player';

    // Clicking one of these ends the ad cleanly; the player rebuilds them per ad, so
    // they are looked up on every sweep rather than cached.
    const SKIP_BUTTON_SELECTORS = [
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button',
    ];

    // Coalesces bursts of mutations (an ad insertion changes dozens of nodes) into one
    // sweep. A trailing throttle, not a polling loop.
    const SWEEP_THROTTLE_MS = 120;

    // While an ad is on screen the sweep re-arms itself: the skip button can appear a
    // moment late and the ad player sometimes resets the playhead. This stops by itself
    // on the first sweep that sees the ad gone — there is no standing interval.
    const AD_RETRY_MS = 250;

    // CSS to hide ad containers
    const adCSS = `
        .ad-showing video { visibility: hidden !important; }
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

    // Re-applied after navigation: a channel or search page can be swapped in
    // without <head> surviving, and the rules would silently stop applying.
    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = adCSS;
        (document.head || document.documentElement).appendChild(style);
    }

    ensureStyle();

    function playerContainer() {
        return document.querySelector(PLAYER_SELECTOR);
    }

    function adIsActive(player) {
        return player.classList.contains('ad-showing') ||
            player.classList.contains('ad-interrupting');
    }

    // --- audio ---------------------------------------------------------------

    // Muting is not free to undo, and the player mirrors the element's mute state into
    // its own: setting `video.muted` alone makes YouTube believe the *user* muted, and
    // the next video then starts silent. The player API is used instead so the state it
    // persists is restored as well.
    let mutedByUs = false;

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

        // Only a mute this script applied is ever undone; a mute the user chose stays.
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

    // --- ad handling ---------------------------------------------------------

    function clickSkipButton() {
        for (const selector of SKIP_BUTTON_SELECTORS) {
            const button = document.querySelector(selector);
            if (button) {
                button.click();
                return true;
            }
        }
        return false;
    }

    function seekPastAd(video) {
        if (!video || !isFinite(video.duration) || video.duration <= 0) return;
        // Leave the last few milliseconds alone: assigning the value that is already
        // there fires nothing, while seeking backwards from the very end can re-enter.
        if (video.currentTime < video.duration - 0.05) {
            video.currentTime = video.duration;
        }
    }

    // Remove ad overlay elements
    function removeAdOverlays() {
        const overlays = document.querySelectorAll(
            '.ytp-ad-overlay-container, .ytp-ad-text-overlay, .ytp-ad-image-overlay'
        );
        overlays.forEach(function(el) { el.remove(); });
    }

    function sweep() {
        const player = playerContainer();
        const video = document.querySelector('video');

        if (!player || !adIsActive(player)) {
            restoreAudio(video);
            removeAdOverlays();
            return;
        }

        muteForAd(video);

        // Mute first so no ad audio escapes, then skip. Seeking to the end is what
        // dismisses ads that do not offer a button yet; the click handles the rest.
        seekPastAd(video);
        clickSkipButton();

        removeAdOverlays();

        // Re-arm only while the ad is still there. The sweep that finds it gone
        // returns above and leaves no timer behind.
        scheduleSweep(AD_RETRY_MS);
    }

    let sweepTimer = null;

    function scheduleSweep(delayMs) {
        // A sweep is already queued and will re-read the world when it runs, so
        // additional mutations inside the window are not worth another timer.
        if (sweepTimer !== null) return;

        sweepTimer = setTimeout(function() {
            sweepTimer = null;
            sweep();
        }, delayMs === undefined ? SWEEP_THROTTLE_MS : delayMs);
    }

    // --- observers -----------------------------------------------------------

    const playerObserver = new MutationObserver(function() {
        scheduleSweep();
    });

    let observedPlayer = null;

    function attachPlayerObserver() {
        if (observedPlayer && observedPlayer.isConnected) return;

        const player = playerContainer();
        if (!player) return;

        playerObserver.disconnect();
        playerObserver.observe(player, {
            attributes: true,
            attributeFilter: ['class'],
            // The skip button and the ad overlays are inserted inside the player.
            childList: true,
            subtree: true,
        });
        observedPlayer = player;
    }

    // The player element survives most navigations, but not all of them, so the body
    // is watched only to re-attach the player observer and to hide promoted nodes.
    const rootObserver = new MutationObserver(function(mutations) {
        attachPlayerObserver();

        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.nodeType !== Node.ELEMENT_NODE) continue;

                if (node.tagName && (
                    node.tagName.toLowerCase().includes('ad-slot') ||
                    node.tagName.toLowerCase().includes('promoted')
                )) {
                    node.style.display = 'none';
                }
            }
        }
    });

    let observedRoot = null;

    function observeRoot() {
        const root = document.body || document.documentElement;
        if (!root || root === observedRoot) return;

        rootObserver.disconnect();
        rootObserver.observe(root, { childList: true, subtree: true });
        observedRoot = root;
        attachPlayerObserver();
    }

    observeRoot();

    // Catch an ad that is already on screen when the script is injected.
    scheduleSweep(0);

    // Bound once, thanks to the guard above. Re-runs the setup after each SPA
    // transition instead of relying on a fresh script evaluation.
    ['yt-navigate-finish', 'state-navigateend'].forEach(function(eventName) {
        window.addEventListener(eventName, function() {
            ensureStyle();
            observeRoot();
            attachPlayerObserver();
            scheduleSweep(0);
        });
    });

    console.log('[YTExt] Ad blocker initialized');
})();
