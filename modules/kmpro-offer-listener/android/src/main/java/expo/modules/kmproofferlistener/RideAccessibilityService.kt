package expo.modules.kmproofferlistener

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Captures ride offers from the Uber/99 driver apps through the accessibility
 * tree and forwards structured offers to the JS side via [OfferManager].
 *
 * Data flow:
 *   Uber screen
 *     -> AccessibilityEvent (window state / content changed)
 *     -> rootInActiveWindow
 *     -> recursive tree walk (text + contentDescription)
 *     -> normalized full text
 *     -> OfferParser.analyze (candidate -> validated)
 *     -> OfferManager.addOffer (dedup) -> expo event -> JS Copiloto
 *
 * Collection strategy (the offer window must not be missed): a window switch
 * (TYPE_WINDOW_STATE_CHANGED) is scanned immediately; a content change is
 * debounced so hundreds of successive events from the same screen don't trigger
 * hundreds of scans. Repeated scans of the same screen are deduplicated by the
 * offer signature (fare|distance|duration).
 */
class RideAccessibilityService : AccessibilityService() {
  private val TAG = "KMProOffer"

  /** Only the window state event is scanned immediately (rare, one per screen). */
  private val CONTENT_DEBOUNCE_MS = 400L

  /**
   * A periodic scan guarantees short-lived offer overlays (a few seconds on
   * screen) are never missed because CONTENT events don't always fire for them.
   * The scan itself dedups unchanged screens before any analysis.
   */
  private val POLL_INTERVAL_MS = 750L

  private val handler = Handler(Looper.getMainLooper())
  private var lastScanAt = 0L
  private var lastScreenSignature = ""

  /**
   * Última assinatura de oferta POR PACOTE. Em tela dividida com Uber e 99
   * oferecendo ao mesmo tempo, cada app precisa do seu próprio dedup: uma assinatura
   * global faria as duas ofertas se alternarem como "novas" a cada varredura.
   */
  private val lastOfferSignatureByPkg = mutableMapOf<String, String>()

  private val scanRunnable = Runnable { scanActiveWindow("CONTENT_DEBOUNCE") }
  private val scanFastRunnable = Runnable { scanActiveWindow("CONTENT_FAST") }

  /**
   * OCR fallback. The driver apps don't publish the offer card in the
   * accessibility tree, so when a watched window is present but silent we read
   * the pixels instead. Throttled: OCR is expensive and the offer card can stay
   * on screen for several seconds.
   */
  private val OCR_MIN_INTERVAL_MS = 1200L

  /**
   * Two cooldowns, not one. The global one keeps the offer card from being
   * re-read several times a second while it stays on screen. The muted one is
   * what actually governs latency: it is how long a *suspicion* of a new screen
   * has to sit unconfirmed. Measured on a real 99 session, a change in the
   * window tree raised the suspicion and the capture that confirmed it was
   * refused by the global cooldown, so the next read was up to 1.2 s later.
   */
  private val OCR_MUTED_MIN_INTERVAL_MS = 300L
  private var lastOcrAt = 0L
  private var lastMutedOcrAt = 0L
  private val ocrRunnable = Runnable { captureByOcr("MUTED") }
  private var ocr: ScreenOcr? = null

  private fun captureByOcr(reason: String) {
    val now = System.currentTimeMillis()
    val interval = if (reason == "MUTED") OCR_MUTED_MIN_INTERVAL_MS else OCR_MIN_INTERVAL_MS
    val last = if (reason == "MUTED") lastMutedOcrAt else lastOcrAt
    if (now - last < interval) {
      Log.d(TAG, "[KMPro][Ocr] cooldown, ignorando reason=$reason")
      return
    }
    if (OfferOverlay.isVisible()) {
      Log.d(TAG, "[KMPro][Ocr] cartão visível, OCR pausado reason=$reason")
      return
    }
    if (reason == "MUTED") lastMutedOcrAt = now else lastOcrAt = now
    Log.d(TAG, "[KMPro][Ocr] iniciando captura reason=$reason")
    val engine = ocr ?: ScreenOcr(this).also { ocr = it }
    engine.capture { lines ->
      handler.post {
        if (lines.isEmpty()) return@post
        Log.d(TAG, "[KMPro][Ocr] reason=$reason linhas=${lines.size}")
        handleOcrLines(lines)
      }
    }
  }
  private val pollRunnable = object : Runnable {
    override fun run() {
      runCatching {
        val hasWatched = windows.any { w ->
          w.root?.let {
            OfferManager.isWatched(
              this@RideAccessibilityService,
              it.packageName?.toString().orEmpty(),
            )
          } == true
        }
        if (hasWatched) {
          scanActiveWindow("POLL")
          // The offer card is rendered off the accessibility tree, so the tree
          // scan can never be the only source: keep a low-rate OCR sweep of the
          // watched screen running alongside it.
          captureByOcr("POLL")
        }
      }
      handler.postDelayed(this, POLL_INTERVAL_MS)
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    // Dedup: the system can rebind this service repeatedly (KNOX audit); make
    // sure a single connection does not stack duplicate polls.
    handler.removeCallbacksAndMessages(null)
    lastScanAt = 0L
    lastScreenSignature = ""
    lastOfferSignatureByPkg.clear()
    OfferManager.setAccessibilityConnected(this, true)
    if (ocr == null) {
      ocr = ScreenOcr(this)
      Log.d(TAG, "[KMPro][Ocr] motor de OCR pronto (on-device)")
    }
    // Capture the screen as soon as the service binds.
    handler.post { scanActiveWindow("SERVICE_CONNECTED") }
    // Reliable capture: keep sampling watched windows while the service is up.
    handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
  }

  override fun onUnbind(intent: Intent?): Boolean {
    Log.d(
      TAG,
      "[KMPro][Accessibility] SERVICE UNBOUND intent=${intent?.action} " +
        "trace=${Thread.currentThread().stackTrace.take(6).joinToString(" ~ ") { it.methodName }}",
    )
    handler.removeCallbacksAndMessages(null)
    handler.removeCallbacks(pollRunnable)
    lastOfferSignatureByPkg.clear()
    AppWindowBounds.clear()
    ocr?.release()
    ocr = null
    OfferManager.setAccessibilityConnected(this, false)
    return super.onUnbind(intent)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    event ?: return
    val packageName = event.packageName?.toString().orEmpty()
    Log.d(
      TAG,
      "[KMPro][Accessibility] package=$packageName event=${eventTypeName(event.eventType)}",
    )

    if (!OfferManager.isWatched(this, packageName)) return

    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        handler.removeCallbacks(scanRunnable)
        scanActiveWindow("WINDOW_STATE")
      }
      AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
        // Uber occasionally announces the new request (TalkBack live region);
        // those announcements may carry the plain-text offer.
        handler.removeCallbacks(scanRunnable)
        scanActiveWindow("ANNOUNCEMENT")
      }
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        // The first content event of a burst is the signal that the offer panel
        // is opening. Scan right away (the burst can last <2s), then re-scan
        // once more after the coalescing window to catch a partially-built tree.
        handler.removeCallbacks(scanFastRunnable)
        handler.post(scanFastRunnable)
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, CONTENT_DEBOUNCE_MS)
      }
    }
  }

  override fun onInterrupt() {
    // no-op: not used by this service yet.
  }

  private fun dumpWindows(tag: String) {
    // Cheap: no tree walk, just the window list metadata. Tells us WHERE the
    // offer panel lives when it appears (a separate interactive window with a
    // root != null is scannable; one with root == null is only reachable via a
    // different retrieval path).
    runCatching {
      windows.forEach { w ->
        Log.d(
          TAG,
          "[KMPro][Diag] $tag window id=${w.id} type=${w.type} pkg=${w.root?.packageName ?: "-"} root=${w.root != null} layer=${w.layer}",
        )
      }
    }
  }

  /**
   * Diagnostic deep dive while a watched window is muted: walk EVERY retrieved
   * window (any package, including ones we don't watch) and report the first
   * non-empty texts found. This locates the real offer text when our chosen
   * root turns out to be an empty ImageView.
   */
  private fun dumpAllWindowsText() {
    runCatching {
      val active = rootInActiveWindow
      val activePkg = active?.packageName?.toString() ?: "-"
      val activeTexts = active?.let { TreeDump.allText(it).distinct() }.orEmpty()
      if (activeTexts.isNotEmpty()) {
        Log.d(
          TAG,
          "[KMPro][Diag] DIVE activeWindow pkg=$activePkg texts=[${activeTexts.take(24).joinToString(" ;; ")}]",
        )
      }
      for (w in windows) {
        val root = w.root ?: continue
        val pkg = root.packageName?.toString() ?: "-"
        val texts = TreeDump.allText(root).distinct()
        if (texts.isNotEmpty()) {
          Log.d(
            TAG,
            "[KMPro][Diag] DIVE window id=${w.id} type=${w.type} pkg=$pkg texts=[${texts.take(24).joinToString(" ;; ")}]",
          )
        }
      }
    }
  }

  private fun scanActiveWindow(reason: String) {
    // The ride-app offer card is an INTERACTIVE overlay window (only visible to
    // clients with FLAG_RETRIEVE_INTERACTIVE_WINDOWS). rootInActiveWindow alone
    // may return the map behind it, so we walk every retrieved window and scan
    // the watched roots.
    val roots = linkedSetOf<AccessibilityNodeInfo>()
    val activeRoot = rootInActiveWindow
    if (activeRoot != null && OfferManager.isWatched(this, activeRoot.packageName?.toString().orEmpty())) {
      roots.add(activeRoot)
    }
    runCatching {
      for (w in windows) {
        val root = w.root ?: continue
        val pkg = root.packageName?.toString().orEmpty()
        if (OfferManager.isWatched(this, pkg)) roots.add(root)
      }
    }

    // Moldura na tela de cada app vigiado com janela visível: é o que permite
    // ancorar o cartão DENTRO do app de origem em tela dividida (a oferta da
    // Uber aparece sobre a Uber, a da 99 sobre a 99). Publicado a cada varredura
    // — rotação, redimensionamento e fechamento de app reancoram sozinhos.
    runCatching {
      val alive = mutableSetOf<String>()
      for (w in windows) {
        val root = w.root ?: continue
        val pkg = root.packageName?.toString().orEmpty()
        if (!OfferManager.isWatched(this, pkg)) continue
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) continue
        alive.add(pkg)
        AppWindowBounds.update(
          pkg,
          AppWindowBounds.Bounds(
            packageName = pkg,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
          ),
        )
      }
      AppWindowBounds.retain(alive)
    }
    // Reancora cartões já visíveis sem esperar a próxima oferta: se o usuário
    // abriu tela dividida (ou girou o tablet) com um cartão na tela, ele precisa
    // pular para dentro da janela do app imediatamente.
    if (AppWindowBounds.isNotEmpty()) {
      OfferOverlay.drainQueue(applicationContext)
    }

    if (roots.isEmpty()) {
      Log.d(TAG, "[KMPro][Accessibility] sem raiz vigiada reason=$reason")
      dumpWindows("sem_raiz")
      return
    }
    if (reason == "CONTENT_FAST" || reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED") {
      dumpWindows("scan")
    }

    val now = System.currentTimeMillis()
    val overallSig = roots
      .joinToString("~") { r ->
        val texts = TreeDump.allText(r).distinct()
        val muted = texts.isEmpty() && TreeDump.collect(r).isNotEmpty()
        r.packageName.toString() + "|" + texts.joinToString(" ") + if (muted) "|MUTED" else ""
      }
    // Skip identical repeated scans unless it's a window switch or announcement.
    if (reason != "WINDOW_STATE" && reason != "SERVICE_CONNECTED" && reason != "ANNOUNCEMENT" && reason != "BURST") {
      if (overallSig == lastScreenSignature) {
        Log.d(TAG, "[KMPro][Accessibility] mesmo texto, ignorado reason=$reason")
        return
      }
    }
    lastScreenSignature = overallSig
    lastScanAt = now

    /**
     * Uma oferta por app: em tela dividida com Uber e 99 oferecendo juntas, cada
     * janela vigiada é analisada e cada oferta válida é encaminhada — o cartão
     * de cada uma aparece sobre o próprio app. Sem `break`: parar na primeira
     * janela com oferta faria a segunda nunca ser capturada.
     */
    val offersByPkg = LinkedHashMap<String, Map<String, Any?>>()

    for (root in roots) {
      val rootPackage = root.packageName?.toString().orEmpty()
      val textLinesTotal = TreeDump.allText(root)
      var textLines = textLinesTotal.distinct()
      val nodes = TreeDump.collect(root)

      Log.d(
        TAG,
        "[KMPro][Accessibility] reason=$reason pkg=$rootPackage nodes=${nodes.size} texts=${textLines.size}",
      )
      if (textLines.isEmpty() && nodes.size in 1..250) {
        // Muted window: the offer panel is not exposed through the accessibility
        // tree at all (a walk returns a single empty ImageView), so text mining
        // on the same root is pointless. Screenshot the screen and read it with
        // OCR — the same approach the reference driver assistant uses.
        val sample = TreeDump.describe(root)
        Log.d(
          TAG,
          "[KMPro][Diagnostic] JANELA_MUDA_SUSPEITA nodes=${nodes.size} pkg=$rootPackage " +
            "-> OCR (a11y nao expoe o painel) sample=[${sample.joinToString(" ;; ")}]", 
        )
        handler.removeCallbacks(ocrRunnable)
        handler.post(ocrRunnable)
      } else if (textLines.isNotEmpty()) {
        // Normal screen returned: no more burst zoom.
        Log.d(
          TAG,
          "[KMPro][Accessibility] texts=[" +
            textLines.take(30).joinToString(" ;; ") { it.take(100) } +
            "]",
        )
      }

      val joined = textLines.joinToString(" | ")

      if (joined.isBlank()) continue

      val offer = OfferParser.extractOffer(
        key = "screen-$now",
        packageName = rootPackage,
        windowTexts = textLines,
        capturedAt = now,
      )
      if (offer != null) offersByPkg[rootPackage] = offer
    }

    if (offersByPkg.isEmpty()) {
      // The overlay card must leave when the offer is gone from a real screen.
      if (reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED" || reason == "POLL") {
        val firstRoot = roots.firstOrNull()
        val segs = firstRoot?.let { TreeDump.allText(it).distinct() }.orEmpty()
        // Only hide once a normal (non-silent) watched screen is back.
        if (segs.isNotEmpty()) {
          // Esconde apenas o cartão cuja oferta saiu da tela. Em tela dividida
          // com Uber e 99 abertos, uma tela normal da Uber não pode derrubar o
          // cartão da 99 (e vice-versa) — cada root vigiado mapeia para o seu
          // próprio pacote.
          for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotBlank()) OfferManager.hideOfferOverlayFor(pkg)
          }
          Log.d(TAG, "[KMPro][OfferParser] oferta encerrada, cartões dos apps sem oferta removidos")
        }
        val analysis = OfferParser.analyze(segs)
        if (reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED") {
          Log.d(TAG, "[KMPro][OfferParser] NOT AN OFFER reason=${analysis.rejectReason}")
          if (segs.isNotEmpty()) OfferManager.addScreenDebug(this, segs.joinToString(" "))
        }
      } else {
        Log.d(TAG, "[KMPro][OfferParser] content nao-oferta (não gravado)")
      }
      return
    }

    // Encaminha a oferta de CADA app capturado nesta varredura. A ordem de
    // inserção (LinkedHashMap) preserva quem apareceu primeiro — é essa ordem
    // que a fila do overlay segue quando não puder exibir ambos ao mesmo tempo.
    for ((pkg, offer) in offersByPkg) {
      val signature = OfferParser.offerSignature(offer)
      if (signature == lastOfferSignatureByPkg[pkg]) {
        Log.d(TAG, "[KMPro][OfferParser] oferta repetida de $pkg, ignorada")
        continue
      }
      lastOfferSignatureByPkg[pkg] = signature
      val fare = offer["fare"]?.toString() ?: "?"
      val distance = offer["distance"]?.toString() ?: "?"
      val duration = offer["durationMinutes"]?.toString() ?: "?"
      Log.d(
        TAG,
        "[KMPro][OfferParser] VALID OFFER pkg=$pkg fare=$fare distance=$distance duration=$duration",
      )
      OfferManager.addOffer(this, offer)
      Log.d(TAG, "[KMPro][Flutter] sending ride_offer id=${offer["id"]} pkg=$pkg")
    }
  }

  /**
   * OCR never touches the accessibility tree, so it has to identify the offer
   * app itself: the capture always contains the whole screen, so the first line
   * that names a watched package header (or a fare) decides whose screen this is.
   */
  private fun handleOcrLines(lines: List<String>) {
    val now = System.currentTimeMillis()
    val packageName = watchedPackageFromOcr(lines) ?: run {
      Log.d(TAG, "[KMPro][Ocr] nenhuma tela vigiada reconhecida, ignorada")
      return
    }
    val joined = lines.joinToString(" | ")
    val offer = OfferParser.extractOffer(
      key = "ocr-$now",
      packageName = packageName,
      windowTexts = lines,
      capturedAt = now,
    )
    if (offer == null) {
      val analysis = OfferParser.analyze(lines)
      Log.d(
        TAG,
        "[KMPro][Ocr] nao-oferta pkg=$packageName reason=${analysis.rejectReason} " +
          "texts=[${lines.take(20).joinToString(" ;; ") { it.take(80) }}]",
      )
      return
    }
    val signature = OfferParser.offerSignature(offer)
    if (signature == lastOfferSignatureByPkg[packageName]) {
      Log.d(TAG, "[KMPro][Ocr] oferta repetida, ignorada")
      return
    }
    lastOfferSignatureByPkg[packageName] = signature
    val fare = offer["fare"]?.toString() ?: "?"
    val distance = offer["distance"]?.toString() ?: "?"
    val duration = offer["durationMinutes"]?.toString() ?: "?"
    Log.d(
      TAG,
      "[KMPro][Ocr] VALID OFFER fare=$fare distance=$distance duration=$duration pkg=$packageName",
    )
    OfferManager.addOffer(this, offer)
    Log.d(TAG, "[KMPro][Flutter] sending ride_offer id=${offer["id"]} (via OCR)")
  }

/**
   * OCR can't report a package name, so infer it from the screen's own labels.
   * The logic and the reason it used to fail live in [WatchedApp].
   */
  private fun watchedPackageFromOcr(lines: List<String>): String? =
    WatchedApp.fromOcrLines(lines)

  private fun eventTypeName(type: Int): String = when (type) {
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
    AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
    else -> "TYPE_$type"
  }
}