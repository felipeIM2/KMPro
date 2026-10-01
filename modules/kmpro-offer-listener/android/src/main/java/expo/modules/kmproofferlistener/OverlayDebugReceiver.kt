package expo.modules.kmproofferlistener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver de debug para conferir o layout do cartão flutuante sem esperar uma
 * corrida real. Ativado por broadcast explícito do adb; não é exportado e não
 * participa do fluxo de captura.
 */
class OverlayDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val app = context.applicationContext
    when (intent?.action) {
      ACTION_SHOW -> {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // Um BroadcastReceiver pode ser destruído assim que onReceive volta;
        // o overlay precisa ser adicionado já, dentro do mesmo main thread.
        handler.post { OfferOverlay.showPreview(app) }
        // Sem isto o poll seguinte esconde o cartão ao ver uma tela não-oferta,
        // o que impossibilita conferir o layout.
        OfferOverlay.holdForDebug()
        handler.postDelayed({ OfferOverlay.dumpTree() }, 1_500)
      }
      ACTION_HIDE -> {
        OfferOverlay.releaseDebugHold()
        OfferOverlay.hide()
      }
    }
  }

  companion object {
    const val ACTION_SHOW = "co.anonymous.KMPro.DEBUG_SHOW_OVERLAY"
    const val ACTION_HIDE = "co.anonymous.KMPro.DEBUG_HIDE_OVERLAY"
  }
}
