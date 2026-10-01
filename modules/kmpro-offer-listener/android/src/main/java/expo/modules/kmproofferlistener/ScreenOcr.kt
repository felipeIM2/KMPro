package expo.modules.kmproofferlistener

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * Nothing leaves the device: the recognition model is bundled in the APK and
 * runs fully offline.
 */
class ScreenOcr(private val service: AccessibilityService) {
  private val TAG = "KMProOcr"

  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val inFlight = AtomicBoolean(false)

  /** Cached result so a debounce that lands right after a scan can reuse it. */
  private var lastText: List<String> = emptyList()
  private var lastTextAt = 0L

  /**
   * Captures the screen and returns the recognized text lines, or null when a
   * capture is unavailable right now (unsupported API, no window, already busy).
   * Results are delivered asynchronously; the callback runs on a worker thread.
   */
  fun capture(onResult: (List<String>) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      Log.w(TAG, "[KMPro][Ocr] takeScreenshot requer API 30+, atual=${Build.VERSION.SDK_INT}")
      onResult(emptyList())
      return
    }
    if (!inFlight.compareAndSet(false, true)) {
      Log.d(TAG, "[KMPro][Ocr] captura ja em andamento, ignorando")
      onResult(emptyList())
      return
    }
    // takeScreenshot is asynchronous: it returns immediately and the callback
    // runs later on the given executor. The in-flight guard must therefore be
    // released from inside the callback, not after this call.
    val settled = AtomicBoolean(false)
    fun finish(lines: List<String>) {
      if (settled.compareAndSet(false, true)) onResult(lines)
      inFlight.set(false)
    }
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
              runCatching { screenshot.hardwareBuffer?.close() }
              finish(emptyList())
              return
            }
            Log.d(TAG, "[KMPro][Ocr] bitmap ${bitmap.width}x${bitmap.height}")
            recognize(bitmap) { lines -> finish(lines) }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(TAG, "[KMPro][Ocr] onFailure codigo=$errorCode")
            finish(emptyList())
          }
        },
      )
      Log.d(TAG, "[KMPro][Ocr] takeScreenshot retornou sem erro imediato")
    }.onFailure { e ->
      Log.w(TAG, "[KMPro][Ocr] takeScreenshot lancou excecao: ${e.message}")
      finish(emptyList())
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

  /**
   * The offer card position varies between the driver apps and device sizes, and
   * cropping to a fixed region risks cutting the very lines we need, so the
   * full screen is recognized.
   */
  private fun cropTop(bitmap: Bitmap): Bitmap = bitmap

  private fun recognize(bitmap: Bitmap, onResult: (List<String>) -> Unit) {
    val cropped = cropTop(bitmap)
    val image = InputImage.fromBitmap(cropped, 0)
    recognizer.process(image)
      .addOnSuccessListener { result ->
        val lines = result.textBlocks
          .flatMap { block -> block.lines }
          .map { it.text.trim() }
          .filter { it.isNotBlank() }
          .distinct()
        lastText = lines
        lastTextAt = System.currentTimeMillis()
        Log.d(
          TAG,
          "[KMPro][Ocr] ${lines.size} linhas: [${lines.take(30).joinToString(" ;; ") { it.take(80) }}]",
        )
        onResult(lines)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "[KMPro][Ocr] falha ao reconhecer texto: ${e.message}")
        onResult(emptyList())
        runCatching { bitmap.recycle() }
      }
  }

  fun release() {
    runCatching { recognizer.close() }
    runCatching { executor.shutdownNow() }
  }

}
