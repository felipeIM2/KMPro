package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAppearanceTest {
  @Test
  fun `default order matches the app metrics`() {
    val appearance = CardAppearance.DEFAULT
    assertEquals(
      listOf("ganhoKm", "lucro", "ganhoHora", "lucroHora"),
      appearance.metricOrder,
    )
    assertEquals("centro", appearance.cardPosition)
  }

  @Test
  fun `every default metric has a label used by the overlay`() {
    // The overlay renders one labelled row per configured metric; an unknown id
    // would silently render a blank row.
    val known = setOf("ganhoKm", "lucro", "ganhoHora", "lucroHora")
    assertEquals(known, CardAppearance.DEFAULT_METRIC_ORDER.toSet())
  }

  @Test
  fun `accepts the positions offered in settings`() {
    assertTrue(CardAppearance.VALID_POSITIONS.containsAll(listOf("esquerda", "centro", "direita")))
  }
}
