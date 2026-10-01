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
  fun `reads origin and destination from the bare place lines`() {
    val offer = extract(
      listOf("Cariacica", "Nova Vila Velha", "8,4 km", "R$ 21,90", "12 min", "Aceitar"),
    )
    assertNotNull(offer)
    assertEquals("Cariacica", offer!!.get("pickup"))
    assertEquals("Nova Vila Velha", offer.get("dropoff"))
  }

  @Test
  fun `keeps the neighbourhood from a full address`() {
    val offer = extract(
      listOf(
        "Rua José Joaquim da Silva, Centro, Vitória", "Av. Nossa Senhora, Jardim da Penha, Serra",
        "R$ 21,90", "8,4 km", "12 min", "Aceitar",
      ),
    )
    assertNotNull(offer)
    // 3 partes: o card mostra o bairro do meio.
    assertEquals("Centro", offer!!.get("pickup"))
    assertEquals("Jardim da Penha", offer.get("dropoff"))
  }

  @Test
  fun `skips the house number in the middle of the address`() {
    val offer = extract(
      listOf(
        "Rua Chagas Freitas, 300, Vila Nova de Colaresi", "Av. Nossa Senhora, Jardim da Penha",
        "R$ 21,90", "8,4 km", "12 min", "Aceitar",
      ),
    )
    assertNotNull(offer)
    // "300" é número de casa: cai para a próxima parte não numérica.
    assertEquals("Vila Nova de Colaresi", offer!!.get("pickup"))
    // 2 partes com rua no começo: o card mostra o bairro depois dela.
    assertEquals("Jardim da Penha", offer.get("dropoff"))
  }

  @Test
  fun `keeps a two-part address start`() {
    val offer = extract(
      listOf(
        "Av. Talma Rodrigues Ribeiro, Centro industrial", "Cariacica",
        "R$ 21,90", "8,4 km", "12 min", "Aceitar",
      ),
    )
    assertNotNull(offer)
    // 2 partes com rua no começo: mostra o bairro, não a avenida.
    assertEquals("Centro industrial", offer!!.get("pickup"))
    assertEquals("Cariacica", offer.get("dropoff"))
  }

  @Test
  fun `sums pickup and dropoff legs like uber does`() {
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
  fun `route ignores the action button and the rating node`() {
    // "Aceitar" e "* 4,78 (90)|" nunca viram coleta/destino.
    val offer = extract(
      listOf(
        "* 4,78 (90)|", "Aceitar", "Cariacica", "Nova Vila Velha",
        "8,4 km", "R$ 21,90", "12 min",
      ),
    )
    assertNotNull(offer)
    assertEquals("Cariacica", offer!!.get("pickup"))
    assertEquals("Nova Vila Velha", offer.get("dropoff"))
  }

  @Test
  fun `routes streets to the neighbourhood on two parts`() {
    // Log real: coleta abreviada em "Cariacica", destino com rua + bairro.
    val offer = extract(
      listOf(
        "Av. Talma Rodrigues Ribeiro, Centro industrial",
        "Rua Chagas Freitas, 300, Vila Nova de Colaresi",
        "9min (5.4 km)", "6 minutos (1.9 km)", "R$ 12,06", "Aceitar",
      ),
    )
    assertNotNull(offer)
    assertEquals("Centro industrial", offer!!.get("pickup"))
    assertEquals("Vila Nova de Colaresi", offer.get("dropoff"))
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
}