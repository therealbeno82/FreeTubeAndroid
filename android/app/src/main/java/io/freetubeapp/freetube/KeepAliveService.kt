package io.freetubeapp.freetube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat

class KeepAliveService : Service() {
  companion object {
    private const val CHANNEL_ID = "keep_alive"
    private const val NOTIFICATION_ID = 1
  }
  override fun onBind(intent: Intent?): IBinder? {
    return null
  }
  override fun onCreate() {
    super.onCreate()
    val notificationManager = NotificationManagerCompat.from(applicationContext)
    val channel = NotificationChannel(CHANNEL_ID, "Keep Alive", NotificationManager.IMPORTANCE_MIN)
    notificationManager.createNotificationChannel(channel)

    val notification = Notification.Builder(this.applicationContext, CHANNEL_ID)
      .setContentTitle("FreeTube is running in the background.")
      .setCategory(Notification.CATEGORY_SERVICE)
      .setSmallIcon(R.drawable.ic_media_notification_icon)
      .build()

    // The system can refuse this, most often because the app is in the background
    // (android 12+). The refusal is thrown here on the main thread rather than at the
    // startForegroundService call, so it has to be caught here. Playback carries on
    // without the service; it just loses its protection from being killed.
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (_: Exception) {
      stopSelf()
    }
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    return START_NOT_STICKY
  }
  override fun onDestroy() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }
}
