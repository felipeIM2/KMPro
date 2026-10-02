package expo.modules.kmproofferlistener

/**
 * Parses raw accessibility/notification text from a ride app into a validated
 * offer. Built to be resilient to layout changes: it searches for patterns in
 * the whole normalized text instead of reading fixed tree positions.
 *
 * Flow:
 *   raw accessibility data -> normalized text -> candidate -> validated -> RideOffer
 */
object OfferParser {
  /**
   * Botões de decisão que só aparecem no card da oferta. Exigir um deles é o
   * que impede o cartão de surgir em Ganhos, Promoções ou Alta demanda.
   */
  private val ACTION_KEYWORDS = listOf(
    "aceitar", "aceite", "selecionar", "buscar passageiro", "confirmar viagem",
    "aceitar corrida", "ir buscar", "confirmar corrida",
  )

  private val OFFER_KEYWORDS = listOf(
    "aceitar",
    "aceite",
    "aceitar corrida",
    "aceitar solicitação",
    "selecionar",
    "solicitação",
    "solicitar",
    "nova corrida",
    "nova solicitação",
    "nova oferta",
    "corrida nova",
    "buscar passageiro",
    "confirmar",
  )

  // R$ 18,50 | R$18.5 | R$ 1.248,00 | (+R$ 8,75 surge — excluded by sign)
  // O trecho numérico é capturado inteiro (dígitos e separadores) e interpretado
  // por [parseMoney]: assim "1.248" vira 1248 e não 1,248 — o separador de
  // milhar com ponto era lido como decimal e uma tarifa de milhar entrava como
  // R$ 1,25, dentro da faixa plausível.
  private val FEE_RE =
    Regex("""R\$\s*([-+]?)\s*(\d[\d.,]*)""", RegexOption.IGNORE_CASE)
  // A node that contains ONLY the fare: "R$ 18,50" (matches whole segment).
  private val FEE_EXACT_RE =
    Regex("""R\$\s*([-+]?)\s*(\d[\d.,]*)\s*""", RegexOption.IGNORE_CASE)
  // 7,4 km | 22.5km | 4 km
  private val DISTANCE_RE =
    Regex("""\b(\d{1,3}(?:[.,]\d{1,2})?)\s*km\b""", RegexOption.IGNORE_CASE)
  // A node that contains ONLY a distance: "7,4 km".
  private val DISTANCE_EXACT_RE =
    Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*km\s*""", RegexOption.IGNORE_CASE)
  // 22 min | 22min | 25 minutos — single-token segments only.
  private val DURATION_EXACT_RE =
    Regex("""(\d{1,3})\s*(?:min|mins|minuto|minutos)\s*""", RegexOption.IGNORE_CASE)
  // Qualquer menção a minutos dentro de um nó: "9min (5,4 km)", "6 minutos".
  private val DURATION_ANY_RE =
    Regex("""(\d{1,3})\s*(?:min|mins|minuto|minutos)\b""", RegexOption.IGNORE_CASE)
  // Frases de estatística que não descrevem a perna da viagem.
  private val STATS_PHRASE_RE = Regex(
    """(?i)(?:[uú]ltimos?|[uú]ltimas?|[uú]ltima|cerca de|m[eé]di[ao]s?|base|média|media)\s*""" +
      """(?:de\s*)?\d{1,3}\s*(?:min|mins|minutos|minuto)""",
  )
  /**
   * Nota do passageiro: aceita "4,9", "★ 4.9", "4.9 ★", "nota 4,9" e
   * "4,9 estrelas". O OCR costuma ler o ícone como `*`, `★` ou `A`.
   */
  /**
   * Nota do passageiro. Precisa vir ancorada numa estrela/nota, senão qualquer
   * "4,9" solto na tela (inclusive parte de um "1-4,9 km") vira nota. Aceita as
   * duas ordens: "★ 4,9" e "4,9 ★", com uma ou duas casas decimais.
   */
  private val RATING_RE =
    Regex(
      """[★*]\s*[:=]?\s*(\d{1,2}[.,]\d{1,2})|(\d{1,2}[.,]\d{1,2})\s*[★*]|""" +
        """\b(?:nota|avalia[çc][ãa]o)\b\s*(?:do passageiro)?\s*[:=]?\s*(\d{1,2}[.,]\d{1,2})|""" +
        """(\d{1,2}[.,]\d{1,2})\s*\bestrelas?\b""",
      RegexOption.IGNORE_CASE,
    )

  /** Structured analysis produced for a text feed. */
  data class OfferAnalysis(
    val fare: Double? = null,
    val distanceKm: Double? = null,
    val durationMinutes: Int? = null,
    val fareStr: String = "",
    val distanceStr: String = "",
    val durationStr: String = "",
    val rating: Double? = null,
    val hasKeyword: Boolean = false,
    val allText: String = "",
    val rejectReason: String? = null,
  ) {
    val isOffer: Boolean get() = rejectReason == null
  }

  /** Normalizes NBSP, tabs, duplicated whitespace, and trims. */
  private fun normalize(raw: String): String =
    raw.replace('\u00a0', ' ')
      .replace(Regex("\\s+"), " ")
      .trim()

  /** Parses Brazilian formatted numbers ("18,50" / "18.5") to Double, or null. */
  fun toDouble(raw: String): Double? =
    normalize(raw).replace(",", ".").toDoubleOrNull()

  /**
   * Interpreta um valor em reais com separador de milhar. O ponto só é decimal
   * quando o número não tem vírgula E os grupos depois do ponto não têm 3
   * dígitos: "18.5" -> 18,5 mas "1.248" -> 1248 e "1.248,00" -> 1248,00.
   * Sem isso, "R$ 1.248" entrava como R$ 1,25 (dentro da faixa plausível) em vez
   * de ser barrado pela faixa.
   */
  fun parseMoney(raw: String): Double? {
    val trimmed = normalize(raw).trim().trimEnd('.', ',')
    if (trimmed.isEmpty()) return null
    val hasComma = trimmed.contains(',')
    val hasDot = trimmed.contains('.')
    val normalized = when {
      hasComma && hasDot -> trimmed.replace(".", "").replace(",", ".")
      hasComma -> trimmed.replace(",", ".")
      hasDot -> {
        val groups = trimmed.split('.')
        val thousands = groups.size > 1 && groups.drop(1).all { it.length == 3 }
        if (thousands) trimmed.replace(".", "") else trimmed
      }
      else -> trimmed
    }
    return normalized.toDoubleOrNull()
  }

  private fun formatBrl(value: Double): String {
    val cents = Math.round(value * 100)
    val intPart = cents / 100
    val decPart = Math.abs(cents % 100)
    return "$intPart,${decPart.toString().padStart(2, '0')}"
  }

  private fun formatKm(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else {
      val v = Math.round(value * 10) / 10.0
      v.toString().replace('.', ',')
    }

  /**
   * Best-effort fare, in BRL.
   * Surge/booster markers render as separate nodes "+R$ 4,25" and are excluded
   * (leading "+"). Mission text such as "R$ 55 a mais por 50 viagens" does not
   * match the exact node pattern, so it never becomes an offer fare.
   */
  fun parseFare(segments: List<String>): String? {
    val exact = segments.mapNotNull { s ->
      val m = FEE_EXACT_RE.matchEntire(normalize(s)) ?: return@mapNotNull null
      if (m.groupValues[1] == "+") null else parseMoney(m.groupValues[2])
    }.filter { it > 0.0 }.maxOrNull()
    if (exact != null) return formatBrl(exact)

    // Fallback: scan the joined text but keep excluding "+R$" surge markers.
    val joined = segments.joinToString(" | ")
    val values = FEE_RE.findAll(joined).mapNotNull { m ->
      if (m.groupValues[1] == "+") null else parseMoney(m.groupValues[2])
    }.filter { it > 0.0 }.toList()
    if (values.isEmpty()) return null
    return formatBrl(values.maxOrNull() ?: return null)
  }

  /**
   * Uma "perna" da viagem: a distância até o passageiro e a distância da
   * viagem em si. A Uber mostra as duas no mesmo cartão, cada uma com seu
   * tempo e sua distância, e o valor por km/h que ela exibe usa a SOMA das
   * duas — não a maior. Exemplo real: "9min (5.4 km)" + "6 minutos (1.9 km)".
   */
  private data class Leg(val km: Double?, val minutes: Int?)

  /** Nó que carrega tempo e distância juntos ("9min (5,4 km)"), sem valor R$. */
  private fun legFromNode(node: String): Leg? {
    val s = normalize(node)
    if (s.isEmpty()) return null
    // "R$ 2,77/km" é o valor por km que a Uber já calcula, não uma perna.
    if (s.contains("r$", ignoreCase = true)) return null
    if (STATS_PHRASE_RE.containsMatchIn(s)) return null
    val km = DISTANCE_RE.find(s)?.let { m -> toDouble(m.groupValues[1]) }?.takeIf { it > 0.0 }
    val min = DURATION_ANY_RE.find(s)?.let { m -> m.groupValues[1].toIntOrNull() }?.takeIf { it > 0 }
    // Só somamos pernas completas: a linha da Uber traz os dois juntos. Um nó
    // com só km ("7,3 km") ou só tempo ("6 min") fica para o parse antigo.
    if (km == null || min == null) return null
    return Leg(km, min)
  }

  /**
   * Soma as pernas da viagem quando o OCR traz as duas (coleta + destino).
   * Retorna null quando menos de duas pernas completas são reconhecidas, para o
   * chamador cair no parse antigo por nó/texto.
   */
  private fun summedLegs(segments: List<String>): Leg? {
    val legs = segments.mapNotNull { legFromNode(it) }
    // Só soma quando há DUAS pernas (coleta + viagem) de fato. Com uma perna
    // isolada a soma não é diferente do parse por nó, e uma linha solta com
    // "km + min" (ex.: o total lido de volta) tomava o lugar da distância/duração
    // reais. Com <2, o chamador cai no parse antigo por nó/texto.
    if (legs.size < 2) return null
    // Máximo de 2 pernas reais (coleta + destino). Se o OCR devolver mais
    // (ex.: leu o próprio card "15 min 5.7 km" como perna), ficam as duas de
    // menor km — a linha do card é sempre o total anterior, maior que as pernas.
    val kept = if (legs.size > 2) legs.sortedBy { it.km ?: Double.MAX_VALUE }.take(2) else legs
    val km = kept.mapNotNull { it.km }.takeIf { it.isNotEmpty() }?.sum()
    val minutes = kept.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()
    if (km == null && minutes == null) return null
    return Leg(km, minutes)
  }

  /** Best-effort distance in km. Prefers a node that is only the distance. */
  fun parseDistanceKm(segments: List<String>): String? {
    summedLegs(segments)?.km?.let { return formatKm(it) }
    val exact = segments.mapNotNull { s ->
      val m = DISTANCE_EXACT_RE.matchEntire(normalize(s)) ?: return@mapNotNull null
      toDouble(m.groupValues[1])
    }.filter { it > 0.0 }.maxOrNull()
    if (exact != null) return formatKm(exact)

    val joined = segments.joinToString(" | ")
    val values = DISTANCE_RE.findAll(joined).mapNotNull { m ->
      toDouble(m.groupValues[1])
    }.filter { it > 0.0 }.toList()
    if (values.isEmpty()) return null
    return formatKm(values.maxOrNull() ?: return null)
  }

  /**
   * Best-effort duration / ETA in minutes.
   * Prefers a node that is exactly "22 min". Falls back to the joined text but
   * removes stats sentences like "últimos 30 minutos" before matching.
   */
  fun parseDuration(segments: List<String>): String? {
    summedLegs(segments)?.minutes?.let { return it.toString() }
    val exact = segments.mapNotNull { s ->
      DURATION_EXACT_RE.matchEntire(normalize(s))?.let { m -> m.groupValues[1].toIntOrNull() }
    }.filter { it > 0 }.maxOrNull()
    if (exact != null) return exact.toString()

    val joined = " " + segments.joinToString(" ") + " "
    val blocked = Regex(
      // Accent-insensitive: "média"/"media", "médias"/"medias".
      """(?i)(últimos?|últimas?|última|cerca de|m[eé]di[ao]s?|base)\s*(?:de\s+)?(\d{1,3})\s*(?:min|mins|minutos|minuto)"""
    )
    val cleaned = blocked.replace(joined, "")
    val values = Regex("""\b(\d{1,3})\s*(?:min|mins|minutos|minuto)\b""")
      .findAll(cleaned)
      .mapNotNull { it.groupValues[1].toIntOrNull() }
      .filter { it > 0 }.toList()
    if (values.isEmpty()) return null
    return (values.maxOrNull() ?: return null).toString()
  }

  /**
   * Nota do passageiro. A Uber mostra o valor perto de uma estrela ou da
   * palavra "nota"; o OCR costuma ler o ícone da estrela como `*` ou `★`, e no
   * log real a nota veio como um "4,9" isolado (a estrela é um drawable que o
   * ML Kit não descreve). Por isso, quando a estrutura da oferta já passou na
   * validacão e existe um número isolado de 1.0 a 5.0, aceitamos como nota —
   * o gate [rejectReason] é o que impede qualquer "4,9" de outra tela virar nota.
   */
  fun parseRating(segments: List<String>, hasOffer: Boolean = false): Double? {
    // Preferência máxima: a própria linha com estrela/nota.
    for (segment in segments) {
      val s = normalize(segment)
      RATING_RE.find(s)?.let { m ->
        // Quatro grupos alternativos; o que casou é o primeiro não vazio.
        for (group in listOf(1, 2, 3, 4)) {
          val raw = m.groupValues[group]
          if (raw.isNotEmpty()) {
            toDouble(raw)?.let { return it }
          }
        }
      }
    }
    // Sem estrela/nota: o OCR da Uber às vezes entrega só a nota ("4,9"), lê o
    // ícone como "A" ("A 4,87 (90)") ou traz a contagem junto ("4,78 (90)"). Um
    // decimal isolado de 1.0 a 5.0 só é nota se for dentro de uma oferta já
    // validada; tarifa, distância e duração falham no regex do número solto.
    if (hasOffer) {
      val BARE_RATING_RE =
        Regex("""(?:^|[★*A])\s*(\d{1,2}[.,]\d{1,2})\s*(?:\(\d+\))?\|?\s*$""")
      for (segment in segments) {
        val s = normalize(segment)
        BARE_RATING_RE.find(s)?.let { m ->
          val raw = m.groupValues[1]
          if (raw.isNotEmpty()) {
            toDouble(raw)?.let { v -> if (v in 1.0..5.0) return v }
          }
        }
      }
    }
    return null
  }

  fun platformForPackage(packageName: String): String = when (packageName) {
    "com.ubercab.driver" -> "uber"
    "com.app99.driver" -> "app99"
    else -> packageName
  }

  /**
   * Central analysis step used by both window and notification paths.
   * Creates a validated [OfferAnalysis] or an analysis carrying the reason it
   * was rejected. A plain "I captured text" is deliberately NOT an offer.
   *
   * Validation (two mutually qualifying paths):
   *  - require an offer keyword AND a fare AND at least one of (km | duration);
   *  - OR a full structure (fare + km + duration) — km presence alone is a very
   *    strong discriminator against mission/surge/home panels.
   */
  fun analyze(segments: List<String>): OfferAnalysis {
    val joined = normalize(segments.joinToString(" | "))
    val lower = joined.lowercase()
    val hasKeyword = OFFER_KEYWORDS.any { lower.contains(it) }
    val fareStr = parseFare(segments)
    val distanceStr = parseDistanceKm(segments)
    val durationStr = parseDuration(segments)

    // O card só pode aparecer quando a corrida "tocar": exigimos um valor, a
    // estrutura da oferta e um botão/ação de decisão. Sem a keyword de ação,
    // telas de Ganhos, Promoções e "Alta demanda" passariam como oferta só por
    // terem R$ e km.
    val hasAction = ACTION_KEYWORDS.any { lower.contains(it) }
    val fareValue = fareStr?.let(::toDouble)
    // Sanity band on the fare. OCR sometimes drops the decimal separator and
    // "R$ 17,03" comes back as "R$ 1703" - the regex happily matches it (4
    // digits) and the offer was accepted at a hundred times its real value. No
    // single ride is worth over R$ 500, so anything above is a misread, not a
    // fare. Below the floor it is the same story in reverse (cents read as
    // reais).
    val fareImplausible = fareValue != null &&
      (fareValue < 0.5 || fareValue > 500.0)
    val rejectReason = when {
      fareStr == null -> "sem valor (R$) identificado"
      fareImplausible -> "tarifa implausível (${fareStr} — separador decimal perdido?)"
      !hasKeyword -> "sem keyword de oferta"
      distanceStr == null -> "sem distância"
      durationStr == null -> "sem duração"
      !hasAction -> "sem ação de decisão (Aceitar/Selecionar)"
      else -> null
    }
    // A nota "solta" do OCR só vale dentro de uma oferta já validada.
    val rating = parseRating(segments, hasOffer = rejectReason == null)

    return OfferAnalysis(
      fare = fareStr?.let(::toDouble),
      distanceKm = distanceStr?.let(::toDouble),
      durationMinutes = durationStr?.toIntOrNull(),
      fareStr = fareStr.orEmpty(),
      distanceStr = distanceStr.orEmpty(),
      durationStr = durationStr.orEmpty(),
      rating = rating,
      hasKeyword = hasKeyword,
      allText = joined,
      rejectReason = rejectReason,
    )
  }

  /**
   * Notification path (kept for the NotificationListenerService). Always returns
   * a map (like before) but callers should only persist offers when isOffer=true.
   */
  fun extract(
    key: String,
    packageName: String,
    title: String?,
    text: String?,
    bigText: String?,
    subText: String?,
    postedAt: Long,
  ): Map<String, Any?> {
    val segments = listOfNotNull(title, text, bigText, subText)
    val analysis = analyze(segments)
    return offerMap(
      id = key,
      packageName = packageName,
      title = title.orEmpty(),
      text = text.orEmpty(),
      analysis = analysis,
      capturedAt = postedAt,
    )
  }

  /**
   * Window/accessibility path. Returns the structured offer or NULL when the
   * analyzed window is not a validated ride offer (callers must not invent one).
   */
  fun extractOffer(
    key: String,
    packageName: String,
    windowTexts: List<String>,
    capturedAt: Long,
  ): Map<String, Any?>? {
    val analysis = analyze(windowTexts)
    if (!analysis.isOffer) return null
    return offerMap(
      id = key,
      packageName = packageName,
      title = "",
      text = "",
      analysis = analysis,
      capturedAt = capturedAt,
    )
  }

  private fun offerMap(
    id: String,
    packageName: String,
    title: String,
    text: String,
    analysis: OfferAnalysis,
    capturedAt: Long,
  ): Map<String, Any?> {
    val rawText = analysis.allText.take(400)
    return linkedMapOf(
      "id" to id,
      "type" to "ride_offer",
      "platform" to platformForPackage(packageName),
      "packageName" to packageName,
      // structured values
      "fare" to analysis.fare,
      "distance" to analysis.distanceKm,
      "durationMinutes" to analysis.durationMinutes,
      "rating" to analysis.rating,
      "capturedAt" to capturedAt,
      "status" to "detected",
      "rawText" to rawText,
      // display helpers (kept for the existing UI/card)
      "title" to title,
      "text" to text,
      "fee" to analysis.fareStr,
      "distanceKm" to analysis.distanceStr,
      "etaMin" to analysis.durationStr,
      "isOffer" to analysis.isOffer,
      "rejectReason" to analysis.rejectReason,
    )
  }

  /** Signature used to deduplicate the same offer (fare + distance + duration). */
  /**
   * Identidade estável de uma oferta, para o dedup.
   *
   * A tarifa não pode entrar crua. Quando o app só expõe o valor por km
   * ("R$ 1,37/km aprox."), a leitura é o produto dessa taxa pela distância — e o
   * último centavo muda a cada varredura. Numa sessão real a MESMA oferta produziu
   * 13.0, 13.01, 13.018, 13.021, 13.024 e 13.044287, e a comparação crua tratava
   * cada uma como oferta nova: o cartão tremia e o Flutter recebia um
   * ride_offer por leitura.
   *
   * Por isso a assinatura guarda a tarifa arredondada para 10 centavos e a
   * distância para 100 metros: o mesmo valor por km continua colidindo (a
   * variação observada foi de centavos), enquanto ofertas de verdade diferentes
   * continuam distintas.
   */
  fun offerSignature(offer: Map<String, Any?>): String {
    fun num(key: String): Double? {
      val v = offer[key]
      return (v as? Number)?.toDouble()
        ?: v?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
    }
    val fare = num("fare")?.let { Math.round(it * 10.0) / 10.0 }
    val distance = num("distance")?.let { Math.round(it * 100.0) / 100.0 }
    val duration = num("durationMinutes")
    return "$fare|$distance|$duration"
  }
}