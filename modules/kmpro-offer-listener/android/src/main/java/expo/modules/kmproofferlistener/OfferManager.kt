package expo.modules.kmproofferlistener

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

object OfferManager {
  private const val TAG = "KMProOffer"

  val DEFAULT_RIDE_PACKAGES = listOf("com.ubercab.driver", "com.app99.driver")

  const val PREFS_NAME = "kmpro_offer_listener"
  const val KEY_WATCHED = "watched_packages"
  const val KEY_OFFERS = "pending_offers"
  const val KEY_DRIVER = "driver_settings"
  const val KEY_APPEARANCE = "card_appearance"
  const val KEY_GOALS = "card_goals"
  const val KEY_LISTENER_CONNECTED = "listener_connected"
  const val KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected"
  const val KEY_SCREEN_LOG = "screen_log"
  const val KEY_COPILOTO_ACTIVE = "copiloto_active"
  private const val MAX_SCREEN_LOG = 5
  private const val MAX_OFFERS = 10

  val ACCESSIBILITY_THROTTLE_MS = 700L

  @Volatile
  var onOffer: ((Map<String, Any?>) -> Unit)? = null

  @Volatile
  var onListenerConnected: ((Boolean) -> Unit)? = null

  @Volatile
  var onAccessibilityConnected: ((Boolean) -> Unit)? = null

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun isPermissionGranted(context: Context): Boolean {
    val app = context.applicationContext
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(app)
    return enabled.contains(app.packageName)
  }

  fun openNotificationAccessSettings(context: Context): Boolean {
    if (isPermissionGranted(context)) return true
    return try {
      val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(settings)
      true
    } catch (e: Exception) {
      false
    }
  }

  fun isListenerConnected(context: Context): Boolean =
    prefs(context).getBoolean(KEY_LISTENER_CONNECTED, false)

  fun setListenerConnected(context: Context, connected: Boolean) {
    prefs(context).edit().putBoolean(KEY_LISTENER_CONNECTED, connected).apply()
    onListenerConnected?.invoke(connected)
  }

  /** Estado do Copiloto (iniciado/parado pelo botão Iniciar/Parar do app). */
  fun setCopilotoActive(context: Context, active: Boolean) {
    prefs(context).edit().putBoolean(KEY_COPILOTO_ACTIVE, active).apply()
  }

  fun isCopilotoActive(context: Context): Boolean =
    prefs(context).getBoolean(KEY_COPILOTO_ACTIVE, false)

  fun isAccessibilityGranted(context: Context): Boolean {
    val serviceId =
      ComponentName(context, RideAccessibilityService::class.java).flattenToString()
    val enabled =
      Settings.Secure.getString(
        context.applicationContext.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return false
    return enabled.split(':').any { it.equals(serviceId, ignoreCase = true) }
  }

  fun openAccessibilitySettings(context: Context): Boolean {
    if (isAccessibilityGranted(context)) return true
    return try {
      val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(settings)
      true
    } catch (e: Exception) {
      false
    }
  }

  fun isAccessibilityConnected(context: Context): Boolean =
    prefs(context).getBoolean(KEY_ACCESSIBILITY_CONNECTED, false)

  fun setAccessibilityConnected(context: Context, connected: Boolean) {
    prefs(context).edit().putBoolean(KEY_ACCESSIBILITY_CONNECTED, connected).apply()
    onAccessibilityConnected?.invoke(connected)
  }

  fun addScreenDebug(context: Context, text: String) {
    val current = prefs(context).getStringSet(KEY_SCREEN_LOG, null)?.toMutableSet() ?: mutableSetOf()
    if (text.isBlank()) return
    if (!current.add(text)) return
    while (current.size > MAX_SCREEN_LOG) {
      current.remove(current.first())
    }
    prefs(context).edit().putStringSet(KEY_SCREEN_LOG, current).apply()
  }

  fun getWatchedPackages(context: Context): Set<String> =
    prefs(context).getStringSet(KEY_WATCHED, emptySet()) ?: emptySet()

  fun setWatchedPackages(context: Context, packages: List<String>) {
    prefs(context).edit().putStringSet(KEY_WATCHED, packages.toSet()).apply()
  }

  fun isWatched(context: Context, packageName: String): Boolean =
    getWatchedPackages(context).contains(packageName) || packageName in DEFAULT_RIDE_PACKAGES

  fun setDriverSettings(context: Context, costPerKm: Double, goalPerKm: Double, goalPerHour: Double) {
    val payload = JSONObject().apply {
      put("costPerKm", costPerKm)
      put("goalPerKm", goalPerKm)
      put("goalPerHour", goalPerHour)
    }
    prefs(context).edit().putString(KEY_DRIVER, payload.toString()).apply()
  }

  fun driverSettings(context: Context): DriverSettings {
    val raw = prefs(context).getString(KEY_DRIVER, null) ?: return DriverSettings()
    return try {
      val o = JSONObject(raw)
      DriverSettings(
        costPerKm = o.optDouble("costPerKm", 0.0),
        goalPerKm = o.optDouble("goalPerKm", 0.0),
        goalPerHour = o.optDouble("goalPerHour", 0.0),
      )
    } catch (e: Exception) {
      DriverSettings()
    }
  }

  fun setCardAppearance(
    context: Context,
    metricOrder: List<String>,
    cardPosition: String,
    screenDurationSeconds: Int = CardAppearance.DEFAULT_SCREEN_DURATION_SECONDS,
  ) {
    val payload = JSONObject().apply {
      put("metricOrder", JSONArray(metricOrder))
      put("cardPosition", cardPosition)
      put("screenDurationSeconds", screenDurationSeconds)
    }
    prefs(context).edit().putString(KEY_APPEARANCE, payload.toString()).apply()
    // Reaplica imediatamente: um cartão já na tela precisa refletir a mudança.
    OfferOverlay.refreshAppearance(context)
  }

  fun cardAppearance(context: Context): CardAppearance {
    val raw = prefs(context).getString(KEY_APPEARANCE, null)
      ?: return CardAppearance.DEFAULT
    return try {
      val o = JSONObject(raw)
      val order = o.optJSONArray("metricOrder")
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }
        ?.filter { it.isNotBlank() }
        .orEmpty()
      val position = o.optString("cardPosition", "esquerda")
      val duration = o.optInt(
        "screenDurationSeconds",
        CardAppearance.DEFAULT_SCREEN_DURATION_SECONDS,
      )
      CardAppearance(
        metricOrder = if (order.isEmpty()) CardAppearance.DEFAULT_METRIC_ORDER else order,
        cardPosition =
          if (position in CardAppearance.VALID_POSITIONS) position else "esquerda",
        screenDurationSeconds =
          if (duration in CardAppearance.VALID_SCREEN_DURATIONS) {
            duration
          } else {
            CardAppearance.DEFAULT_SCREEN_DURATION_SECONDS
          },
      )
    } catch (e: Exception) {
      CardAppearance.DEFAULT
    }
  }

  /** Metas de Ajustes > Metas, usadas para colorir as métricas do cartão. */
  fun setCardGoals(
    context: Context,
    gainKmMin: Double,
    gainKmMax: Double,
    gainHourMin: Double,
    gainHourMax: Double,
    ratingMin: Double,
    custoHora: Double,
  ) {
    val payload = JSONObject().apply {
      put("gainKmMin", gainKmMin)
      put("gainKmMax", gainKmMax)
      put("gainHourMin", gainHourMin)
      put("gainHourMax", gainHourMax)
      put("ratingMin", ratingMin)
      put("custoHora", custoHora)
    }
    prefs(context).edit().putString(KEY_GOALS, payload.toString()).apply()
  }

  fun cardGoals(context: Context): CardGoals {
    val raw = prefs(context).getString(KEY_GOALS, null) ?: return CardGoals.DEFAULT
    return try {
      val o = JSONObject(raw)
      CardGoals(
        gainKmMin = o.optDouble("gainKmMin", 0.0),
        gainKmMax = o.optDouble("gainKmMax", 0.0),
        gainHourMin = o.optDouble("gainHourMin", 0.0),
        gainHourMax = o.optDouble("gainHourMax", 0.0),
        ratingMin = o.optDouble("ratingMin", 0.0),
        custoHora = o.optDouble("custoHora", 0.0),
      )
    } catch (e: Exception) {
      CardGoals.DEFAULT
    }
  }

  fun setOverlayEnabled(context: Context, enabled: Boolean) {
    OfferOverlay.setEnabled(context, enabled)
  }

  fun isOverlayEnabled(context: Context): Boolean =
    OfferOverlay.isEnabled(context)

  fun canDrawOverlays(context: Context): Boolean =
    OfferOverlay.canDraw(context)

  fun openOverlaySettings(context: Context) {
    OfferOverlay.openSettings(context)
  }

  /** Esconde TODOS os cartões flutuantes e limpa a fila de exibição. */
  fun hideOfferOverlay() {
    OfferOverlay.hide()
  }

  /**
   * Esconde apenas o cartão do app informado (a oferta dele desapareceu da
   * tela). Os cartões dos outros apps permanecem; a fila de exibição é drenada
   * caso algum cartão esteja esperando. O contexto de app do overlay é o
   * próprio que o criou os cartões, então o dreno da fila não precisa de
   * contexto aqui.
   */
  fun hideOfferOverlayFor(packageName: String) {
    OfferOverlay.hideFor(packageName)
  }

  fun addOffer(context: Context, offer: Map<String, Any?>) {
    // Só captura enquanto o Copiloto está iniciado: pausado, nada é emitido,
    // persistido, avisado por push ou mostrado no overlay.
    if (!isCopilotoActive(context)) {
      Log.d(TAG, "[KMPro] oferta ignorada: copiloto pausado")
      return
    }
    if (OfferParser.offerSignature(offer) in seenSignatures(context)) return

    // Anexa análise de lucro/classificação (verde/amarelo/vermelho) à oferta.
    val analysis = RideCalc.calculate(
      fare = offer["fare"] as? Double ?: (offer["fare"] as? Number)?.toDouble(),
      distanceKm = (offer["distance"] as? Number)?.toDouble(),
      durationMinutes = (offer["durationMinutes"] as? Number)?.toDouble(),
      settings = driverSettings(context),
    )
    val extended = LinkedHashMap(offer)
    if (analysis != null) {
      extended.putAll(analysis.asMap())
      extended["analysis"] = analysis.asMap()
    } else {
      extended["analysis"] = null
    }

    val offers = loadOffers(context)
    val obj = JSONObject()
    for ((key, value) in extended) {
      if (value != null) obj.put(key, value)
    }
    offers.put(obj)
    while (offers.length() > MAX_OFFERS) {
      offers.remove(0)
    }
    prefs(context).edit().putString(KEY_OFFERS, offers.toString()).apply()
    onOffer?.invoke(extended)

    // Push local com os mesmos parâmetros do cartão (o Copiloto não mostra mais
    // a última corrida; a notificação substitui esse card).
    KmproOfferNotifications.post(context, extended)

    // Overlay flutuante sobre o app de transporte (um cartão por app; ofertas
    // simultâneas aparecem ao mesmo tempo, cada uma sobre o app de origem).
    if (analysis != null) {
      OfferOverlay.show(context, extended)
    }
  }

  /**
   * Solta as ofertas pendentes de um app: a corrida dele acabou, então as
   * assinaturas guardadas precisam ir embora junto.
   *
   * [seenSignatures] deriva do histórico inteiro de ofertas salvas e não tinha
   * nenhuma forma de liberar um pacote. Efeito em campo: a corrida saía da tela e
   * voltava com os mesmos valores (mesmo trajeto, mesmo horário) — a assinatura
   * continuava na lista, a leitura era jogada fora como repetida e o cartão do
   * KMPro não aparecia. É a segunda das duas camadas que travavam o acionamento;
   * a primeira é o dedup do serviço, limpo em [RideLifecycle].
   *
   * O RN não precisa de evento: `use-offer-listener` guarda só a última oferta,
   * não uma lista, então nada fica dessincronizado do lado do app.
   */
  fun dropOffersFor(context: Context, packageName: String) {
    if (packageName.isBlank()) return
    val offers = loadOffers(context)
    if (offers.length() == 0) return
    val kept = JSONArray()
    var removed = 0
    for (i in 0 until offers.length()) {
      val item = offers.optJSONObject(i)
      if (item != null && item.optString("packageName") == packageName) {
        removed += 1
      } else if (item != null) {
        kept.put(item)
      }
    }
    if (removed == 0) return
    prefs(context).edit().putString(KEY_OFFERS, kept.toString()).apply()
    Log.d(TAG, "[KMPro][Ride] ofertas pendentes soltas pkg=$packageName removidas=$removed")
  }

  /** Same offer (fare|distance|duration) must not be persisted or re-emitted. */
  private fun seenSignatures(context: Context): Set<String> =
    getPendingOffers(context).mapNotNull { OfferParser.offerSignature(it) }.toSet()

  fun getPendingOffers(context: Context): List<Map<String, Any?>> {
    val result = mutableListOf<Map<String, Any?>>()
    val offers = loadOffers(context)
    for (i in 0 until offers.length()) {
      val item = offers.optJSONObject(i) ?: continue
      val map = LinkedHashMap<String, Any?>()
      val keys = item.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        map[key] = item.opt(key)
      }
      result.add(map)
    }
    return result
  }

  private fun loadOffers(context: Context): JSONArray {
    val raw = prefs(context).getString(KEY_OFFERS, null) ?: return JSONArray()
    return try {
      JSONArray(raw)
    } catch (e: Exception) {
      JSONArray()
    }
  }
}