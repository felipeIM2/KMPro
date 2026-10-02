package expo.modules.kmproofferlistener

/**
 * Geometria do OCR em coordenadas de tela.
 *
 * O ML Kit devolve, para cada linha reconhecida, um retângulo em coordenadas da
 * imagem. Até agora o [ScreenOcr] descartava esse retângulo e guardava só o
 * texto — era o que impedia recortar a região do cartão de oferta e reprocessá-la
 * com mais acurácia. Estes dois tipos são Kotlin puro (nada de
 * `android.graphics.Rect`) porque o módulo roda os testes unitários com
 * `returnDefaultValues = true`, e qualquer coisa que dependa do framework
 * voltaria zerada.
 */
data class OcrRect(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
  val centerY: Int get() = top + height / 2

  fun union(other: OcrRect): OcrRect = OcrRect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom),
  )

  /**
   * Cresce em volta, mas nunca além de [maxLeft]/[maxTop]/[maxRight]/[maxBottom]
   * — sem isso o recorte sairia dos limites do bitmap e o `Bitmap.createBitmap`
   * lançaria.
   */
  fun expand(
    horizontal: Int,
    top: Int,
    bottom: Int,
    maxLeft: Int,
    maxTop: Int,
    maxRight: Int,
    maxBottom: Int,
  ): OcrRect = OcrRect(
    left = (this.left - horizontal).coerceAtLeast(maxLeft),
    top = (this.top - top).coerceAtLeast(maxTop),
    right = (this.right + horizontal).coerceAtMost(maxRight),
    bottom = (this.bottom + bottom).coerceAtMost(maxBottom),
  )

  fun intersects(other: OcrRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

  fun contains(other: OcrRect): Boolean =
    other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

  fun isValid(): Boolean = right > left && bottom > top
}

/** Uma linha reconhecida, com o retângulo onde ela estava na imagem. */
data class OcrLine(
  val text: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val rect: OcrRect get() = OcrRect(left, top, right, bottom)
}