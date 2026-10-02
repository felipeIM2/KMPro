package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferParserTest {
  private fun extract(texts: List<String>) = OfferParser.extractOffer(
    key = "test-1",
    packageName = "com.ubercab.driver",
    windowTexts = texts,
    capturedAt = 0,
  )

  @Test
  fun `parses a real-like uber offer`() {
    val offer = extract(
      listOf("Página inicial", "R$ 15,08", "7,3 km", "11 minutos", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(15.08, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
    assertEquals(7.3, ((offer.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(11.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
    assertEquals("com.ubercab.driver", offer["packageName"])
    assertTrue(offer["isOffer"] as? Boolean == true)
  }

  @Test
  fun `rejects a home screen without offer keywords`() {
    val offer = extract(listOf("Página inicial", "Você está online", "1-12 min"))
    assertNull(offer)
  }

  @Test
  fun `keeps stats text (media de) away from duration`() {
    val analysis = OfferParser.analyze(listOf("7,3 km", "e a media de 8 minutos"))
    assertEquals(null, analysis.durationMinutes)
  }

  @Test
  fun `normalizes comma decimals after R$`() {
    val offer = extract(listOf("R$ 18,50", "18 min", "6.2 km", "aceitar corrida"))
    assertEquals(18.50, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
    assertEquals(6.2, ((offer.get("distance") as? Double) ?: 0.0), 0.001)
  }

  @Test
  fun `profile and status are set`() {
    val offer = extract(listOf("R$ 12,00", "3 km", "8 min", "buscar passageiro"))
    assertEquals("ride_offer", offer!!["type"])
    assertFalse((offer["status"] as? String).isNullOrEmpty())
  }

  @Test
  fun `parses the offer card as read by OCR on a real uber screen`() {
    // Lines exactly as ML Kit returned them from takeScreenshot().
    val offer = extract(
      listOf(
        "Cariacica",
        "Nova Vila Velha",
        "8,4 km",
        "R$ 21,90",
        "12 min",
        "Aceitar",
        "Voltar",
      ),
    )
    assertNotNull(offer)
    assertEquals(21.90, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
    assertEquals(8.4, ((offer.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(12.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
  }

  @Test
  fun `ocr noise on the uber home screen is not an offer`() {
    // Real OCR output of the offline home screen: it has a date, a "R$ 8"
    // promotion and a "1-3 minutos" wait estimate, but no ride to accept.
    val offer = extract(
      listOf(
        "17:21 ter., 29 de set.",
        "Alta demanda: Serra",
        "Os tempos de espera são de cerca de 1-3 minutos, com base nos dados dos últimos",
        "R$ 8 adicionais ao concluir 2 viagens (apenas para viagens)",
        "Até Domingo, 23:59",
        "Descubra",
        "Ficar online",
        "Ganhos",
        "Caixa de entrada",
        "Menu",
      ),
    )
    assertNull(offer)
  }

  @Test
  fun `ignores surge badge when a real fare is present`() {
    val offer = extract(
      listOf("R$ 18,50", "+R$ 4,25 de bônus", "7,3 km", "11 min", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(18.50, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
  }

  @Test
  fun `reads the passenger rating next to a star`() {
    val offer = extract(
      listOf(
        "Cariacica", "Nova Vila Velha", "8,4 km", "R$ 21,90", "12 min",
        "4,9", "Aceitar",
      ),
    )
    assertNotNull(offer)
    assertEquals(4.9, ((offer!!.get("rating") as? Double) ?: -1.0), 0.001)
  }

  @Test
  fun `reads the rating when the star is glued to the value`() {
    val offer = extract(
      listOf("R$ 21,90", "8,4 km", "12 min", "★4.87", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(4.87, ((offer!!.get("rating") as? Double) ?: -1.0), 0.001)
  }

  @Test
  fun `rating is null when the offer does not show one`() {
    val offer = extract(
      listOf("Cariacica", "Nova Vila Velha", "8,4 km", "R$ 21,90", "12 min", "Aceitar"),
    )
    assertNotNull(offer)
    assertNull(offer!!.get("rating"))
  }

  @Test
  fun `sums the trip legs like uber does`() {
    // Log real: a perna da coleta e a da viagem vem cada uma com km + tempo.
    val offer = extract(
      listOf(
        "Parque da Lagoa", "R. São Carlos",
        "9min (5.4 km)", "6 minutos (1.9 km)",
        "R$ 12,06", "Aceitar",
      ),
    )
    assertNotNull(offer)
    // 5.4 + 1.9 = 7.3 km, não o maior (5.4) — igual ao R$ 1,65/km da Uber.
    assertEquals(7.3, ((offer!!.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(15.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
  }

  @Test
  fun `lone distance and duration still fall back to single values`() {
    val offer = extract(
      listOf("5,4 km", "30 min", "R$ 12,06", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(5.4, ((offer!!.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(30.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
  }

  @Test
  fun `a gains screen with a fare and distance is not an offer`() {
    // Tinha R$ e km, mas não tem botão de decisão: não pode virar cartão.
    assertNull(
      extract(
        listOf("Ganhos", "R$ 1.248,00", "12,4 km", "R$ 320,50", "15 min", "Meta diária"),
      ),
    )
  }

  @Test
  fun `an offer without the action button is rejected`() {
    assertNull(
      extract(listOf("Cariacica", "Nova Vila Velha", "8,4 km", "R$ 21,90", "12 min")),
    )
  }

  @Test
  fun `caps summed legs at two, keeping the smallest`() {
    // Terceira perna = linha do próprio card lida de volta pelo OCR.
    val offer = extract(
      listOf(
        "Parque da Lagoa", "R. São Carlos",
        "9min (5.4 km)", "6 minutos (1.9 km)", "15 min (7.3 km)",
        "R$ 12,06", "Aceitar",
      ),
    )
    assertNotNull(offer)
    // 5.4 + 1.9 = 7.3; a perna extra (7.3, do card) não entra duas vezes.
    assertEquals(7.3, ((offer!!.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(15.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
  }

  @Test
  fun `reads the rating when OCR renders the star as A with a count`() {
    val offer = extract(
      listOf("R$ 21,90", "8,4 km", "12 min", "A 4,87 (90)", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(4.87, ((offer!!.get("rating") as? Double) ?: -1.0), 0.001)
  }

  @Test
  fun `reads the rating from an asterisk star with review count`() {
    val offer = extract(
      listOf("R$ 21,90", "8,4 km", "12 min", "* 4,78 (90)|", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(4.78, ((offer!!.get("rating") as? Double) ?: -1.0), 0.001)
  }

  @Test
  fun `the same offer with a shaky last cent keeps one signature`() {
    // Regression, seen live: the same panel OCR'd as 13.0, then 13.01, then 13.0
    // across three scans, and the raw string signature let all three through, so
    // the card flickered and Flutter got three ride_offer events.
    val a = mapOf("fare" to 13.0, "distance" to 1.4, "durationMinutes" to 5.0)
    val b = mapOf("fare" to 13.01, "distance" to 1.4, "durationMinutes" to 5.0)
    val c = mapOf("fare" to 13.0, "distance" to 1.4, "durationMinutes" to 5.0)
    assertEquals(OfferParser.offerSignature(a), OfferParser.offerSignature(b))
    assertEquals(OfferParser.offerSignature(b), OfferParser.offerSignature(c))
  }

  @Test
  fun `genuinely different offers keep different signatures`() {
    val a = mapOf("fare" to 13.0, "distance" to 1.4, "durationMinutes" to 5.0)
    val b = mapOf("fare" to 13.05, "distance" to 1.4, "durationMinutes" to 5.0)
    val c = mapOf("fare" to 13.0, "distance" to 2.4, "durationMinutes" to 5.0)
    val d = mapOf("fare" to 13.0, "distance" to 1.4, "durationMinutes" to 6.0)
    val sigs = listOf(a, b, c, d).map { OfferParser.offerSignature(it) }
    assertEquals(4, sigs.distinct().size)
  }

  @Test
  fun `a non numeric fare still signs rather than crashing`() {
    val sig = OfferParser.offerSignature(mapOf("fare" to "?", "distance" to "?", "durationMinutes" to "?"))
    assertEquals("null|null|null", sig)
  }

  @Test
  fun `the same per km estimate is one offer across its cent noise`() {
    // Real readings of one Uber panel, all the same R$ 1,37/km estimate:
    // 13.0, 13.01, 13.018, 13.021, 13.024, 13.044287.
    val noisy = listOf(13.0, 13.01, 13.018, 13.021, 13.024, 13.044287)
    val sigs = noisy.map {
      OfferParser.offerSignature(
        mapOf("fare" to it, "distance" to 9.5, "durationMinutes" to 15.0),
      )
    }
    assertEquals("one signature expected, got $sigs", 1, sigs.distinct().size)
  }

  @Test
  fun `different fares still separate`() {
    val a = OfferParser.offerSignature(mapOf("fare" to 13.0, "distance" to 9.5, "durationMinutes" to 15.0))
    val b = OfferParser.offerSignature(mapOf("fare" to 13.5, "distance" to 9.5, "durationMinutes" to 15.0))
    val c = OfferParser.offerSignature(mapOf("fare" to 13.0, "distance" to 9.6, "durationMinutes" to 15.0))
    val d = OfferParser.offerSignature(mapOf("fare" to 13.0, "distance" to 9.5, "durationMinutes" to 16.0))
    assertEquals(4, listOf(a, b, c, d).distinct().size)
  }

  @Test
  fun `rejects a fare that lost its decimal separator`() {
    // Real reading from a live Uber session: the screen showed "R$ 17,03" and OCR
    // returned "R$ 1703". The regex matches 4 digits happily, so the offer was
    // accepted at a hundred times its real value and shown on the card as R$
    // 1.703,00.
    val analysis = OfferParser.analyze(
      listOf("R$ 1703", "3,4 km", "17 min", "Aceitar corrida"),
    )
    assertFalse("misread fare accepted: $analysis", analysis.isOffer)
    assertTrue(
      "reject reason should name the suspect: ${analysis.rejectReason}",
      analysis.rejectReason?.contains("implaus") == true,
    )
    // The misread value is kept for the diagnostic log, but it must never reach a
    // card: only `isOffer` decides that.
    assertEquals(1703.0, analysis.fare ?: 0.0, 0.001)
    assertNull(extract(listOf("R$ 1703", "3,4 km", "17 min", "Aceitar corrida")))
  }

  @Test
  fun `keeps accepting the same fare when the separator survives`() {
    val offer = extract(listOf("R$ 17,03", "3,4 km", "17 min", "Aceitar corrida"))
    assertEquals(17.03, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
  }

  @Test
  fun `a long expensive ride is still an offer`() {
    // The band rejects misreads, not real money: an airport run over R$ 100 must
    // not be thrown away with the misreads.
    val offer = extract(listOf("R$ 480,00", "38,6 km", "52 min", "Aceitar corrida"))
    assertEquals(480.00, ((offer!!.get("fare") as? Double) ?: 0.0), 0.001)
  }

  @Test
  fun `reads a fare with a thousands separator as thousands`() {
    // "R$ 1.248" vinha como R$ 1.248,00 -> 1.248 interpretado como decimal
    // (R$ 1,25), dentro da faixa, e passava como oferta. Agora sai 1248 e a
    // faixa de plausibilidade barra a leitura como milhar perdido.
    assertEquals(1248.0, OfferParser.parseMoney("1.248") ?: 0.0, 0.001)
    assertEquals(1248.0, OfferParser.parseMoney("1.248,00") ?: 0.0, 0.001)
    assertEquals(18.5, OfferParser.parseMoney("18.5") ?: 0.0, 0.001)
    assertEquals(18.5, OfferParser.parseMoney("18,50") ?: 0.0, 0.001)
    val analysis = OfferParser.analyze(listOf("R$ 1.248", "3,4 km", "17 min", "Aceitar corrida"))
    assertFalse("misread thousands accepted: $analysis", analysis.isOffer)
    assertTrue(
      "should be rejected by the plausibility band: ${analysis.rejectReason}",
      analysis.rejectReason?.contains("implaus") == true,
    )
  }

  @Test
  fun `a single stray leg does not override the real distance`() {
    // Uma linha solta "km + min" (ex.: total relido) não pode virar a distância
    // quando não há as duas pernas da viagem; o parse por nó assume e devolve o
    // nó de distância de verdade.
    val offer = extract(
      listOf("Parque da Lagoa", "9 min (5,7 km)", "R$ 12,06", "3,4 km", "17 min", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals(3.4, ((offer!!.get("distance") as? Double) ?: 0.0), 0.001)
    assertEquals(17.0, ((offer.get("durationMinutes") as? Number)?.toDouble() ?: 0.0), 0.001)
  }

  @Test
  fun `rejects a fare read in cents`() {
    // Same story in reverse: "R$ 1703" read as cents of a R$ 1,70 ride shows up
    // as "R$ 0,03"-ish values, and anything under the floor is equally suspect.
    val analysis = OfferParser.analyze(listOf("R$ 0,3", "3,4 km", "17 min", "Aceitar"))
    assertFalse("implausibly small fare accepted: $analysis", analysis.isOffer)
  }
}