package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferRegionFinderTest {
  private val screenWidth = 1200
  private val screenHeight = 1920

  // shortSide = 1200 -> verticalPad = max(union*0.6, 216, 24); horizontalPad = 72.
  private val vpad = (minOf(screenWidth, screenHeight) * OfferRegionFinder.VERTICAL_PAD_FRACTION).toInt()
  private val hpad = (screenWidth * OfferRegionFinder.HORIZONTAL_PAD_FRACTION).toInt()

  private fun analysis(
    fare: String = "",
    distance: String = "",
    duration: String = "",
    isOffer: Boolean = true,
    hasKeyword: Boolean = true,
  ) = OfferParser.OfferAnalysis(
    fareStr = fare,
    distanceStr = distance,
    durationStr = duration,
    hasKeyword = hasKeyword,
    rejectReason = if (isOffer) null else "teste",
  )

  @Test
  fun `sem oferta valida e sem keyword nao encontra regiao`() {
    val lines = listOf(OcrLine("R$ 17,03", 100, 400, 300, 440))
    val region = OfferRegionFinder.find(
      analysis(fare = "17,03", isOffer = false, hasKeyword = false),
      lines,
      screenWidth,
      screenHeight,
    )
    assertNull(region)
  }

  @Test
  fun `acha regiao numa oferta rejeitada por tarifa implausivel se tem keyword`() {
    // O caso que motivou a segunda passada: "R$ 1703" (17,03 sem o separador).
    val lines = listOf(
      OcrLine("R$ 1703", 100, 500, 300, 540),
      OcrLine("Selecionar", 100, 560, 300, 600),
    )
    val region = OfferRegionFinder.find(
      analysis(fare = "1703", isOffer = false, hasKeyword = true),
      lines,
      screenWidth,
      screenHeight,
    )
    assertNotNull(region)
    assertTrue(region!!.contains(OcrRect(100, 500, 300, 600)))
  }

  @Test
  fun `sem tarifa nao encontra regiao`() {
    val lines = listOf(OcrLine("3,4 km", 100, 600, 260, 640))
    assertNull(OfferRegionFinder.find(analysis(distance = "3,4"), lines, screenWidth, screenHeight))
  }

  @Test
  fun `sem linhas nao encontra regiao`() {
    assertNull(OfferRegionFinder.find(analysis(fare = "17,03"), emptyList(), screenWidth, screenHeight))
  }

  @Test
  fun `oferta sem string valida nas linhas nao encontra regiao`() {
    val lines = listOf(OcrLine("Boa viagem", 10, 10, 100, 50))
    assertNull(OfferRegionFinder.find(analysis(fare = "17,03"), lines, screenWidth, screenHeight))
  }

  @Test
  fun `acha a regiao em volta da tarifa`() {
    val lines = listOf(
      OcrLine("Uber", 40, 100, 160, 140),
      OcrLine("R$ 17,03", 100, 400, 300, 440),
    )
    val region = OfferRegionFinder.find(analysis(fare = "17,03"), lines, screenWidth, screenHeight)
    assertNotNull(region)
    assertEquals(OcrRect(left = 100 - hpad, top = 400 - vpad, right = 300 + hpad, bottom = 440 + vpad), region)
  }

  @Test
  fun `acha a regiao pela distancia quando a tarifa nao casa`() {
    val lines = listOf(OcrLine("3,4 km", 100, 600, 260, 640))
    val region = OfferRegionFinder.find(
      analysis(fare = "99,99", distance = "3,4"),
      lines,
      screenWidth,
      screenHeight,
    )
    assertNotNull(region)
    assertTrue(region!!.contains(OcrRect(100, 600, 260, 640)))
  }

  @Test
  fun `nao confunde 13,4 km com distancia 3,4`() {
    val lines = listOf(OcrLine("13,4 km", 100, 600, 260, 640))
    assertNull(
      OfferRegionFinder.find(analysis(fare = "99,99", distance = "3,4"), lines, screenWidth, screenHeight),
    )
  }

  @Test
  fun `acha a regiao pela duracao em minutos`() {
    val lines = listOf(OcrLine("17 min", 100, 700, 220, 740))
    val region = OfferRegionFinder.find(analysis(fare = "99,99", duration = "17"), lines, screenWidth, screenHeight)
    assertNotNull(region)
    assertTrue(region!!.contains(OcrRect(100, 700, 220, 740)))
  }

  @Test
  fun `duracao 17 nao casa com 1703 nem com 17h03`() {
    assertNull(
      OfferRegionFinder.find(
        analysis(fare = "99,99", duration = "17"),
        listOf(OcrLine("1703", 100, 700, 220, 740)),
        screenWidth,
        screenHeight,
      ),
    )
    assertNull(
      OfferRegionFinder.find(
        analysis(fare = "99,99", duration = "17"),
        listOf(OcrLine("17:03", 100, 700, 220, 740)),
        screenWidth,
        screenHeight,
      ),
    )
  }

  @Test
  fun `une varias linhas casadas`() {
    val lines = listOf(
      OcrLine("R$ 17,03", 100, 400, 300, 440),
      OcrLine("3,4 km · 17 min", 90, 460, 360, 500),
    )
    val region = OfferRegionFinder.find(
      analysis(fare = "17,03", distance = "3,4", duration = "17"),
      lines,
      screenWidth,
      screenHeight,
    )
    assertNotNull(region)
    // União começa em left=90 e termina em bottom=500 antes do padding.
    assertEquals(90 - hpad, region!!.left)
    assertEquals(500 + vpad, region.bottom)
  }

  @Test
  fun `a folga vertical cobre a altura de um cartao em paisagem`() {
    // Tela de 1920x1200 (paisagem): uma união de uma linha (40 px) precisa gerar
    // uma região alta o bastante para não cortar o "8 min"/"Selecionar" abaixo.
    val lines = listOf(OcrLine("R$ 11,02", 100, 700, 300, 740))
    val region = OfferRegionFinder.find(analysis(fare = "11,02"), lines, 1920, 1200)
    assertNotNull(region)
    // verticalPad = 1200 * 0.18 = 216.
    assertEquals(700 - 216, region!!.top)
    assertEquals(740 + 216, region.bottom)
  }

  @Test
  fun `limita a regiao aos limites da tela`() {
    val lines = listOf(OcrLine("R$ 17,03", 1150, 1900, 1190, 1915))
    val region = OfferRegionFinder.find(analysis(fare = "17,03"), lines, screenWidth, screenHeight)
    assertNotNull(region)
    assertTrue(region!!.right <= screenWidth)
    assertTrue(region.bottom <= screenHeight)
    assertTrue(region.left >= 0)
    assertTrue(region.top >= 0)
  }

  @Test
  fun `ignora linhas em branco`() {
    val lines = listOf(
      OcrLine("   ", 100, 400, 300, 440),
      OcrLine("R$ 17,03", 100, 500, 300, 540),
    )
    assertNotNull(OfferRegionFinder.find(analysis(fare = "17,03"), lines, screenWidth, screenHeight))
  }
}