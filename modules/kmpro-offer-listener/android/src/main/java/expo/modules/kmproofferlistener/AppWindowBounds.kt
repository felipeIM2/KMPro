package expo.modules.kmproofferlistener

import java.util.concurrent.ConcurrentHashMap

/**
 * Moldura na tela de cada app de corrida vigiado, em coordenadas de tela.
 *
 * O [RideAccessibilityService] enxerga as janelas raiz dos apps vigiados; em
 * tela dividida cada app ocupa metade da tela e o `AccessibilityNodeInfo` raiz
 * expõe os bounds exatos daquela metade. O [OfferOverlay] usa esses bounds para
 * ancorar o cartão DENTRO do app correspondente — a notificação da Uber fica
 * sobre a Uber, a da 99 sobre a 99 — em vez de ancorar na tela inteira.
 *
 * Os bounds são publicados a cada varredura do serviço (a cada ~750 ms) e
 * podados para os apps ainda visíveis, então um app fechado perde a âncora e o
 * cartão seguinte cai no fallback de tela inteira.
 */
object AppWindowBounds {
  data class Bounds(
    val packageName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
  }

  private val bounds = ConcurrentHashMap<String, Bounds>()

  fun update(packageName: String, value: Bounds) {
    if (packageName.isBlank() || value.width <= 0 || value.height <= 0) return
    bounds[packageName] = value
  }

  fun get(packageName: String): Bounds? = bounds[packageName]

  /** Há pelo menos um app vigiado com janela visível agora? */
  fun isNotEmpty(): Boolean = bounds.isNotEmpty()

  /** Mantém só os apps que ainda têm janela visível (chamado a cada varredura). */
  fun retain(alive: Set<String>) {
    bounds.keys.retainAll(alive)
  }

  fun clear() = bounds.clear()
}
