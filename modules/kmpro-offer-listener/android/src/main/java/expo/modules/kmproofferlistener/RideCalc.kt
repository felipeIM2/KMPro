package expo.modules.kmproofferlistener

/**
 * Custo e metas por km informados pelo motorista (vindos da UI do app).
 * A UI já calcula custo/km (combustível + fixos) e as metas de ganho; o nativo
 * só recebe os valores para não duplicar fórmulas.
 */
data class DriverSettings(
  val costPerKm: Double = 0.0,
  val goalPerKm: Double = 0.0,
  val goalPerHour: Double = 0.0,
)

/** Resultado da análise de uma oferta. */
data class RideAnalysis(
  val netProfit: Double,
  val gainPerKm: Double,
  val gainPerHour: Double,
  val netPerHour: Double,
  val costPerKm: Double,
  val goalPerKm: Double,
  val goalPerHour: Double,
  val classification: String,
  val reasons: List<String>,
) {
  fun asMap(): Map<String, Any?> = linkedMapOf(
    "netProfit" to netProfit,
    "gainPerKm" to gainPerKm,
    "gainPerHour" to gainPerHour,
    "netPerHour" to netPerHour,
    "costPerKm" to costPerKm,
    "goalPerKm" to goalPerKm,
    "goalPerHour" to goalPerHour,
    "classification" to classification,
    "reasons" to reasons,
  )
}

/**
 * Calcula lucro líquido e ganhos por km/hora de uma oferta e classifica em
 * verde (boa), amarela (atenção) ou vermelha (prejuízo).
 *
 * Regras:
 *  - verde:   lucro > 0 e ganho/km >= meta e ganho/hora >= meta
 *  - amarela: lucro > 0, mas falta atingir alguma meta
 *  - vermelha: lucro <= 0, ou ganho/km abaixo de 50% da meta
 */
object RideCalc {

  const val GREEN = "green"
  const val YELLOW = "yellow"
  const val RED = "red"

  /**
   * @return null se a oferta não tem valor válido (nada a analisar/sobrepor).
   */
  fun calculate(
    fare: Double?,
    distanceKm: Double?,
    durationMinutes: Double?,
    settings: DriverSettings,
  ): RideAnalysis? {
    val fareValue = fare
    if (fareValue == null || fareValue <= 0.0) return null
    val safeDist = (distanceKm ?: 0.0).coerceAtLeast(0.0)
    val safeDur = (durationMinutes ?: 0.0).coerceAtLeast(0.0)

    val profit: Double =
      if (safeDist > 0) fareValue - settings.costPerKm * safeDist
      else fareValue

    val gainPerKm: Double = if (safeDist > 0) fareValue / safeDist else 0.0
    val gainPerHour: Double =
      if (safeDur <= 0) 0.0
      else fareValue / (safeDur / 60.0)
    val netPerHour: Double =
      if (safeDur <= 0) 0.0
      else profit / (safeDur / 60.0)

    val reasons = mutableListOf<String>()

    fun changeMetaKm() =
      settings.goalPerKm > 0 && gainPerKm < settings.goalPerKm &&
        gainPerKm >= settings.goalPerKm * 0.5

    val classification =
      if (profit <= 0) {
        reasons += "Lucro estimado não cobre o custo por km"
        RED
      } else if (settings.goalPerKm > 0 && gainPerKm < settings.goalPerKm * 0.5) {
        reasons += "Ganho/km abaixo de 50% da meta"
        RED
      } else if (
        (settings.goalPerKm > 0 && gainPerKm >= settings.goalPerKm) &&
        (settings.goalPerHour <= 0 || gainPerHour >= settings.goalPerHour)
      ) {
        reasons += "Meta de km/hora atingida"
        GREEN
      } else {
        if (settings.goalPerKm > 0 && gainPerKm < settings.goalPerKm) {
          reasons += "Abaixo da meta de ganho/km"
        }
        if (settings.goalPerHour > 0 && gainPerHour < settings.goalPerHour) {
          reasons += "Abaixo da meta de ganho/hora"
        }
        if (reasons.isEmpty()) reasons += "Lucro positivo, mas metas indefinidas"
        YELLOW
      }

    return RideAnalysis(
      netProfit = profit,
      gainPerKm = gainPerKm,
      gainPerHour = gainPerHour,
      netPerHour = netPerHour,
      costPerKm = settings.costPerKm,
      goalPerKm = settings.goalPerKm,
      goalPerHour = settings.goalPerHour,
      classification = classification,
      reasons = reasons,
    )
  }
}