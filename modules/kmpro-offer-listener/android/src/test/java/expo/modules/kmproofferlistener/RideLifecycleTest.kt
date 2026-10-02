package expo.modules.kmproofferlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regressões do acionamento do cartão: a corrida precisa de um ciclo explícito
 * para o cartão aparecer, sumir e voltar a aparecer.
 */
class RideLifecycleTest {
  private val uber = "com.ubercab.driver"
  private val n99 = "com.app99.driver"
  private val ride = "17.03|3.4|17"

  @Before
  fun setUp() = RideLifecycle.clear()

  /** Drives the ride to the confirmed-gone point and asserts it happened once. */
  private fun endRide(pkg: String) {
    repeat(RideLifecycle.PRESENCE_PIN) {
      assertFalse("absent during the presence pin must be ignored", RideLifecycle.observeAbsent(pkg))
    }
    repeat(RideLifecycle.GONE_CONFIRMATIONS - 1) {
      assertFalse("a single absent read must not end the ride", RideLifecycle.observeAbsent(pkg))
    }
    assertTrue("the ride must be declared gone", RideLifecycle.observeAbsent(pkg))
  }

  @Test
  fun `first appearance opens a cycle`() {
    assertTrue(RideLifecycle.observePresent(uber))
  }

  @Test
  fun `staying on screen does not reopen the cycle`() {
    RideLifecycle.observePresent(uber)
    repeat(10) { assertFalse(RideLifecycle.observePresent(uber)) }
    assertTrue(RideLifecycle.isOnScreen(uber))
  }

  @Test
  fun `the signature is only remembered within the cycle`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    assertEquals(ride, RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `one missing capture does not end the ride`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    // The offer panel is not in the accessibility tree, so a single blank read is
    // normal noise, not the end of the ride.
    assertFalse(RideLifecycle.observeAbsent(uber))
    assertTrue(RideLifecycle.isOnScreen(uber))
    assertEquals("the card state must survive a single miss", ride, RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `a presence pins the cycle against the faster tree scan`() {
    // The tree scans every ~750 ms and the OCR every 1200 ms+. Right after the OCR
    // confirms the ride, the tree can return two normal-text frames and would
    // close the cycle on its own, dropping the dedup and making the same ride
    // show twice.
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    repeat(RideLifecycle.PRESENCE_PIN) {
      assertFalse(RideLifecycle.observeAbsent(uber))
    }
    assertTrue("the cycle must still be open during the pin", RideLifecycle.isOnScreen(uber))
    assertEquals(ride, RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `the ride ends after the pin plus the confirmations and frees the signature`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    endRide(uber)
    assertFalse(RideLifecycle.isOnScreen(uber))
    // This is the bug in the field: the signature outlived the ride, so the next
    // ride was always a "repeat" and the card never came back.
    assertNull(RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `the same values show again when the ride comes back`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    endRide(uber)

    // Back on screen with the exact same numbers: a new cycle, so the card must
    // be allowed to show again.
    assertTrue(RideLifecycle.observePresent(uber))
    assertNull(RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `a ride that never left keeps its card suppressed`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    // The card hid on the screen timer, but the ride is still there: re-showing
    // would ignore the "Tempo de tela" the user configured. Re-reading the same
    // ride keeps pinning the cycle open.
    repeat(20) {
      assertFalse(RideLifecycle.observeAbsent(uber))
      assertFalse(RideLifecycle.observePresent(uber))
    }
    assertEquals(ride, RideLifecycle.shownSignature(uber))
  }

  @Test
  fun `each app keeps its own cycle`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    RideLifecycle.observePresent(n99)
    RideLifecycle.markShown(n99, "48.03|34.9|68")

    endRide(uber)

    assertFalse(RideLifecycle.isOnScreen(uber))
    assertTrue("the 99 ride must survive the Uber one ending", RideLifecycle.isOnScreen(n99))
    assertEquals("48.03|34.9|68", RideLifecycle.shownSignature(n99))
  }

  @Test
  fun `forget drops the whole state of a package`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.markShown(uber, ride)
    RideLifecycle.forget(uber)
    assertNull(RideLifecycle.shownSignature(uber))
    assertFalse(RideLifecycle.isOnScreen(uber))
    assertEquals("nenhum", RideLifecycle.describe(uber))
  }

  @Test
  fun `a blank package name is ignored`() {
    assertFalse(RideLifecycle.observePresent(""))
    assertFalse(RideLifecycle.observeAbsent(""))
    assertFalse(RideLifecycle.isOnScreen(""))
    RideLifecycle.markShown("", ride)
    assertNull(RideLifecycle.shownSignature(""))
  }

  @Test
  fun `clear resets every app`() {
    RideLifecycle.observePresent(uber)
    RideLifecycle.observePresent(n99)
    RideLifecycle.clear()
    assertFalse(RideLifecycle.isOnScreen(uber))
    assertFalse(RideLifecycle.isOnScreen(n99))
  }

  @Test
  fun `a present read cancels a pending ending`() {
    RideLifecycle.observePresent(uber)
    assertFalse(RideLifecycle.observeAbsent(uber))
    // Back before the confirmation threshold: the ride never ended.
    assertFalse(RideLifecycle.observePresent(uber))
    assertTrue(RideLifecycle.isOnScreen(uber))
  }
}