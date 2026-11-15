// YT Ext — Download Button Injection
(function() {
    'use strict';

    // Idempotent guard. m.youtube.com re-evaluates injected scripts on the same
    // document, so without this every SPA transition would stack another observer.
    if (window.__ytExtDownloadInjected) return;
    window.__ytExtDownloadInjected = true;

    const BUTTON_ID = 'yt-ext-download-btn';
    const VIDEO_ID_RE = /(?:[?&]v=|shorts\/|embed\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/;

    const SVG_NS = 'http://www.w3.org/2000/svg';

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

    function createOrUpdateButton() {
        let btn = document.getElementById('yt-ext-download-btn');
        
        if (!btn) {
            btn = document.createElement('button');
            btn.id = 'yt-ext-download-btn';
            btn.type = 'button';
            btn.className = 'ytSpecButtonShapeNextHost ytSpecButtonShapeNextFilled ytSpecButtonShapeNextTonal ytSpecButtonShapeNextSizeM ytSpecButtonShapeNextIconButton ytSpecButtonShapeNextMainstageIconSize';
            btn.appendChild(createDownloadIcon());
            btn.style.cssText = buttonStyle;
            btn.setAttribute('aria-label', 'Download');
        }

        // Always (re)attach click handler
        btn.onclick = null; // Clear any existing
        btn.addEventListener('click', onButtonClick);

        // Long press handler - show download progress
        btn.oncontextmenu = (e) => {
            e.preventDefault();
            showDownloadProgress();
            return false;
        };
        
        // Also handle long press for touch devices
        let pressTimer = null;
        btn.onmousedown = (e) => {
            if (e.button === 0) {
                pressTimer = setTimeout(() => {
                    showDownloadProgress();
                }, 500);
            }
        };
        btn.onmouseup = () => {
            if (pressTimer) clearTimeout(pressTimer);
        };
        btn.onmouseleave = () => {
            if (pressTimer) clearTimeout(pressTimer);
        };
        
        // Touch events for mobile
        btn.ontouchstart = (e) => {
            pressTimer = setTimeout(() => {
                showDownloadProgress();
            }, 500);
        };
        btn.ontouchend = () => {
            if (pressTimer) clearTimeout(pressTimer);
        };
        btn.ontouchcancel = () => {
            if (pressTimer) clearTimeout(pressTimer);
        };

        // Hover/active states for desktop mode
        btn.onmouseenter = () => btn.style.background = 'rgba(255, 255, 255, 0.1)';
        btn.onmouseleave = () => btn.style.background = 'transparent';
        btn.onmousedown = (e) => {
            if (e.button === 0) btn.style.background = 'rgba(255, 255, 255, 0.15)';
        };
        btn.onmouseup = (e) => {
            if (e.button === 0) btn.style.background = 'rgba(255, 255, 255, 0.1)';
        };

        return btn;
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
        svg.appendChild(polyline)

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

    function onButtonClick() {
        const url = window.location.href;
        const title = document.title.replace(' - YouTube', '').trim();
        const directStreamUrl = getDirectVideoSrc();

        if (window.YTExtDownloader && typeof window.YTExtDownloader.requestDownload === 'function') {
            window.YTExtDownloader.requestDownload(url, title, directStreamUrl);
        }
    }

    function showDownloadProgress() {
        // Request download progress from the native layer
        if (window.YTExtDownloader && typeof window.YTExtDownloader.showProgress === 'function') {
            window.YTExtDownloader.showProgress();
        }
    }

    function injectButton() {
        if (!isVideoPage()) return;
        
        const actionBar = findActionBar();
        if (!actionBar) return;

        const btn = createOrUpdateButton();
        
        // Insert as the LAST button (after Share, Save, etc.)
        if (btn.parentNode !== actionBar) {
            actionBar.appendChild(btn);
        }
    }

    function removeButton() {
        const btn = document.getElementById('yt-ext-download-btn');
        if (btn) btn.remove();
    }

    function refresh() {
        if (isVideoPage()) {
            injectButton();
        } else {
            removeButton();
        }
    }

    // Initial injection
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectButton, { once: true });
    } else {
        injectButton();
    }

    // Re-inject on SPA navigations
    ['yt-navigate-finish', 'state-navigateend'].forEach(function(eventName) {
        window.addEventListener(eventName, function() {
            setTimeout(refresh, 0);
        });
    });

    // Also observe for the action bar appearing after the initial load
    const observer = new MutationObserver(function(mutations) {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.nodeType === Node.ELEMENT_NODE) {
                    if (node.classList && node.classList.contains('slim-video-action-bar-actions')) {
                        injectButton();
                        return;
                    }
                    if (node.querySelector && node.querySelector('.slim-video-action-bar-actions')) {
                        injectButton();
                        return;
                    }
                }
            }
        }
    });

    observer.observe(document.body || document.documentElement, {
        childList: true,
        subtree: true,
    });

    console.log('[YTExt] Download button initialized');
})();