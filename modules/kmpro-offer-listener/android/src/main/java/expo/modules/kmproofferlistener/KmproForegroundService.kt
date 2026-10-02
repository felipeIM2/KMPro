package expo.modules.kmproofferlistener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class KmproForegroundService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null
  private val handler = Handler(Looper.getMainLooper())
  private val renewRunnable = object : Runnable {
    override fun run() {
      acquireWakeLock()
      handler.postDelayed(this, WAKE_LOCK_RENEW_MS)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startAsForeground()
    acquireWakeLock()
    // O wakelock é adquirido com timeout e renovado enquanto o serviço viver:
    // se o sistema matar o serviço sem chamar onDestroy, ele expira sozinho em
    // vez de manter a tela acesa indefinidamente.
    handler.postDelayed(renewRunnable, WAKE_LOCK_RENEW_MS)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startAsForeground()
    // Sem restart automático: a notificação só volta quando o usuário der Iniciar de novo.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(renewRunnable)
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    super.onDestroy()
  }

  private fun acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    // Solta a referência anterior antes de readquirir: acquire() em um lock já
    // mantido apenas incrementa a contagem, e um único release não o soltaria.
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = try {
      pm.newWakeLock(
        PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "kmpro:copiloto"
      )
    } catch (_: SecurityException) {
      null
    }
    wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
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

    /** Timeout do wakelock e intervalo de renovação (renova antes de expirar). */
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    private const val WAKE_LOCK_RENEW_MS = 9 * 60 * 1000L

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