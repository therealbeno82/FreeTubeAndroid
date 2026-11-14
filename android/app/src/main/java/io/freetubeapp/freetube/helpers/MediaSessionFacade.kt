package io.freetubeapp.freetube.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.session.PlaybackState.STATE_BUFFERING
import android.media.session.PlaybackState.STATE_PAUSED
import android.media.session.PlaybackState.STATE_PLAYING
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import io.freetubeapp.freetube.KeepAliveService
import io.freetubeapp.freetube.MediaControlsReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MediaSessionFacade(
  private val context: Context,
  private val channelId: String,
  private val dispatchMediaEvent: (String) -> Unit = {},
  private val dispatchPositionEvent: (Long) -> Unit = {},
  private var state: Int = STATE_PAUSED,
  private val notificationId: Int = 2,
  private val notificationTag: String = "media_controls"
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val notificationManager = NotificationManagerCompat.from(context)
  private var session: MediaSession? = null
  private var notification: android.app.Notification? = null
  private var playbackPosition: Long? = null
  private var lastNotifiedPlaying: Boolean? = null

  init {
    context.createNotificationChannel(channelId)
  }

  private fun ensureSession(): MediaSession {
    val existing = session
    if (existing != null) {
      return existing
    }
    val created = context.createMediaSession(
      channelId,
      { event ->
        dispatchMediaEvent(event)
      },
      { position ->
        setState(STATE_BUFFERING, position)
        dispatchPositionEvent(position)
      }
    )
    created.isActive = true
    session = created
    notification = context.createNotification(created, channelId, state)
    MediaControlsReceiver.notifyMediaSessionListeners = { action ->
      if (action == "dismiss") {
        cancel()
      } else {
        dispatchMediaEvent("media-$action")
      }
    }
    startKeepAlive()
    return created
  }

  private fun startKeepAlive() {
    try {
      context.startForegroundService(Intent(context, KeepAliveService::class.java))
    } catch (_: Exception) {
    }
  }

  private fun stopKeepAlive() {
    try {
      context.stopService(Intent(context, KeepAliveService::class.java))
    } catch (_: Exception) {
    }
  }

  @SuppressLint("MissingPermission")
  fun push() {
    val current = notification ?: return
    notificationManager.notify(notificationTag, notificationId, current)
  }

  fun setState(givenState: Int?, givenPosition: Long? = null): MediaSessionFacade {
    val current = session ?: return this
    val previousPlaying = state == STATE_PLAYING
    if (givenState != null) {
      state = givenState
    }
    val nowPlaying = state == STATE_PLAYING
    val playPauseChanged = previousPlaying != nowPlaying || lastNotifiedPlaying == null

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && playPauseChanged) {
      notification = context.createNotification(current, channelId, state)
      lastNotifiedPlaying = nowPlaying
      push()
    }

    val statePosition: Long? = givenPosition ?: playbackPosition
    playbackPosition = statePosition
    val speed = if (nowPlaying) 1.0f else 0.0f
    current.setPlaybackState(
      PlaybackState.Builder()
        .setState(state, statePosition ?: 0, speed)
        .setActions(
          PlaybackState.ACTION_PLAY_PAUSE or
          PlaybackState.ACTION_PLAY or
          PlaybackState.ACTION_PAUSE or
          PlaybackState.ACTION_SKIP_TO_NEXT or
          PlaybackState.ACTION_SKIP_TO_PREVIOUS or
          PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
          PlaybackState.ACTION_PLAY_FROM_SEARCH or
          PlaybackState.ACTION_SEEK_TO
        ).build()
    )
    return this
  }

  fun setMetadata(
    trackName: String,
    artist: String,
    duration: Long,
    art: String?
  ): MediaSessionFacade {
    val current = ensureSession()
    val metadataBuilder = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, trackName)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
      .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)

    current.setMetadata(metadataBuilder.build())

    if (art != null) {
      scope.launch {
        val bitmapArt = decodeArt(art) ?: return@launch
        current.setMetadata(
          metadataBuilder
            .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmapArt)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmapArt)
            .build()
        )
      }
    }

    return this
  }

  private fun decodeArt(art: String): Bitmap? {
    var connection: java.net.URLConnection? = null
    return try {
      // inside the try: URL() throws on a malformed url, and this runs in a coroutine
      // with nothing above it to catch that
      val opened = URL(art).openConnection()
      connection = opened
      opened.connectTimeout = 5000
      opened.readTimeout = 5000
      val bytes = opened.getInputStream().use { it.readBytes() }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val max = 512
      var sample = 1
      val largest = maxOf(bounds.outWidth, bounds.outHeight)
      while (largest / sample > max) {
        sample *= 2
      }
      val decode = BitmapFactory.Options().apply { inSampleSize = sample }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
    } catch (_: Throwable) {
      null
    } finally {
      (connection as? HttpURLConnection)?.disconnect()
    }
  }

  fun cancel() {
    session?.isActive = false
    session?.release()
    session = null
    notification = null
    lastNotifiedPlaying = null
    MediaControlsReceiver.notifyMediaSessionListeners = null
    notificationManager.cancel(notificationTag, notificationId)
    stopKeepAlive()
  }

  fun release() {
    cancel()
    scope.cancel()
  }
}
