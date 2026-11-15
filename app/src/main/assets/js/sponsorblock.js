// YT Ext — SponsorBlock Script
(function() {
    'use strict';

    window._ytExtSponsorBlock = {
        segments: [],
        skippedUUIDs: new Set(),
        overlayTimeout: null,

        setSegments: function(newSegments) {
            this.segments = newSegments;
            this.skippedUUIDs.clear();
            console.log('[YTExt SponsorBlock] Loaded ' + newSegments.length + ' segments');
        },

        showOverlay: function(category) {
            // Remove existing overlay
            let existing = document.getElementById('yt-ext-sb-overlay');
            if (existing) existing.remove();

            const overlay = document.createElement('div');
            overlay.id = 'yt-ext-sb-overlay';
            overlay.textContent = 'Skipped: ' + category;
            overlay.style.cssText = [
                'position: fixed',
                'bottom: 80px',
                'left: 50%',
                'transform: translateX(-50%)',
                'background: rgba(0, 0, 0, 0.8)',
                'color: #00d400',
                'padding: 8px 20px',
                'border-radius: 20px',
                'font-size: 14px',
                'font-family: sans-serif',
                'z-index: 999999',
                'pointer-events: none',
                'transition: opacity 0.3s',
            ].join('; ');

            document.body.appendChild(overlay);

            if (this.overlayTimeout) clearTimeout(this.overlayTimeout);
            this.overlayTimeout = setTimeout(function() {
                overlay.style.opacity = '0';
                setTimeout(function() { overlay.remove(); }, 300);
            }, 2000);
        },

        checkAndSkip: function() {
            const video = document.querySelector('video');
            if (!video || !video.duration || video.paused) return;

            const currentTime = video.currentTime;
            for (const segment of this.segments) {
                const key = segment.start + '-' + segment.end;
                if (this.skippedUUIDs.has(key)) continue;

                if (currentTime >= segment.start && currentTime < segment.end - 0.3) {
                    video.currentTime = segment.end;
                    this.skippedUUIDs.add(key);
                    this.showOverlay(segment.category);
                    console.log('[YTExt SponsorBlock] Skipped ' + segment.category +
                        ' from ' + segment.start + ' to ' + segment.end);
                    break;
                }
            }
        },

        start: function() {
            const self = this;
            setInterval(function() { self.checkAndSkip(); }, 500);
        }
    };

    window._ytExtSponsorBlock.start();
    console.log('[YTExt] SponsorBlock initialized');
})();
