// YT Ext — Download Button Injection
//
// The old version observed document.body with `subtree: true` and re-bound click
// handlers on every mutation. On a video page that is thousands of callbacks per
// minute, and every callback was another set of listeners on the same button.
// This version uses a single button instance, bounded retries for the action bar,
// and a cheap watchdog that only runs `getElementById`.
(function() {
    'use strict';

    // Idempotent guard. m.youtube.com re-evaluates injected scripts on the same
    // document, so without this every SPA transition would stack another observer.
    if (window.__ytExtDownloadInjected) return;
    window.__ytExtDownloadInjected = true;

    const BUTTON_ID = 'yt-ext-download-btn';
    const CONTAINER_ID = 'yt-ext-download-container';
    const PROGRESS_ID = 'yt-ext-download-progress';
    const VIDEO_ID_RE = /(?:[?&]v=|shorts\/|embed\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/;

    const RETRY_DELAY_MS = 350;
    const MAX_ATTEMPTS = 12;
    const WATCHDOG_MS = 3000;
    const LONG_PRESS_MS = 550;

    // The action bar stays untouched apart from one wrapper. The progress pill is a
    // fixed-position element, so it cannot be clipped by the action bar's overflow
    // and does not push YouTube's own buttons around while downloading.
    const containerStyle = `
        display: inline-flex;
        align-items: center;
        justify-content: center;
        position: relative;
        flex: 0 0 auto;
    `;

    // YouTube action bar button style (matches .ytSpecButtonShapeNextHost buttons in action bar).
    const buttonStyle = `
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 6px;
        height: 36px;
        min-width: 36px;
        padding: 0 10px;
        border: none;
        border-radius: 18px;
        background: transparent;
        color: #fff;
        font: 500 13px/1 "YouTube Sans", "Roboto", sans-serif;
        letter-spacing: 0.2px;
        cursor: pointer;
        white-space: nowrap;
        user-select: none;
        -webkit-tap-highlight-color: transparent;
    `;

    let retryToken = 0;
    let pressTimer = null;

    function currentVideoId() {
        const match = window.location.href.match(VIDEO_ID_RE);
        return match ? match[1] : null;
    }

    function isVideoPage() {
        return currentVideoId() !== null;
    }

    function findActionBar() {
        // m.youtube.com bottom action bar (Like, Dislike, Share, Download, etc.)
        return document.querySelector('.slim-video-action-bar-actions');
    }

    function createDownloadIcon() {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '24');
        svg.setAttribute('height', '24');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2.5');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.style.cssText = 'display:block; flex-shrink:0;';

        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4');
        svg.appendChild(path);

        const polyline = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
        polyline.setAttribute('points', '7 10 12 15 17 10');
        svg.appendChild(polyline);

        const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
        line.setAttribute('x1', '12');
        line.setAttribute('y1', '15');
        line.setAttribute('x2', '12');
        line.setAttribute('y2', '3');
        svg.appendChild(line);

        return svg;
    }

    function getDirectVideoSrc() {
        const video = document.querySelector('video');
        if (!video) return null;

        const src = video.currentSrc || video.src;
        if (!src || src.startsWith('blob:')) return null;

        // Avoid returning the current page URL (happens when src is not set)
        if (src === window.location.href) return null;

        return src;
    }

    let activeProgressPayload = null;
    let progressHideTimer = null;
    // Monotonic progress state. Kotlin filters by task id as well, but the page keeps
    // its own high-water mark so a late or reordered tick can never move the bar back.
    let activeDownloadKey = null;
    let lastPercent = -1;
    let awaitingNewDownload = false;

    function ensureProgressPill() {
        let pill = document.getElementById(PROGRESS_ID);
        if (pill) return pill;

        pill = document.createElement('div');
        pill.id = PROGRESS_ID;
        pill.style.cssText = `
            position: fixed;
            z-index: 2147483647;
            display: none;
            min-width: 88px;
            max-width: 156px;
            padding: 5px 8px 6px;
            border-radius: 10px;
            background: rgba(17, 17, 17, 0.94);
            color: #fff;
            font: 500 10px/1.25 "Roboto", "YouTube Sans", sans-serif;
            letter-spacing: 0.1px;
            box-shadow: 0 4px 14px rgba(0, 0, 0, 0.42);
            pointer-events: none;
            text-align: center;
            box-sizing: border-box;
        `;

        const label = document.createElement('div');
        label.id = 'yt-ext-download-progress-label';
        label.style.cssText = 'margin-bottom:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
        pill.appendChild(label);

        const track = document.createElement('div');
        track.style.cssText = 'height:3px;border-radius:2px;background:rgba(255,255,255,0.25);overflow:hidden;';
        const fill = document.createElement('div');
        fill.id = 'yt-ext-download-progress-fill';
        fill.style.cssText = 'height:100%;width:0%;border-radius:2px;background:#ffd400;transition:width 0.25s linear,opacity 0.2s linear;';
        track.appendChild(fill);
        pill.appendChild(track);

        document.body.appendChild(pill);
        return pill;
    }

    function positionProgressPill() {
        const pill = document.getElementById(PROGRESS_ID);
        if (!pill || pill.style.display === 'none') return;

        const btn = document.getElementById(BUTTON_ID);
        if (!btn || !document.body.contains(btn)) {
            pill.style.display = 'none';
            return;
        }

        const rect = btn.getBoundingClientRect();
        const pillRect = pill.getBoundingClientRect();
        const margin = 6;
        let left = rect.left + rect.width / 2 - pillRect.width / 2;
        left = Math.max(margin, Math.min(left, window.innerWidth - pillRect.width - margin));

        let top = rect.bottom + 5;
        if (top + pillRect.height > window.innerHeight - margin) {
            top = rect.top - pillRect.height - 5;
        }

        pill.style.left = left + 'px';
        pill.style.top = Math.max(margin, top) + 'px';
    }

    function hideProgressPill(clearPayload) {
        if (progressHideTimer !== null) {
            clearTimeout(progressHideTimer);
            progressHideTimer = null;
        }
        if (clearPayload) activeProgressPayload = null;
        const pill = document.getElementById(PROGRESS_ID);
        if (pill) pill.style.display = 'none';
    }

    function renderProgress(payload) {
        const pill = ensureProgressPill();
        const label = document.getElementById('yt-ext-download-progress-label');
        const fill = document.getElementById('yt-ext-download-progress-fill');
        if (!label || !fill) return;

        const state = payload.state || 'downloading';
        let percent = typeof payload.percent === 'number' ? payload.percent : -1;
        let color = '#ffd400';

        if (state === 'complete') {
            color = '#34d399';
            percent = 100;
        } else if (state === 'error') {
            color = '#f87171';
            percent = percent >= 0 ? percent : 100;
        }

        fill.style.background = color;
        if (percent >= 0) {
            fill.style.width = Math.max(0, Math.min(100, percent)) + '%';
            fill.style.opacity = '1';
        } else {
            // Unknown total: show a full, dim bar rather than a stalled 0%.
            fill.style.width = '100%';
            fill.style.opacity = '0.45';
        }

        if (state === 'downloading') {
            label.textContent = percent >= 0
                ? percent + '%' + (payload.detail || '')
                : (payload.text || 'Downloading…');
        } else if (state === 'processing') {
            label.textContent = percent >= 0
                ? 'Finishing… ' + percent + '%'
                : (payload.text || 'Finishing…');
        } else {
            label.textContent = payload.text || (percent >= 0 ? percent + '%' : 'Downloading…');
        }
        pill.style.display = 'block';
        positionProgressPill();

        if (progressHideTimer !== null) {
            clearTimeout(progressHideTimer);
            progressHideTimer = null;
        }
        if (state === 'complete' || state === 'error') {
            progressHideTimer = setTimeout(function() {
                hideProgressPill(true);
            }, 8000);
        }
    }

    // Called from Kotlin through WebView.evaluateJavascript once per progress tick.
    window.__ytExtDownloadProgress = function(payload) {
        if (!payload) return;

        // A download belongs to one video; do not draw its progress on another one.
        const videoId = currentVideoId();
        if (payload.videoId && videoId && payload.videoId !== videoId) {
            hideProgressPill(false);
            return;
        }

        const incomingId = payload.downloadId != null ? String(payload.downloadId) : null;

        if (payload.reset) {
            // A new download starts at 0. If its id is already known, remember it;
            // otherwise accept the next id that arrives.
            activeDownloadKey = incomingId;
            awaitingNewDownload = incomingId === null;
            lastPercent = -1;
        } else if (incomingId !== null) {
            if (awaitingNewDownload || activeDownloadKey === null) {
                activeDownloadKey = incomingId;
                awaitingNewDownload = false;
                lastPercent = -1;
            } else if (incomingId !== activeDownloadKey) {
                // Late callback from an older task. Never let it overwrite the
                // percentage of the download the user is watching.
                return;
            }
        }

        if (typeof payload.percent === 'number' && payload.percent >= 0) {
            payload.percent = Math.max(payload.percent, lastPercent);
            lastPercent = payload.percent;
        }

        if (payload.state === 'complete') {
            payload.percent = 100;
            lastPercent = 100;
        }

        activeProgressPayload = payload;
        renderProgress(payload);
    };

    function onButtonClick() {
        const url = window.location.href;
        const title = document.title.replace(' - YouTube', '').trim();
        const directStreamUrl = getDirectVideoSrc();

        if (window.YTExtDownloader && typeof window.YTExtDownloader.requestDownload === 'function') {
            window.YTExtDownloader.requestDownload(url, title, directStreamUrl);
        }
    }

    function showDownloadProgress() {
        if (window.YTExtDownloader && typeof window.YTExtDownloader.showProgress === 'function') {
            window.YTExtDownloader.showProgress();
        }
    }

    function cancelLongPress() {
        if (pressTimer === null) return;
        clearTimeout(pressTimer);
        pressTimer = null;
    }

    function startLongPress() {
        cancelLongPress();
        pressTimer = setTimeout(function() {
            pressTimer = null;
            showDownloadProgress();
        }, LONG_PRESS_MS);
    }

    function createButton() {
        const btn = document.createElement('button');
        btn.id = BUTTON_ID;
        btn.type = 'button';
        btn.className = 'ytSpecButtonShapeNextHost ytSpecButtonShapeNextFilled ytSpecButtonShapeNextTonal ytSpecButtonShapeNextSizeM ytSpecButtonShapeNextIconButton ytSpecButtonShapeNextMainstageIconSize';
        btn.style.cssText = buttonStyle;
        btn.setAttribute('aria-label', 'Download');
        btn.appendChild(createDownloadIcon());

        // Bound once, when the element is created. Re-binding on every refresh was
        // what made a single tap open several download sheets.
        btn.addEventListener('click', onButtonClick);
        btn.addEventListener('contextmenu', function(event) {
            event.preventDefault();
            showDownloadProgress();
        });
        btn.addEventListener('touchstart', startLongPress, { passive: true });
        btn.addEventListener('touchend', cancelLongPress);
        btn.addEventListener('touchcancel', cancelLongPress);
        btn.addEventListener('mousedown', startLongPress);
        btn.addEventListener('mouseup', cancelLongPress);
        btn.addEventListener('mouseleave', cancelLongPress);

        return btn;
    }

    function removeButton() {
        const container = document.getElementById(CONTAINER_ID);
        if (container) container.remove();

        // Defensive: older injections may have appended the button directly.
        const btn = document.getElementById(BUTTON_ID);
        if (btn) btn.remove();

        hideProgressPill(false);
    }

    function injectButton() {
        if (!isVideoPage()) {
            removeButton();
            return false;
        }

        const actionBar = findActionBar();
        if (!actionBar) return false;

        let container = document.getElementById(CONTAINER_ID);
        if (!container) {
            container = document.createElement('div');
            container.id = CONTAINER_ID;
            container.style.cssText = containerStyle;
        }

        let btn = document.getElementById(BUTTON_ID);
        if (!btn) {
            btn = createButton();
            container.appendChild(btn);
        } else if (btn.parentNode !== container) {
            btn.remove();
            container.appendChild(btn);
        }

        // Insert as the LAST item (after Share, Save, etc.)
        if (container.parentNode !== actionBar) actionBar.appendChild(container);

        // Re-apply an in-flight download after a YouTube re-render. Only for the
        // video that actually owns the download.
        if (activeProgressPayload) {
            const state = activeProgressPayload.state || 'downloading';
            const payloadVideoId = activeProgressPayload.videoId;
            const videoId = currentVideoId();
            const sameVideo = !payloadVideoId || !videoId || payloadVideoId === videoId;
            if (sameVideo && (state === 'downloading' || state === 'processing')) {
                renderProgress(activeProgressPayload);
            }
        }

        return true;
    }

    function refreshWithRetries() {
        const token = ++retryToken;
        let attempts = 0;

        (function attempt() {
            if (token !== retryToken) return;
            if (injectButton()) return;
            if (++attempts >= MAX_ATTEMPTS) return;
            setTimeout(attempt, RETRY_DELAY_MS);
        })();
    }

    function refresh() {
        if (isVideoPage()) refreshWithRetries();
        else {
            retryToken++;
            removeButton();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', refresh, { once: true });
    } else {
        refresh();
    }

    // Re-inject on SPA navigations.
    ['yt-navigate-finish', 'state-navigateend'].forEach(function(eventName) {
        window.addEventListener(eventName, refresh);
    });

    // The action bar is re-rendered by YouTube. The retries above cover its first
    // appearance; this watchdog only puts the button back if YouTube dropped it.
    setInterval(function() {
        if (!isVideoPage()) return;
        if (!document.getElementById(BUTTON_ID)) refreshWithRetries();
    }, WATCHDOG_MS);

    // The pill is fixed-position, so it has to follow the button when the page
    // scrolls or the player resizes. It returns immediately while hidden.
    setInterval(positionProgressPill, 500);

    console.log('[YTExt] Download button initialized');
})();
