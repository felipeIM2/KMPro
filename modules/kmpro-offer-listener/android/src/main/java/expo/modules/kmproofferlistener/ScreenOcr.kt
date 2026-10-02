package expo.modules.kmproofferlistener

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the offer card from screen pixels.
 *
 * The driver apps (Uber Driver / 99) render the offer panel without publishing
 * it through the accessibility tree — a walk of their window returns a single
 * empty ImageView. On-device OCR is the only way to get the fare, distance and
 * duration, so we screenshot through [AccessibilityService.takeScreenshot]
 * (no MediaProjection consent dialog, no "capturing screen" notification) and
 * run ML Kit's on-device text recognizer.
 *
 * Two passes:
 *  1. full screen, only to find the text and its rectangles;
 *  2. the region [OfferRegionFinder] derived from the validated offer, cropped
 *     (and upscaled when narrow) so the digits are read from the big card text
 *     with the rest of the UI out of the frame. The second pass is the
 *     authoritative read — "R$ 17,03" being read as "R$ 1703" is the class of
 *     error this is here to kill.
 *
 * Nothing leaves the device: the recognition model is bundled in the APK and
 * runs fully offline.
 */
class ScreenOcr(private val service: AccessibilityService) {
  private val TAG = "KMProOcr"

  // A leitura em si roda nas threads do ML Kit; os callbacks de resultado ficam
  // na main thread (executor padrão da Task), mas o serviço devolve o resultado
  // para o handler principal de qualquer forma. Não há executor próprio aqui.
  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
  private val inFlight = AtomicBoolean(false)

  /** Both OCR passes of one capture. */
  data class Read(
    val full: List<OcrLine>,
    val region: OcrRect?,
    val regionLines: List<OcrLine>,
  )

  companion object {
    /**
     * Abaixo disso o recorte é ampliado 2x antes de reconhecer. O ML Kit lê
     * números grandes com muito menos erro, e a região do cartão fica estreita
     * num aparelho de 1200 px de largura — o custo do segundo passe é baixo
     * justamente porque a imagem é pequena.
     */
    const val UPSCALE_WIDTH_THRESHOLD_PX = 600
    const val UPSCALE_FACTOR = 2f
  }

  /**
   * Captures the screen and returns both reads. [findRegion] receives the full
   * pass lines plus the bitmap size and returns the region to re-read, or null
   * when there is nothing to crop (no offer, or the card could not be located).
   * Results are delivered asynchronously; the callback runs on the executor
   * chosen by ML Kit.
   */
  fun capture(
    findRegion: (List<OcrLine>, Int, Int) -> OcrRect?,
    onResult: (Read) -> Unit,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      Log.w(TAG, "[KMPro][Ocr] takeScreenshot requer API 30+, atual=${Build.VERSION.SDK_INT}")
      onResult(Read(emptyList(), null, emptyList()))
      return
    }
    if (!inFlight.compareAndSet(false, true)) {
      Log.d(TAG, "[KMPro][Ocr] captura ja em andamento, ignorando")
      onResult(Read(emptyList(), null, emptyList()))
      return
    }
    // takeScreenshot is asynchronous: it returns immediately and the callback
    // runs later on the given executor. The in-flight guard must therefore be
    // released from inside the callback, not after this call.
    val settled = AtomicBoolean(false)
    fun finish(read: Read) {
      if (settled.compareAndSet(false, true)) onResult(read)
      inFlight.set(false)
    }
    val empty = Read(emptyList(), null, emptyList())
    runCatching {
      Log.d(TAG, "[KMPro][Ocr] chamando takeScreenshot (sdk=${Build.VERSION.SDK_INT})")
      service.takeScreenshot(
        android.view.Display.DEFAULT_DISPLAY,
        service.mainExecutor,
        object : AccessibilityService.TakeScreenshotCallback {
          override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            Log.d(TAG, "[KMPro][Ocr] onSuccess recebido")
            val bitmap = runCatching { screenshot.toBitmap() }.getOrNull()
            if (bitmap == null) {
              Log.w(TAG, "[KMPro][Ocr] nao foi possivel converter o screenshot em bitmap")
              finish(empty)
              return
            }
            Log.d(TAG, "[KMPro][Ocr] bitmap ${bitmap.width}x${bitmap.height}")
            val width = bitmap.width
            val height = bitmap.height
            recognizeFull(bitmap) { full ->
              val region = runCatching { findRegion(full, width, height) }.getOrNull()
                ?.takeIf { it.isValid() && it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height }
              if (region == null) {
                bitmap.recycle()
                finish(Read(full, null, emptyList()))
                return@recognizeFull
              }
              Log.d(
                TAG,
                "[KMPro][Ocr] regiao do cartao ${region.left},${region.top},${region.right},${region.bottom} " +
                  "(${region.width}x${region.height})",
              )
              recognizeRegion(bitmap, region) { regionLines ->
                bitmap.recycle()
                finish(Read(full, region, regionLines))
              }
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(TAG, "[KMPro][Ocr] onFailure codigo=$errorCode")
            finish(empty)
          }
        },
      )
      Log.d(TAG, "[KMPro][Ocr] takeScreenshot retornou sem erro imediato")
    }.onFailure { e ->
      Log.w(TAG, "[KMPro][Ocr] takeScreenshot lancou excecao: ${e.message}")
      finish(empty)
    }
  }

  /**
   * The a11y screenshot API hands back a HardwareBuffer, not a Bitmap, so it has
   * to be wrapped and copied into a software bitmap before ML Kit can read it.
   * The buffer is closed here because the caller no longer needs it.
   */
  private fun AccessibilityService.ScreenshotResult.toBitmap(): Bitmap? {
    val buffer = hardwareBuffer ?: return null
    return try {
      val wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
      // wrapHardwareBuffer returns an immutable hardware-backed bitmap; OCR needs
      // a readable software copy.
      val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
      if (wrapped != software) wrapped.recycle()
      software
    } finally {
      runCatching { buffer.close() }
    }
  }

  /** Full-screen pass. Does not recycle [bitmap]: the region pass still needs it. */
  private fun recognizeFull(bitmap: Bitmap, onResult: (List<OcrLine>) -> Unit) {
    runRecognition(bitmap, onResult)
  }

  /**
   * Crops [region] out of [bitmap], upscales it when narrow and recognizes it.
   * The rectangles come back translated to screen coordinates. Does not recycle
   * [bitmap]; recycles only its own crop.
   */
  private fun recognizeRegion(bitmap: Bitmap, region: OcrRect, onResult: (List<OcrLine>) -> Unit) {
    val crop = runCatching {
      Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height)
    }.getOrNull()
    if (crop == null) {
      Log.w(TAG, "[KMPro][Ocr] nao foi possivel recortar a regiao $region")
      onResult(emptyList())
      return
    }
    val scale = if (crop.width < UPSCALE_WIDTH_THRESHOLD_PX) UPSCALE_FACTOR else 1f
    val input = if (scale > 1f) {
      runCatching {
        Bitmap.createScaledBitmap(
          crop,
          (crop.width * scale).toInt(),
          (crop.height * scale).toInt(),
          true,
        )
      }.getOrNull()
    } else {
      crop
    }
    if (input == null) {
      crop.recycle()
      onResult(emptyList())
      return
    }
    if (input !== crop) crop.recycle()
    val startedAt = System.currentTimeMillis()
    runRecognition(input) { lines ->
      Log.d(
        TAG,
        "[KMPro][Ocr] 2a passada ${System.currentTimeMillis() - startedAt}ms " +
          "scale=$scale linhas=${lines.size}",
      )
      input.recycle()
      onResult(translate(lines, region.left, region.top, scale))
    }
  }

  private fun runRecognition(bitmap: Bitmap, onResult: (List<OcrLine>) -> Unit) {
    val image = InputImage.fromBitmap(bitmap, 0)
    recognizer.process(image)
      .addOnSuccessListener { result ->
        val lines = result.textBlocks
          .flatMap { block -> block.lines }
          .mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(
              text = line.text.trim(),
              left = box.left,
              top = box.top,
              right = box.right,
              bottom = box.bottom,
            )
          }
          .filter { it.text.isNotBlank() }
        Log.d(
          TAG,
          "[KMPro][Ocr] ${lines.size} linhas: [${lines.take(30).joinToString(" ;; ") { it.text.take(80) }}]",
        )
        onResult(lines)
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "[KMPro][Ocr] falha ao reconhecer texto: ${e.message}")
        onResult(emptyList())
      }
  }

  /** Converte os retângulos do recorte ampliado de volta para a tela. */
  private fun translate(lines: List<OcrLine>, offsetX: Int, offsetY: Int, scale: Float): List<OcrLine> =
    lines.map { line ->
      OcrLine(
        text = line.text,
        left = offsetX + (line.left / scale).toInt(),
        top = offsetY + (line.top / scale).toInt(),
        right = offsetX + (line.right / scale).toInt(),
        bottom = offsetY + (line.bottom / scale).toInt(),
      )
    }

  fun release() {
    runCatching { recognizer.close() }
  }
}