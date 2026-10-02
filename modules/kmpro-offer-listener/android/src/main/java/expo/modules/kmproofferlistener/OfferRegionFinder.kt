package expo.modules.kmproofferlistener

/**
 * Localiza, na leitura de tela cheia, a região onde está o cartão de oferta.
 *
 * Motivação: a leitura da tela inteira erra dígitos justamente nos números que
 * importam — "R$ 17,03" chegou a ser lido como "R$ 1703" numa sessão real. A
 * região achada aqui é recortada e reconhecida de novo pelo [ScreenOcr], agora
 * só com o texto grande do cartão e sem o resto da UI como ruído.
 *
 * A região é derivada das linhas que o [OfferParser] já validou como oferta:
 * casa `fareStr`/`distanceStr`/`durationStr` com a linha do ML Kit que as contém,
 * une os retângulos e cresce em volta. Nada de posição fixa — o cartão muda de
 * lugar entre Uber e 99, entre aparelhos e entre versões do app, e uma região
 * fixa cortaria exatamente as linhas que precisamos.
 *
 * Kotlin puro de propósito: os testes unitários do módulo rodam com
 * `returnDefaultValues = true`, então a matemática precisa ficar fora do
 * framework Android para poder ser testada de verdade.
 */
object OfferRegionFinder {
  /**
   * Folga vertical em fração do **lado curto** da tela, não da altura: o tablet
   * roda em paisagem (1920x1200), então uma fração da altura (1200) dava 120 px,
   * curto demais — numa oferta real da Uber o recorte cortou o "8 min" e o
   * "Selecionar", e a segunda passada perdeu justamente a keyword e a duração.
   */
  const val VERTICAL_PAD_FRACTION = 0.18

  /** Folga horizontal em fração da largura da tela. */
  const val HORIZONTAL_PAD_FRACTION = 0.06

  /**
   * Piso da folga vertical em fração da própria união casada. Se a união já é
   * alta (várias linhas), cresce com ela; se é uma linha só, o piso do lado
   * curto domina.
   */
  const val UNION_HEIGHT_FRACTION = 0.6

  /** Folga mínima em pixels, para telas/cartões pequenos. */
  const val MIN_PAD_PX = 24

  /**
   * Retorna a região do cartão de oferta, ou null quando não há nada a recortar.
   *
   * Não exige `analysis.isOffer`: o caso que motivou a segunda passada é
   * exatamente uma oferta em que o OCR da tela inteira leu "R$ 17,03" como
   * "R$ 1703", e o parser rejeita a tarifa implausível. Se a região só fosse
   * procurada para ofertas já válidas, esse caso nunca chegaria ao recorte —
   * que é quem conserta o número. A tarifa (mesmo implausível) e a keyword
   * continuam valendo como âncoras; telas de Ganhos/home com um "R$ 4,75" solto
   * não têm keyword e ficam de fora, para não dobrar o OCR a cada varredura.
   */
  fun find(
    analysis: OfferParser.OfferAnalysis,
    lines: List<OcrLine>,
    screenWidth: Int,
    screenHeight: Int,
  ): OcrRect? {
    if (lines.isEmpty()) return null
    if (analysis.fareStr.isBlank()) return null
    if (!analysis.isOffer && !analysis.hasKeyword) return null

    val fare = normalize(analysis.fareStr)
    val distance = normalize(analysis.distanceStr)
    val duration = normalize(analysis.durationStr)

    val matched = lines.filter { line ->
      matches(line.text, fare, distance, duration)
    }
    if (matched.isEmpty()) return null

    var union = matched.first().rect
    for (line in matched.drop(1)) union = union.union(line.rect)
    if (!union.isValid()) return null

    val shortSide = minOf(screenWidth, screenHeight)
    val verticalPad = maxOf(
      (union.height * UNION_HEIGHT_FRACTION).toInt(),
      (shortSide * VERTICAL_PAD_FRACTION).toInt(),
      MIN_PAD_PX,
    )
    val horizontalPad = maxOf(
      (screenWidth * HORIZONTAL_PAD_FRACTION).toInt(),
      MIN_PAD_PX,
    )

    return union.expand(
      horizontal = horizontalPad,
      top = verticalPad,
      bottom = verticalPad,
      maxLeft = 0,
      maxTop = 0,
      maxRight = screenWidth,
      maxBottom = screenHeight,
    ).takeIf { it.isValid() }
  }

  /**
   * Cada campo é casado na sua forma real, porque o parser devolve recortes
   * diferentes: `fareStr` é o número já formatado ("17,03"), `distanceStr` é o
   * número sem unidade ("3,4") e `durationStr` é só o inteiro ("17"). Um
   * `contains` cru no inteiro casaria com "1703", "17:03" ou o horário do
   * celular — por isso a distância exige a unidade e a duração exige "min".
   */
  private fun matches(
    rawText: String,
    fare: String,
    distance: String,
    duration: String,
  ): Boolean {
    val text = normalize(rawText)
    if (text.isBlank()) return false

    if (fare.isNotBlank() && text.contains(fare)) return true

    if (distance.isNotBlank()) {
      val number = Regex.escape(distance)
      if (Regex("(?<![\\d])$number\\s*km", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true
    }

    if (duration.isNotBlank()) {
      val minutes = duration.substringBefore('.').filter { it.isDigit() }
      if (minutes.isNotBlank() &&
        Regex("(?<![\\d])$minutes\\s*(?:min|mins|minuto|minutos)", RegexOption.IGNORE_CASE)
          .containsMatchIn(text)
      ) {
        return true
      }
    }
    return false
  }

  /** Mesma normalização do [OfferParser]. */
  private fun normalize(raw: String): String =
    raw.replace('\u00a0', ' ')
      .replace(Regex("\\s+"), " ")
      .trim()
}