package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardGoalsTest {
  private val goals = CardGoals(
    gainKmMin = 1.5,
    gainKmMax = 2.0,
    gainHourMin = 30.0,
    gainHourMax = 50.0,
    ratingMin = 4.85,
  )

  @Test
  fun `below the minimum is red`() {
    assertEquals(RideCalc.RED, goals.toneFor("ganhoKm", 1.4))
    assertEquals(RideCalc.RED, goals.toneFor("ganhoHora", 29.99))
    assertEquals(RideCalc.RED, goals.toneFor("rating", 4.0))
  }

  @Test
  fun `between the minimum and maximum is yellow`() {
    assertEquals(RideCalc.YELLOW, goals.toneFor("ganhoKm", 1.75))
    assertEquals(RideCalc.YELLOW, goals.toneFor("ganhoHora", 40.0))
  }

  @Test
  fun `at or above the maximum is green`() {
    assertEquals(RideCalc.GREEN, goals.toneFor("ganhoKm", 2.0))
    assertEquals(RideCalc.GREEN, goals.toneFor("ganhoKm", 3.1))
    assertEquals(RideCalc.GREEN, goals.toneFor("ganhoHora", 50.0))
    assertEquals(RideCalc.GREEN, goals.toneFor("ganhoHora", 55.0))
    // rating só tem mínimo: no mínimo é verde.
    assertEquals(RideCalc.GREEN, goals.toneFor("rating", 4.85))
    assertEquals(RideCalc.GREEN, goals.toneFor("rating", 5.0))
  }

  @Test
  fun `unconfigured metrics are neutral`() {
    val neutral = CardGoals()
    assertNull(neutral.toneFor("ganhoKm", 1.0))
    assertNull(neutral.toneFor("ganhoHora", 0.0))
    assertNull(neutral.toneFor("rating", 4.0))
    assertNull(neutral.toneFor("lucro", 5.0))
    assertNull(neutral.toneFor("desconhecido", 5.0))
  }

  @Test
  fun `null value is always neutral`() {
    assertNull(goals.toneFor("ganhoKm", null))
    assertNull(goals.toneFor("rating", null))
  }

  @Test
  fun `boundary of the minimum is green`() {
    // 1.5 com mínima em 1.5: não é menor que o mínimo e não tem max (rating).
    assertEquals(RideCalc.GREEN, goals.toneFor("rating", 4.85))
  }
}