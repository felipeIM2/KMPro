package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideCalcTest {
  private val empty = DriverSettings()

  @Test
  fun `green when profit positive and both goals met`() {
    val analysis = RideCalc.calculate(
      fare = 20.0,
      distanceKm = 5.0,
      durationMinutes = 20.0,
      settings = DriverSettings(costPerKm = 1.5, goalPerKm = 3.0, goalPerHour = 40.0),
    )!!
    assertEquals(12.5, analysis.netProfit, 0.001)
    assertEquals(4.0, analysis.gainPerKm, 0.001)
    assertEquals(60.0, analysis.gainPerHour, 0.001)
    assertEquals(37.5, analysis.netPerHour, 0.001)
    assertEquals(RideCalc.GREEN, analysis.classification)
  }

  @Test
  fun `yellow when profit positive but a goal is missed`() {
    val analysis = RideCalc.calculate(
      fare = 10.0,
      distanceKm = 5.0,
      durationMinutes = 20.0,
      settings = DriverSettings(costPerKm = 1.0, goalPerKm = 3.0, goalPerHour = 40.0),
    )!!
    assertEquals(5.0, analysis.netProfit, 0.001)
    assertEquals(RideCalc.YELLOW, analysis.classification)
  }

  @Test
  fun `red when profit is not positive`() {
    val analysis = RideCalc.calculate(
      fare = 5.0,
      distanceKm = 5.0,
      durationMinutes = 20.0,
      settings = DriverSettings(costPerKm = 1.5, goalPerKm = 0.0, goalPerHour = 0.0),
    )!!
    assertEquals(-2.5, analysis.netProfit, 0.001)
    assertEquals(RideCalc.RED, analysis.classification)
  }

  @Test
  fun `red when gain per km below half the goal`() {
    val analysis = RideCalc.calculate(
      fare = 10.0,
      distanceKm = 5.0,
      durationMinutes = 20.0,
      settings = DriverSettings(costPerKm = 0.5, goalPerKm = 5.0, goalPerHour = 0.0),
    )!!
    assertEquals(RideCalc.RED, analysis.classification)
  }

  @Test
  fun `null when fare is missing or invalid`() {
    assertNull(RideCalc.calculate(null, 5.0, 20.0, empty))
    assertNull(RideCalc.calculate(0.0, 5.0, 20.0, empty))
  }

  @Test
  fun `works with no goals configured`() {
    val analysis = RideCalc.calculate(
      fare = 15.0,
      distanceKm = 4.0,
      durationMinutes = 15.0,
      settings = DriverSettings(costPerKm = 1.0),
    )!!
    assertEquals(RideCalc.YELLOW, analysis.classification)
    assertEquals(11.0, analysis.netProfit, 0.001)
  }
}