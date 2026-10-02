package expo.modules.kmproofferlistener

/**
 * Metas de performance definidas em Ajustes > Metas, replicadas no card para
 * pintar cada métrica.
 *
 * São faixas, não um valor único: abaixo do mínimo é vermelho, entre o mínimo
 * e o máximo é amarelo e no máximo ou acima é verde. Um limite em zero (ou
 * menor) significa "não configurado" e a métrica fica neutra, sem cor.
 *
 * O lucro/h não vem das Metas: ele é derivado de Informações de Custos por
 * [custoHora] (custo por hora no ritmo planejado de km/h). O lucro por viagem
 * usa a mesma taxa, escalada pela duração da oferta ([toneForLucro]).
 */
data class CardGoals(
  val gainKmMin: Double = 0.0,
  val gainKmMax: Double = 0.0,
  val gainHourMin: Double = 0.0,
  val gainHourMax: Double = 0.0,
  val ratingMin: Double = 0.0,
  /** Custo por hora = totalCostPerKm × (km/dia ÷ horas/dia). Zero = sem meta. */
  val custoHora: Double = 0.0,
) {
  /**
   * Devolve `green`, `yellow`, `red` ou `null` (sem meta configurada) para um
   * valor observado.
   */
  fun toneFor(metricId: String, value: Double?): String? {
    if (value == null) return null
    val (min, max) = limitsFor(metricId) ?: return null
    return toneForRange(value, min, max)
  }

  /**
   * Tom do lucro por viagem: o alvo por hora ([custoHora]) vira um alvo em
   * reais multiplicando pelas horas da oferta. Abaixo de 90% do alvo é
   * vermelho, entre 90% e o alvo é amarelo e no alvo ou acima é verde.
   */
  fun toneForLucro(value: Double?, minutes: Double?): String? {
    if (value == null || minutes == null || minutes <= 0.0) return null
    val max = custoHora * (minutes / 60.0)
    if (max <= 0.0) return null
    return toneForRange(value, max * 0.9, max)
  }

  private fun limitsFor(metricId: String): Pair<Double, Double>? = when (metricId) {
    "ganhoKm" -> gainKmMin to gainKmMax
    "ganhoHora" -> gainHourMin to gainHourMax
    "lucroHora" -> (custoHora * 0.9) to custoHora
    "rating" -> ratingMin to 0.0
    else -> null
  }

  private fun toneForRange(value: Double, min: Double, max: Double): String? = when {
    min <= 0.0 && max <= 0.0 -> null
    max > 0.0 && value >= max -> RideCalc.GREEN
    min > 0.0 && value < min -> RideCalc.RED
    min > 0.0 && max > 0.0 -> RideCalc.YELLOW
    // Só um dos limites foi preenchido: compara só com ele.
    min > 0.0 -> if (value >= min) RideCalc.GREEN else RideCalc.RED
    else -> if (value >= max) RideCalc.GREEN else RideCalc.RED
  }

  companion object {
    val DEFAULT = CardGoals()

    /** Pior tom entre os informados (red > yellow > green), ignorando null. */
    fun worst(tones: Iterable<String?>): String? {
      var result: String? = null
      for (tone in tones) {
        if (tone == null) continue
        if (result == null || rank(tone) > rank(result)) result = tone
      }
      return result
    }

    private fun rank(tone: String): Int = when (tone) {
      RideCalc.RED -> 3
      RideCalc.YELLOW -> 2
      RideCalc.GREEN -> 1
      else -> 0
    }
  }
}
