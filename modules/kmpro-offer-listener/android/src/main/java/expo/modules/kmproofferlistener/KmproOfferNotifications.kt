package expo.modules.kmproofferlistener

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Push local com os MESMOS parâmetros do cartão quando uma corrida é capturada:
 * título com o valor da corrida e texto com as métricas na ordem configurada
 * (mesmos rótulos/sufixos de `formatMetric` e da prévia de Ajustes).
 *
 * O canal usa IMPORTANCE_DEFAULT de propósito: importance alta (ou padrão do
 * sistema) faz o Android mostrar o aviso em BANNER (heads-up) sobre o que quer
 * que esteja na tela — inclusive sobre o app de corrida, atrapalhando a leitura
 * da oferta. IMPORTANCE_DEFAULT entrega o aviso silenciosamente, apenas na
 * central de notificações.
 *
 * O id do canal é versionado porque o Android NUNCA atualiza importance de um
 * canal já criado: para mudar de HIGH para DEFAULT é preciso criar um canal
 * novo (e apagar o antigo, senão ele fica listado em Ajustes para sempre).
 *
 * No Android 13+ (API 33) também é exigida a permissão POST_NOTIFICATIONS; se
 * ela não estiver concedida a push é ignorada até o usuário conceder na tela de
 * Acessos.
 */
object KmproOfferNotifications {
  /** V2 = versão com IMPORTANCE_DEFAULT (sem heads-up). */
  private const val CHANNEL_ID = "kmpro_oferta_v2"
  /** Canal antigo (IMPORTANCE_HIGH, com banner) — criado por versões anteriores. */
  private const val LEGACY_CHANNEL_ID = "kmpro_oferta"
  private const val POST_NOTIFICATIONS_REQUEST = 441

  private var nextId = 3000

  fun isPostNotificationsGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = context.applicationContext
      .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    // IMPORTANCE_DEFAULT: o aviso entra na central SEM banner/heads-up. Com
    // IMPORTANCE_HIGH o sistema mostra o cartão flutuante sobre o app ativo,
    // exatamente o que não queremos — o overlay próprio já cumpre esse papel.
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "KMPro — Oferta",
          NotificationManager.IMPORTANCE_DEFAULT,
        ),
      )
    }
    // Canal antigo com IMPORTANCE_HIGH: apagado para não sobrar um canal de
    // ofertas com banner nas configurações do app.
    nm.getNotificationChannel(LEGACY_CHANNEL_ID)?.let { nm.deleteNotificationChannel(it.id) }
  }

  /**
   * Pede POST_NOTIFICATIONS usando o diálogo padrão do sistema. Nunca redireciona
   * para as configurações por conta própria: se o sistema ainda mostrar o
   * diálogo, ele aparece; se já o tiver suprimido, `requestPermissions` é um
   * no-op inofensivo e o status real vem de [isPostNotificationsGranted].
   */
  fun requestPostNotifications(activity: android.app.Activity): Boolean {
    if (Build.VERSION.SDK_INT < 33 || isPostNotificationsGranted(activity)) return true

    Log.d(
      "KMProOfferNotif",
      "solicitando POST_NOTIFICATIONS (rationale=${activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)})",
    )
    activity.requestPermissions(
      arrayOf(Manifest.permission.POST_NOTIFICATIONS),
      POST_NOTIFICATIONS_REQUEST,
    )
    return false
  }

  /** Abre as configurações de notificações do app, como fallback. */
  fun openPostNotificationsSettings(context: Context) {
    try {
      val intent = android.content.Intent(
        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
      ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (_: Exception) {
    }
  }

  /** Remove todos os avisos do app ao parar o Copiloto. */
  fun dismissAll(context: Context) {
    try {
      NotificationManagerCompat.from(context).cancelAll()
    } catch (_: Exception) {
    }
  }

  fun post(context: Context, offer: Map<String, Any?>) {
    val app = context.applicationContext
    if (!isPostNotificationsGranted(app)) {
      Log.d("KMProOfferNotif", "ignorada: POST_NOTIFICATIONS nao concedida")
      return
    }
    if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return

    ensureChannel(app)

    val fare = OfferOverlay.fareLabel(offer)
    val metrics = OfferOverlay.metricLabelsFor(
      offer,
      OfferManager.cardAppearance(app).metricOrder,
    ).joinToString(" · ")
    val minutes = (offer["durationMinutes"] as? Number)?.toDouble()
    val rating = (offer["rating"] as? Number)?.toDouble()

    val text = buildString {
      // A tarifa já vai no título; o texto fica com métricas e fatos.
      if (metrics.isNotBlank()) append(metrics)
      val facts = listOfNotNull(
        minutes?.let { String.format(Locale.US, "%.0f min", it) },
        rating?.let { String.format(Locale.US, "★ %.1f", it).replace('.', ',') },
      ).joinToString(" · ")
      if (facts.isNotBlank()) append("\n$facts")
    }

    val contentIntent = PendingIntent.getActivity(
      app,
      0,
      app.packageManager.getLaunchIntentForPackage(app.packageName),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(app, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_kmpro)
      .setContentTitle(fare)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(contentIntent)
      .setAutoCancel(true)
      .build()
    try {
      NotificationManagerCompat.from(app).notify(nextId++, notification)
    } catch (_: SecurityException) {
      Log.d("KMProOfferNotif", "notify bloqueado: falta POST_NOTIFICATIONS")
    }
  }
}