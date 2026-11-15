// YT Ext — Comprehensive Ad Blocker Script
(function() {
    'use strict';

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

    const style = document.createElement('style');
    style.textContent = adCSS;
    document.head.appendChild(style);

    // Skip video ads by clicking skip button or setting currentTime
    function skipAd() {
        // Click skip button if available
        const skipButton = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
        if (skipButton) {
            skipButton.click();
            return;
        }

        // If ad is playing, try to skip to end
        const player = document.querySelector('.ad-showing video');
        if (player && player.duration && isFinite(player.duration)) {
            player.currentTime = player.duration;
        }
    }

    // Remove ad overlay elements
    function removeAdOverlays() {
        const overlays = document.querySelectorAll(
            '.ytp-ad-overlay-container, .ytp-ad-text-overlay, .ytp-ad-image-overlay'
        );
        overlays.forEach(function(el) { el.remove(); });
    }

    // MutationObserver to catch dynamically inserted ads
    const observer = new MutationObserver(function(mutations) {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.nodeType !== Node.ELEMENT_NODE) continue;

                // Check if an ad started playing
                if (node.classList && (
                    node.classList.contains('ytp-ad-module') ||
                    node.classList.contains('ytp-ad-overlay-container') ||
                    node.classList.contains('ad-showing')
                )) {
                    skipAd();
                    removeAdOverlays();
                }

                // Check for promoted content
                if (node.tagName && (
                    node.tagName.toLowerCase().includes('ad-slot') ||
                    node.tagName.toLowerCase().includes('promoted')
                )) {
                    node.style.display = 'none';
                }
            }

            // Check attribute changes on the player
            if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
                const target = mutation.target;
                if (target.classList && target.classList.contains('ad-showing')) {
                    skipAd();
                }
            }
        }
    });

    // Observe the entire body for changes
    observer.observe(document.body || document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['class'],
    });

    // Periodic check as fallback
    setInterval(function() {
        skipAd();
        removeAdOverlays();
    }, 1000);

    console.log('[YTExt] Ad blocker initialized');
})();
