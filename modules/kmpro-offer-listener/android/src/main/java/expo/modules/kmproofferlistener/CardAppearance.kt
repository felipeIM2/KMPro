package expo.modules.kmproofferlistener

/**
 * Aparência do cartão flutuante, configurada em Ajustes > Aparência do cartão.
 *
 * A UI (React Native) monta o mesmo cartão em `OfferCard` usando estes campos,
 * então a prévia nas configurações e o overlay real precisam refletir as mesmas
 * escolhas: quais métricas mostrar, em que ordem e onde ancorar o cartão.
 */
data class CardAppearance(
  /** Ordem exata das métricas na tela. */
  val metricOrder: List<String> = DEFAULT_METRIC_ORDER,
  /** Ancoragem horizontal: esquerda, centro ou direita. */
  val cardPosition: String = "centro",
  /**
   * Quanto tempo o cartão fica disponível depois de uma oferta, em segundos.
   * Vem do seletor "Tempo de tela" do Ajustes e limita o auto-hide.
   */
  val screenDurationSeconds: Int = DEFAULT_SCREEN_DURATION_SECONDS,
) {
  val screenDurationMs: Long get() = screenDurationSeconds.coerceAtLeast(1) * 1000L

  companion object {
    val DEFAULT_METRIC_ORDER = listOf("ganhoKm", "lucro", "ganhoHora", "lucroHora")
    val VALID_POSITIONS = setOf("esquerda", "centro", "direita")
    const val DEFAULT_SCREEN_DURATION_SECONDS = 6
    val VALID_SCREEN_DURATIONS = setOf(4, 6, 8, 10)

    val DEFAULT = CardAppearance()
  }
}
