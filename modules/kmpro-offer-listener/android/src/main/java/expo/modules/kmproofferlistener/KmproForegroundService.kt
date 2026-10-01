package expo.modules.kmproofferlistener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class KmproForegroundService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startAsForeground()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startAsForeground()
    // Sem restart automático: a notificação só volta quando o usuário der Iniciar de novo.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    super.onDestroy()
  }

  private fun acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = try {
      pm.newWakeLock(
        PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "kmpro:copiloto"
      )
    } catch (_: SecurityException) {
      null
    }
    wakeLock?.acquire()
  }

  private fun startAsForeground() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "kmpro_copiloto"
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(channelId, "KMPro — Copiloto", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      packageManager.getLaunchIntentForPackage(packageName),
      PendingIntent.FLAG_IMMUTABLE
    )
    val notification = Notification.Builder(this, channelId)
      .setContentTitle(getString(R.string.kmpro_fg_title))
      .setContentText(getString(R.string.kmpro_fg_text))
      .setSmallIcon(R.drawable.ic_kmpro)
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .build()
    startForeground(NOTIF_ID, notification)
  }

  companion object {
    private const val NOTIF_ID = 1001

    fun start(context: Context) {
      val intent = Intent(context, KmproForegroundService::class.java)
      if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, KmproForegroundService::class.java))
    }
  }
}