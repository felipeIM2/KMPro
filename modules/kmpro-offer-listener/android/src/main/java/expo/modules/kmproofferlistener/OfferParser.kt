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
  private val FEE_RE =
    Regex("""R\$\s*([-+]?)\s*(\d{1,4}(?:[.,]\d{1,3})?)""", RegexOption.IGNORE_CASE)
  // A node that contains ONLY the fare: "R$ 18,50" (matches whole segment).
  private val FEE_EXACT_RE =
    Regex("""R\$\s*([-+]?)\s*(\d{1,4}(?:[.,]\d{1,3})?)\s*""", RegexOption.IGNORE_CASE)
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
  // Street-like anchor for pickup/destination (best effort):
  /**
   * Logradouro + bairro + cidade. A forma curta ("R.", "Av.", "Al.") precisa de
   * lookahead para a fronteira depois do ponto, senão `\b` nunca casa e a regex
   * ignora a linha inteira.
   */
  private val STREET_PREFIX =
    """(?:\b(?:avenida|rua|estrada|alameda|travessa|rodovia|praça)|\b(?:av|ru|est|al|r)\.(?=\s)|br-?\d*)"""

  private val STREET_RE =
    Regex("""$STREET_PREFIX[^|]{2,70}""", RegexOption.IGNORE_CASE)

  /** Logradouro no COMEÇO da parte ("Av. Talma…") — usado para separar a rua
   *  do bairro quando a linha tem só dois blocos. */
  private val STREET_AT_START_RE =
    Regex("""^\s*$STREET_PREFIX""", RegexOption.IGNORE_CASE)

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

  /** Só número e unidade, tipo "8,4 km" ou "R$ 21,90" — nunca um lugar. */
  private val NUMERIC_ONLY_RE =
    Regex("""^\+?\s*(?:r\$\s*)?\d[\d.,]*\s*(?:km|min|h)?\s*(?:de\s*)?(?:b[oô]nus)?$""",
      RegexOption.IGNORE_CASE,
    )

  private val CLOCK_RE = Regex("""^\d{1,2}\s*[:h]\s*\d{2}""")

  private val PLACEHOLDER_TOKENS = listOf(
    "descubra", "ganhos", "menu", "ajuda", "perfil", "viagem", "promoções",
  )

  /** Structured analysis produced for a text feed. */
  data class OfferAnalysis(
    val fare: Double? = null,
    val distanceKm: Double? = null,
    val durationMinutes: Int? = null,
    val fareStr: String = "",
    val distanceStr: String = "",
    val durationStr: String = "",
    val pickup: String = "",
    val dropoff: String = "",
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
      if (m.groupValues[1] == "+") null else toDouble(m.groupValues[2])
    }.filter { it > 0.0 }.maxOrNull()
    if (exact != null) return formatBrl(exact)

    // Fallback: scan the joined text but keep excluding "+R$" surge markers.
    val joined = segments.joinToString(" | ")
    val values = FEE_RE.findAll(joined).mapNotNull { m ->
      if (m.groupValues[1] == "+") null else toDouble(m.groupValues[2])
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
   * Retorna null se nenhuma perna for reconhecida, para o chamador cair no
   * parse antigo por nó/texto.
   */
  private fun summedLegs(segments: List<String>): Leg? {
    val legs = segments.mapNotNull { legFromNode(it) }
    if (legs.isEmpty()) return null
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

  /**
   * Origem e destino.
   *
   * O OCR entrega o card da Uber como linhas soltas ("Cariacica",
   * "Nova Vila Velha"), sem seta, e os endereços completos às vezes trazem o
   * bairro entre o logradouro e a cidade ("Rua X, Centro, Vitória"). Por isso:
   * 1. usa as linhas de endereço quando existirem, na ordem em que aparecem;
   * 2. senão usa os dois primeiros segmentos "de texto" que não são número,
   *    keyword ou rótulo de UI.
   */
  private fun parseRoute(segments: List<String>): Pair<String, String> {
    val joined = normalize(segments.joinToString(" | "))
    val street = STREET_RE.findAll(joined).map { it.value.trim() }.toList()

    val places = segments
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .filter { !isUiNoise(it) }
      .filter { !NUMERIC_ONLY_RE.matches(it) }
    // Lugares que não são o próprio endereço com logradouro já casado acima.
    val otherPlaces = places.filterNot { p ->
      street.any { it == p || p.startsWith(it) || it.startsWith(p) }
    }

    if (street.size >= 2) return withBairro(street[0]) to withBairro(street[1])
    if (street.size == 1) {
      // Ex.: "Av. Talma Rodrigues Ribeiro, Centro industrial" + "Cariacica".
      return withBairro(street[0]) to (otherPlaces.firstOrNull() ?: "")
    }

    if (otherPlaces.size >= 2) return otherPlaces[0] to otherPlaces[1]
    if (otherPlaces.size == 1) return otherPlaces[0] to ""
    return "" to ""
  }

  /**
   * Escolhe o que exibir no card a partir de um endereço separado por vírgulas.
   * Regra combinada com o usuário:
   *  - 3 ou mais partes: pega a do meio (o bairro);
   *  - 2 partes: pega a que NÃO é logradouro — "Av. Talma…, Centro industrial"
   *    mostra "Centro industrial" (a rua ia voltar de novo); "Cariacica, Serra"
   *    mostra "Cariacica" (primeira, sem prefixo de rua);
   *  - 1 parte (sem vírgula): mostra o que tiver.
   * Partes que são só número são puladas, porque no formato
   * "Rua X, 300, Bairro" a segunda parte é o número da casa, não o bairro.
   */
  private fun withBairro(address: String): String {
    val parts = address.split(',').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return address.trim()
    if (parts.size == 1) return parts[0]
    if (parts.size == 2) {
      val bairro = parts.firstOrNull { part -> !isNumberPart(part) && !STREET_AT_START_RE.containsMatchIn(part) }
      return bairro ?: parts[0]
    }
    // 3+: começa no meio e pula partes puramente numéricas.
    val meaningful = parts.drop(1).firstOrNull { !isNumberPart(it) }
    return meaningful ?: parts[1]
  }

  /** "300", "1200", "45" — número de casa/rua, nunca um bairro. */
  private fun isNumberPart(part: String): Boolean = part.matches(Regex("""\d+"""))

  /** Linhas que nunca são um lugar: botões, rótulos de UI e horário. */
  private val RATING_NODE_RE =
    Regex("""^[★*A]?\s*\d{1,2}[.,]\d{1,2}\s*(?:\(\d+\))?\|?\s*$""")

  private fun isUiNoise(line: String): Boolean {
    val s = normalize(line)
    if (s.isEmpty()) return true
    if (s.length > 60) return true
    if (CLOCK_RE.matches(s)) return true
    if (PLACEHOLDER_TOKENS.any { s.equals(it, ignoreCase = true) }) return true
    if (RATING_NODE_RE.matches(s)) return true
    val l = s.lowercase()
    // Botões e cabeçalhos de navegação da Uber/99 ("Aceitar"/"Selecionar" vêm
    // capitalizados no OCR, então o filtro precisa ignorar caixa).
    if (l.contains("aceitar") || l.contains("selecionar") || l.contains("voltar")) return true
    if (l.contains("cancelar") || l.contains("recusar") || l.contains("confirmar")) return true
    if (l.startsWith("r$") || l.endsWith("min") || l.endsWith("km")) return true
    if (s.equals("destino", ignoreCase = true) || s.equals("origem", ignoreCase = true)) return true
    if (l.startsWith("página inicial") || l.startsWith("menu")) return true
    if (l.startsWith("caixa de entrada") || l.startsWith("ganhos")) return true
    if (l.contains("ficar online") || l.contains("você está")) return true
    return false
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
    val (pickup, dropoff) = parseRoute(segments)

    // O card só pode aparecer quando a corrida "tocar": exigimos um valor, a
    // estrutura da oferta e um botão/ação de decisão. Sem a keyword de ação,
    // telas de Ganhos, Promoções e "Alta demanda" passariam como oferta só por
    // terem R$ e km.
    val hasAction = ACTION_KEYWORDS.any { lower.contains(it) }
    val rejectReason = when {
      fareStr == null -> "sem valor (R$) identificado"
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
      pickup = pickup,
      dropoff = dropoff,
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
      "pickup" to analysis.pickup,
      "dropoff" to analysis.dropoff,
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
  fun offerSignature(offer: Map<String, Any?>): String {
    val fare = offer["fare"]?.toString().orEmpty()
    val distance = offer["distance"]?.toString().orEmpty()
    val duration = offer["durationMinutes"]?.toString().orEmpty()
    return "$fare|$distance|$duration"
  }
}