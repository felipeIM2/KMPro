package expo.modules.kmproofferlistener

/**
 * Which ride app a screenshot belongs to, inferred from the screen's own text.
 *
 * OCR returns pixels-to-text, not a package name, so the identity has to come
 * from the labels on screen. This is its own object rather than a method on the
 * accessibility service for two reasons: it is pure and unit-testable, and the
 * bug it had was invisible without tests.
 *
 * The failure it had: ML Kit reads the 99 logo in the app header as "9l$90" or
 * "9l90" - the round mark becomes a lowercase L and the shape beside it becomes
 * a dollar sign. Matching the clean word "99pop" therefore never fired on a
 * real screen, every 99 screen came back as unknown, and [RideAccessibilityService]
 * discarded the OCR result before the parser ever saw it. That is why the app
 * looked fast and correct on Uber and silent on 99.
 */
object WatchedApp {
  private const val UBER = "com.ubercab.driver"
  private const val POP99 = "com.app99.driver"

  fun fromOcrLines(lines: List<String>): String? {
    val blob = lines.joinToString(" ").lowercase()

    // The 99 brand, in every spelling ML Kit produces for its header logo.
    val is99 = blob.contains("99pop") ||
      blob.contains("nove e nove") ||
      blob.contains("99g") ||
      blob.contains("99l") ||
      blob.contains("9l")
    if (is99) return POP99

    if (blob.contains("uber") || blob.contains("übe")) return UBER

    // Uber's driver home screen markers. Checked last on purpose: on 99 the brand
    // match above already returned, so reaching here means the screen is Uber's
    // and these are the only labels left to go on.
    val looksLikeRideHome = blob.contains("tudo pronto") ||
      blob.contains("você está online") ||
      blob.contains("voce esta online") ||
      blob.contains("você está offline") ||
      blob.contains("voce esta offline") ||
      blob.contains("ficar online") ||
      blob.contains("aceitar corrida") ||
      blob.contains("procurando") ||
      blob.contains("caixa de entrada")
    return if (looksLikeRideHome) UBER else null
  }
}