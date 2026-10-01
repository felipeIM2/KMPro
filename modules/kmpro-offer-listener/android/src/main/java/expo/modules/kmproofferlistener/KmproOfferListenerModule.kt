package expo.modules.kmproofferlistener

import android.content.Context
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class KmproOfferListenerModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("KmproOfferListener")

    Events("onOffer", "onListenerConnected", "onAccessibilityConnected")

    AsyncFunction("isPermissionGranted") {
      OfferManager.isPermissionGranted(context)
    }

    AsyncFunction("openNotificationAccessSettings") {
      val activity = appContext.activityProvider?.currentActivity
      if (activity == null) return@AsyncFunction false
      OfferManager.openNotificationAccessSettings(activity)
    }

    AsyncFunction("isListenerConnected") {
      OfferManager.isListenerConnected(context)
    }

    AsyncFunction("isAccessibilityGranted") {
      OfferManager.isAccessibilityGranted(context)
    }

    AsyncFunction("isAccessibilityConnected") {
      OfferManager.isAccessibilityConnected(context)
    }

    AsyncFunction("openAccessibilitySettings") {
      val activity = appContext.activityProvider?.currentActivity
      if (activity == null) return@AsyncFunction false
      OfferManager.openAccessibilitySettings(activity)
    }

    AsyncFunction("isPostNotificationsGranted") {
      KmproOfferNotifications.isPostNotificationsGranted(context)
    }

    AsyncFunction("requestPostNotifications") {
      val activity = appContext.activityProvider?.currentActivity
      if (activity == null) return@AsyncFunction false
      KmproOfferNotifications.requestPostNotifications(activity)
    }

    AsyncFunction("openPostNotificationsSettings") {
      KmproOfferNotifications.openPostNotificationsSettings(context)
      true
    }

    AsyncFunction("setWatchedPackages") { packages: List<String> ->
      OfferManager.setWatchedPackages(context, packages)
    }

    AsyncFunction("getWatchedPackages") {
      OfferManager.getWatchedPackages(context).toList()
    }

    AsyncFunction("getPendingOffers") {
      OfferManager.getPendingOffers(context)
    }

    AsyncFunction("setDriverSettings") { costPerKm: Double, goalPerKm: Double, goalPerHour: Double ->
      OfferManager.setDriverSettings(context, costPerKm, goalPerKm, goalPerHour)
    }

    AsyncFunction("setCardAppearance") {
        metricOrder: List<String>,
        cardPosition: String,
        screenDurationSeconds: Int ->
      OfferManager.setCardAppearance(
        context,
        metricOrder,
        cardPosition,
        screenDurationSeconds,
      )
    }

    AsyncFunction("setCardGoals") {
        gainKmMin: Double,
        gainKmMax: Double,
        gainHourMin: Double,
        gainHourMax: Double,
        ratingMin: Double ->
      OfferManager.setCardGoals(
        context,
        gainKmMin,
        gainKmMax,
        gainHourMin,
        gainHourMax,
        ratingMin,
      )
    }

    AsyncFunction("setOverlayEnabled") { enabled: Boolean ->
      OfferManager.setOverlayEnabled(context, enabled)
    }

    AsyncFunction("setCopilotoActive") { active: Boolean ->
      OfferManager.setCopilotoActive(context, active)
      if (active) {
        KmproForegroundService.start(context)
      } else {
        // Para o serviço e remove os avisos postados: pausado não deve sobrar
        // nenhuma notificação do app na gaveta.
        KmproForegroundService.stop(context)
        KmproOfferNotifications.dismissAll(context)
      }
    }

    AsyncFunction("isOverlayEnabled") {
      OfferManager.isOverlayEnabled(context)
    }

    AsyncFunction("isOverlayPermissionGranted") {
      OfferManager.canDrawOverlays(context)
    }

    AsyncFunction("openOverlayPermissionSettings") {
      val activity = appContext.activityProvider?.currentActivity
      if (activity == null) return@AsyncFunction false
      OfferManager.openOverlaySettings(activity)
      true
    }

    OnStartObserving {
      OfferManager.onOffer = { offer ->
        sendEvent("onOffer", offer)
      }
      OfferManager.onListenerConnected = { connected ->
        sendEvent("onListenerConnected", mapOf("connected" to connected))
      }
      OfferManager.onAccessibilityConnected = { connected ->
        sendEvent("onAccessibilityConnected", mapOf("connected" to connected))
      }
    }

    OnStopObserving {
      OfferManager.onOffer = null
      OfferManager.onListenerConnected = null
      OfferManager.onAccessibilityConnected = null
    }
  }
}