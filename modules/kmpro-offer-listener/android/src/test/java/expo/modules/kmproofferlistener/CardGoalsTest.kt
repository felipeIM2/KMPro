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
    custoHora = 20.0,
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

  @Test
  fun `lucro per hour uses the hourly cost as target`() {
    // Alvo 20/h: verde em 20+, amarelo entre 18 e 20, vermelho abaixo de 18.
    assertEquals(RideCalc.GREEN, goals.toneFor("lucroHora", 20.0))
    assertEquals(RideCalc.GREEN, goals.toneFor("lucroHora", 25.0))
    assertEquals(RideCalc.YELLOW, goals.toneFor("lucroHora", 19.0))
    assertEquals(RideCalc.RED, goals.toneFor("lucroHora", 17.9))
  }

  @Test
  fun `lucro per trip scales the hourly target by the offer duration`() {
    // 30 min = meia hora: alvo 10, amarelo entre 9 e 10, vermelho abaixo de 9.
    assertEquals(RideCalc.GREEN, goals.toneForLucro(10.0, 30.0))
    assertEquals(RideCalc.YELLOW, goals.toneForLucro(9.5, 30.0))
    assertEquals(RideCalc.RED, goals.toneForLucro(8.9, 30.0))
    // 60 min = uma hora: alvo 20.
    assertEquals(RideCalc.GREEN, goals.toneForLucro(20.0, 60.0))
    assertEquals(RideCalc.YELLOW, goals.toneForLucro(19.0, 60.0))
  }

  @Test
  fun `lucro without a duration or with no hourly cost is neutral`() {
    assertNull(goals.toneForLucro(10.0, null))
    assertNull(goals.toneForLucro(10.0, 0.0))
    assertNull(CardGoals(custoHora = 0.0).toneForLucro(10.0, 30.0))
  }

  @Test
  fun `worst tone keeps the most severe and ignores nulls`() {
    assertEquals(
      RideCalc.RED,
      CardGoals.worst(listOf(RideCalc.GREEN, RideCalc.RED, RideCalc.YELLOW)),
    )
    assertEquals(
      RideCalc.YELLOW,
      CardGoals.worst(listOf(null, RideCalc.GREEN, RideCalc.YELLOW)),
    )
    assertEquals(RideCalc.GREEN, CardGoals.worst(listOf(null, RideCalc.GREEN)))
    assertNull(CardGoals.worst(listOf(null, null)))
  }
}