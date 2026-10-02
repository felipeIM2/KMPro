package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the app identity inferred from OCR output.
 *
 * The lines below are verbatim ML Kit output captured from a real 99 driver
 * screen via AccessibilityService.takeScreenshot() on a Samsung Tab S9 FE. The
 * brand glyph in the header is the trap: ML Kit reads the 99 logo as "9l$90" or
 * "9l90", so matching the clean word "99pop" never fires and every real screen
 * was classified as unknown, which made the whole OCR path return early.
 */
class WatchedPackageFromOcrTest {
  /** 99 home screen, offline. The header logo OCRs as "9l$90". */
  private val home99 = listOf(
    "22:45 qua., 30 de set. → 99",
    "Você está offline",
    "Tudo pronto?",
    "Santa Leopoldina",
    "Cariacica",
    "Programar lembretes",
    "Missão",
    "Serra",
    "Página inicial",
    "Conclua 40 viagens e ganhe R$ 44 a mais",
    "1a 1-3 min",
    "Descubra",
    "+R$ 13",
    "Aeroporto Eurico",
    "Ficar online",
    "Ganhos",
    "Caixa de entrada",
    "99",
    "Menu",
    "9l$90",
  )

  /** 99 screen with the logo read as "9l90" instead of "9l$90". */
  private val home99Alt = listOf(
    "22:45 qua., 30 de set. >u 99",
    "1-3 min",
    "R$ 0,00",
    "Você está online",
    "Procurando viagens",
    "Ganhos",
    "9l90",
  )

  /** Uber driver home screen, real OCR output. */
  private val homeUber = listOf(
    "Cariacica",
    "Nova Vila Velha",
    "Tudo pronto?",
    "Você está online",
    "Aceitar",
  )

  @Test
  fun `99 home screen is recognised despite the garbled logo`() {
    assertEquals(
      "com.app99.driver",
      WatchedApp.fromOcrLines(home99),
    )
  }

  @Test
  fun `99 logo read as 9l90 is still recognised`() {
    assertEquals(
      "com.app99.driver",
      WatchedApp.fromOcrLines(home99Alt),
    )
  }

  @Test
  fun `uber home screen is recognised`() {
    assertEquals(
      "com.ubercab.driver",
      WatchedApp.fromOcrLines(homeUber),
    )
  }

  @Test
  fun `uber home screen survives a lost accent`() {
    // ML Kit drops the cedilla/tilde on small text: "voce esta online".
    assertEquals(
      "com.ubercab.driver",
      WatchedApp.fromOcrLines(listOf("voce esta online", "Tudo pronto?")),
    )
  }

  @Test
  fun `an unrelated screen yields no package`() {
    assertNull(WatchedApp.fromOcrLines(listOf("Calculadora", "7 x 8 =", "56")))
  }

  @Test
  fun `a bare launcher screen yields no package`() {
    // Unrelated app labels can appear in the recents list without any ride app open.
    assertNull(
      WatchedApp.fromOcrLines(listOf("Fotos", "99", "Calculadora")),
    )
  }

  @Test
  fun `uber radar is not mistaken for 99 by a distance read as 9l`() {
    // Regression, seen live: the Uber Radar screen OCRs "13 minutos (9.3 km)" as
    // "9l", and the bare contains("9l") match filed the whole screen under 99
    // while Uber was the app in the foreground.
    assertEquals(
      "com.ubercab.driver",
      WatchedApp.fromOcrLines(
        listOf("Radar de Viagens", "Procurando viagens", "13 minutos (9l km)", "UberX"),
      ),
    )
  }

  @Test
  fun `a 99 promo banner alone does not claim the screen for 99`() {
    // Uber's Radar carries the 99 R$100 banner. With Uber's own labels present
    // the screen must stay Uber's.
    assertEquals(
      "com.ubercab.driver",
      WatchedApp.fromOcrLines(
        listOf("Radar de Viagens", "Procurando viagens", "9l100", "UberX"),
      ),
    )
  }

  @Test
  fun `the 99 logo still wins when the logo itself is on screen`() {
    // The anchored match is not a loosening: "9l100" is the 99 logo plus its
    // value, and with no Uber marker present 99 must still win.
    assertEquals(
      "com.app99.driver",
      WatchedApp.fromOcrLines(listOf("9l100", "0/500 pontos", "Procurando")),
    )
  }
}