package expo.modules.kmproofferlistener

/**
 * Metas de performance definidas em Ajustes > Metas, replicadas no card para
 * pintar cada métrica.
 *
 * São faixas, não um valor único: abaixo do mínimo é vermelho, entre o mínimo
 * e o máximo é amarelo e no máximo ou acima é verde. Um limite em zero (ou
 * menor) significa "não configurado" e a métrica fica neutra, sem cor.
 */
data class CardGoals(
  val gainKmMin: Double = 0.0,
  val gainKmMax: Double = 0.0,
  val gainHourMin: Double = 0.0,
  val gainHourMax: Double = 0.0,
  val ratingMin: Double = 0.0,
) {
  /**
   * Devolve `green`, `yellow`, `red` ou `null` (sem meta configurada) para um
   * valor observado.
   */
  fun toneFor(metricId: String, value: Double?): String? {
    if (value == null) return null
    val (min, max) = limitsFor(metricId) ?: return null
    return when {
      min <= 0.0 && max <= 0.0 -> null
      max > 0.0 && value >= max -> RideCalc.GREEN
      min > 0.0 && value < min -> RideCalc.RED
      min > 0.0 && max > 0.0 -> RideCalc.YELLOW
      // Só um dos limites foi preenchido: compara só com ele.
      min > 0.0 -> if (value >= min) RideCalc.GREEN else RideCalc.RED
      else -> if (value >= max) RideCalc.GREEN else RideCalc.RED
    }
  }

  private fun limitsFor(metricId: String): Pair<Double, Double>? = when (metricId) {
    "ganhoKm" -> gainKmMin to gainKmMax
    "ganhoHora" -> gainHourMin to gainHourMax
    "rating" -> ratingMin to 0.0
    else -> null
  }

  companion object {
    val DEFAULT = CardGoals()
  }
}
