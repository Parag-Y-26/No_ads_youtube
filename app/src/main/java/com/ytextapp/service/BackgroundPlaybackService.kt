package com.ytextapp.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.ytextapp.MainActivity
import java.lang.ref.WeakReference

class BackgroundPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "yt_ext_background_playback"
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "YTExt:BackgroundPlayback"

        /**
         * The WebView currently on screen. Kept as a weak reference because the service
         * can outlive the activity (that is the whole point of it), and a discarded
         * WebView must not be leaked by a running service.
         */
        private var webViewRef: WeakReference<WebView>? = null

        fun start(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
            context.stopService(intent)
        }

        /** Called by the composable when its WebView is created. */
        fun attachWebView(webView: WebView) {
            webViewRef = WeakReference(webView)
        }

        /** Called when the composable leaves composition. */
        fun detachWebView(webView: WebView) {
            if (webViewRef?.get() === webView) webViewRef = null
        }

        /** Runs [script] against the live page, if there is still one. */
        private fun runInPage(script: String) {
            val webView = webViewRef?.get() ?: return
            // MediaSession callbacks can arrive on a binder thread; evaluateJavascript
            // must run on the UI thread that owns the WebView.
            webView.post { webView.evaluateJavascript(script, null) }
        }

        private const val JS_PLAY = """
            (function() {
                // Prefer the player API so YouTube's own play/pause state and UI stay in
                // sync with the notification. The raw <video> element is the fallback.
                var player = document.getElementById('movie_player');
                if (player && typeof player.playVideo === 'function') {
                    player.playVideo();
                    return;
                }
                var video = document.querySelector('video');
                if (video) { video.play(); }
            })();
        """

        private const val JS_PAUSE = """
            (function() {
                var player = document.getElementById('movie_player');
                if (player && typeof player.pauseVideo === 'function') {
                    player.pauseVideo();
                    return;
                }
                var video = document.querySelector('video');
                if (video) { video.pause(); }
            })();
        """

        /**
         * "Next" is not part of the HTML media API, so this drives YouTube's own player.
         * `movie_player.nextVideo()` is what the on-page next control calls internally and
         * is locale-independent — matching the button by aria-label does not survive a
         * translated UI. The DOM selectors stay as fallbacks for other player builds.
         */
        private const val JS_NEXT = """
            (function() {
                var player = document.getElementById('movie_player');
                if (player && typeof player.nextVideo === 'function') {
                    player.nextVideo();
                    return;
                }
                var selectors = [
                    '.ytp-next-button',
                    'button[aria-label="Next video"]',
                    '.next-button',
                    '.mobile-next-button',
                    'ytm-next-button button'
                ];
                for (var i = 0; i < selectors.length; i++) {
                    var button = document.querySelector(selectors[i]);
                    if (button) { button.click(); return; }
                }
                document.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'N', shiftKey: true, bubbles: true
                }));
            })();
        """
    }

    private var mediaSession: MediaSessionCompat? = null

    /** Holds the CPU awake so audio does not stall while the screen is off. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        // Android 14 (API 34) wants the foreground service type stated explicitly at
        // start time. Older releases infer it from the manifest and reject the 3-arg
        // overload, so both paths are needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquired only after the service is legitimately in the foreground, so a
        // rejected startForeground() cannot leave the lock held with no service.
        acquireWakeLock()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Wires the transport controls to the page. Every command is forwarded to the
     * WebView as JavaScript, so the notification, Bluetooth buttons and the lock screen
     * all end up driving the same `<video>` element the user is watching.
     */
    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "YTExtMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    runInPage(JS_PLAY)
                    updatePlaybackState(isPlaying = true)
                }

                override fun onPause() {
                    runInPage(JS_PAUSE)
                    updatePlaybackState(isPlaying = false)
                }

                override fun onSkipToNext() {
                    runInPage(JS_NEXT)
                }
            })

            // Media button handling needs no flags on API 21+: the session receives
            // them by default. The old FLAG_HANDLES_* constants are deprecated no-ops.
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_TITLE,
                        getString(com.ytextapp.R.string.notification_title),
                    )
                    .build()
            )

            isActive = true
        }

        // Playback is already running when the user flips the switch, so start optimistic;
        // onPlay/onPause keep it honest afterwards.
        updatePlaybackState(isPlaying = true)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    /**
     * A partial wake lock is intentionally held without a timeout: background audio can
     * run indefinitely, and the lock is released in [onDestroy] whenever the service is
     * stopped. The system still kills the service under memory pressure, which is why
     * START_STICKY re-acquires it on restart.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                // Not reference counted: repeated onStartCommand calls must not require
                // a matching number of release() calls.
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.ytextapp.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.ytextapp.R.string.notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // MediaButtonReceiver routes each of these back into the MediaSession callback
        // above, which is what forwards them to the WebView.
        val playAction = androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_PLAY,
        )
        val pauseAction = androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_PAUSE,
        )
        val nextAction = androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.ytextapp.R.string.notification_title))
            .setContentText(getString(com.ytextapp.R.string.notification_text))
            .setSmallIcon(com.ytextapp.R.drawable.ic_notification_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                com.ytextapp.R.drawable.ic_notification_play,
                getString(com.ytextapp.R.string.action_play),
                playAction,
            )
            .addAction(
                com.ytextapp.R.drawable.ic_notification_pause,
                getString(com.ytextapp.R.string.action_pause),
                pauseAction,
            )
            .addAction(
                com.ytextapp.R.drawable.ic_notification_next,
                getString(com.ytextapp.R.string.action_next),
                nextAction,
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
