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
  private var lastScreenSignature = ""

  /**
   * Apps vigiados com janela visível na última varredura. Um pacote que some
   * deste conjunto (app fechado/minimizado em tela dividida) teve a oferta
   * encerrada — o cartão dele sai na hora, sem esperar o "Tempo de tela".
   */
  private var lastSeenWatchedPkgs = setOf<String>()

  /**
   * Fim de qualquer varredura, inclusive as que voltaram cedo por dedup. É isso
   * que a porta de eventos usa para saber que a árvore já foi lida.
   */
  private var lastScanDoneAt = 0L

  /**
   * Teto de varreduras dirigidas por EVENTO. Medido numa sessão real da 99: a
   * Uber dispara WINDOW_CONTENT_CHANGED a 9,6/s e cada evento varrendo todas as
   * janelas produzia 20,3 scans/s, suficiente para saturar o handler - o tick do
   * POLL subia de 750 ms para 1107 ms de mediana e a cadência real de OCR ficava
   * em 2083 ms, porque o cooldown de 1200 ms não fechava conta com o tick.
   *
   * Nada se perde ao segurar o evento: o painel de oferta não aparece na árvore
   * de acessibilidade (no log da sessão medida, os CONTENT_CHANGED voltaram todos
   * como "mesmo texto" e quem achou a oferta foi o OCR do POLL), e o próprio POLL
   * já chama scanActiveWindow a cada tick. Descartar um evento que chegou logo
   * depois de uma varredura completa só adia o work no máximo um tick.
   */
  private val EVENT_SCAN_MIN_GAP_MS = 400L

  /**
   * Ciclo de vida da corrida por pacote — ver [RideLifecycle]. Substitui o mapa
   * de assinaturas que vivia aqui: ele guardava a última oferta por pacote e
   * nunca era limpo quando a corrida saía da tela, então a mesma corrida que
   * voltasse com valores idênticos era sempre descartada como repetida.
   *
   * Em tela dividida com Uber e 99 oferecendo ao mesmo tempo, cada app precisa
   * do seu próprio dedup: uma assinatura global faria as duas ofertas se
   * alternarem como "novas" a cada varredura.
   */

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
    if (!OfferManager.isCopilotoActive(this)) {
      Log.d(TAG, "[KMPro][Ocr] copiloto pausado, captura ignorada reason=$reason")
      return
    }
    val now = System.currentTimeMillis()
    val interval = if (reason == "MUTED") OCR_MUTED_MIN_INTERVAL_MS else OCR_MIN_INTERVAL_MS
    val last = if (reason == "MUTED") lastMutedOcrAt else lastOcrAt
    if (now - last < interval) {
      Log.d(TAG, "[KMPro][Ocr] cooldown, ignorando reason=$reason")
      return
    }
    if (reason == "MUTED") lastMutedOcrAt = now else lastOcrAt = now
    Log.d(TAG, "[KMPro][Ocr] iniciando captura reason=$reason")
    val engine = ocr ?: ScreenOcr(this).also { ocr = it }
    engine.capture(
      findRegion = { lines, width, height ->
        // Descarta o texto do próprio cartão do KMPro: sem isso o "15 min / 5,7 km"
        // dele vira uma perna fantasma e o OCR também relê a oferta que acabou de
        // ser mostrada. Antes isso era evitado pausando o OCR enquanto o cartão
        // estava na tela, o que impedia detectar a corrida seguinte.
        val clean = withoutOwnCards(lines)
        OfferRegionFinder.find(OfferParser.analyze(clean.map { it.text }), clean, width, height)
      },
    ) { read ->
      handler.post {
        if (read.full.isEmpty()) return@post
        Log.d(TAG, "[KMPro][Ocr] reason=$reason linhas=${read.full.size} regiao=${read.region != null}")
        handleOcrRead(read)
      }
    }
  }

  /** Remove as linhas que caem dentro de um cartão visível do KMPro. */
  private fun withoutOwnCards(lines: List<OcrLine>): List<OcrLine> {
    val cards = OfferOverlay.visibleCardRects()
    if (cards.isEmpty()) return lines
    return lines.filter { line -> cards.none { it.intersects(line.rect) } }
  }
  private val pollRunnable = object : Runnable {
    override fun run() {
      runCatching {
        // Copiloto pausado: nada é capturado nem emitido (OfferManager.addOffer
        // já descarta), então varrer a árvore e rodar OCR a cada tick só gastava
        // bateria. O gatilho de retomada é o próprio botão Iniciar, que reativa
        // o serviço; aqui basta não fazer trabalho enquanto estiver parado.
        if (!OfferManager.isCopilotoActive(this@RideAccessibilityService)) {
          return@runCatching
        }
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
    lastScreenSignature = ""
    RideLifecycle.clear()
    OfferManager.setAccessibilityConnected(this, true)
    if (ocr == null) {
      ocr = ScreenOcr(this)
      Log.d(TAG, "[KMPro][Ocr] motor de OCR pronto (on-device)")
    }
    // Capture the screen as soon as the service binds — mas só se o Copiloto
    // estiver ativo; pausado, o POLL abaixo fica ocioso até o Iniciar.
    if (OfferManager.isCopilotoActive(this)) {
      handler.post { scanActiveWindow("SERVICE_CONNECTED") }
    }
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
    RideLifecycle.clear()
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

    // Pausado, eventos não disparam varredura: o POLL também está parado, então
    // nenhum trabalho de captura acontece até o usuário dar Iniciar.
    if (!OfferManager.isCopilotoActive(this)) return

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

  private fun scanActiveWindow(reason: String) {
    val startedAt = System.currentTimeMillis()
    // Varredura dirigida por evento chegando logo depois de outra varredura
    // qualquer: a árvore já foi lida e o POLL cobre o intervalo. Pular aqui é o
    // que impede o handler de ser inundado — o resto do método continua igual.
    if (reason == "CONTENT_FAST" || reason == "CONTENT_DEBOUNCE") {
      val sinceLast = startedAt - lastScanDoneAt
      if (lastScanDoneAt != 0L && sinceLast < EVENT_SCAN_MIN_GAP_MS) {
        Log.d(
          TAG,
          "[KMPro][Accessibility] evento adiado reason=$reason " +
            "ms_desde_ultima_varredura=$sinceLast (piso=${EVENT_SCAN_MIN_GAP_MS}ms)",
        )
        return
      }
    }
    try {
      scanWatchedRoots(reason, startedAt)
    } finally {
      // Todo caminho de saída conta, inclusive os que voltaram por dedup: a porta
      // de eventos só é justa se uma varredura que NÃO produziu nada novo ainda
      // conta como leitura da árvore.
      lastScanDoneAt = System.currentTimeMillis()
    }
  }

  private fun scanWatchedRoots(reason: String, now: Long) {
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
      // Lista de janelas vazia pode ser transitória (rebind, rotação): sem essa
      // marca, um intervalo sem janelas esconderia cartões de ofertas ainda na
      // tela — e o dedup de assinatura impediria o re-show.
      var sawAnyWindow = false
      for (w in windows) {
        sawAnyWindow = true
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
      // Um app que saiu desta varredura (fechado/minimizado) encerra a oferta
      // dele: esconde só o cartão daquele pacote — em tela dividida, o cartão do
      // outro app permanece. O hide geral abaixo cobre quando TODAS as janelas
      // vigiadas somem.
      if (sawAnyWindow) {
        val gone = lastSeenWatchedPkgs - alive
        for (pkg in gone) {
          Log.d(TAG, "[KMPro][Accessibility] app saiu da tela, escondendo cartão pkg=$pkg")
          OfferManager.dropOffersFor(this, pkg)
          OfferManager.hideOfferOverlayFor(pkg)
          RideLifecycle.forget(pkg)
        }
        lastSeenWatchedPkgs = alive
      }
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
      // `alive` já escondeu o cartão de um app que fechou; aqui cobre o caso de
      // TODAS as janelas vigiadas terem sumido (app único fechado/minimizado):
      if (reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED" || reason == "POLL") {
        OfferManager.hideOfferOverlay()
      }
      return
    }
    if (reason == "CONTENT_FAST" || reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED") {
      dumpWindows("scan")
    }

    // Uma leitura por raiz, reutilizada na assinatura e no laço de ofertas. Antes
    // a mesma árvore era percorrida com TreeDump.allText() duas vezes (aqui e de
    // novo dentro do laço) e o primeiro root ainda era lido uma terceira vez no
    // bloco de esconder o cartão. A varredura é síncrona no handler principal,
    // então cada duplicata atrasa o tick do POLL.
    val textsByRoot = LinkedHashMap<AccessibilityNodeInfo, List<String>>()
    val overallSig = roots
      .joinToString("~") { r ->
        val texts = TreeDump.allText(r).distinct()
        textsByRoot[r] = texts
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

    /**
     * Uma oferta por app: em tela dividida com Uber e 99 oferecendo juntas, cada
     * janela vigiada é analisada e cada oferta válida é encaminhada — o cartão
     * de cada uma aparece sobre o próprio app. Sem `break`: parar na primeira
     * janela com oferta faria a segunda nunca ser capturada.
     */
    val offersByPkg = LinkedHashMap<String, Map<String, Any?>>()

    for (root in roots) {
      val rootPackage = root.packageName?.toString().orEmpty()
      // Reaproveita a leitura da assinatura; a raiz está sempre no mapa porque a
      // assinatura percorre exatamente as mesmas raízes, na mesma ordem.
      val textLines = textsByRoot[root] ?: TreeDump.allText(root).distinct()
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

    /**
     * Presença/ausência de corrida por app, avaliada em TODAS as varreduras e
     * para TODOS os roots — não só quando nenhuma app tem oferta.
     *
     * Duas correções para o acionamento do cartão:
     *
     * 1. Era avaliado só `roots.first()`. Com Uber e 99 abertos, se a primeira
     * janela estivesse muda, o `if (segs.isNotEmpty())` falhava e NENHUM cartão
     * era removido — inclusive o do app que realmente tinha encerrado a corrida.
     *
     * 2. Fica dentro de `offersByPkg.isEmpty()`, então uma app com oferta
     * "blindava" a outra: a 99 podia encerrar a corrida com a Uber ainda
     * oferecendo e o cartão da 99 ficaria na tela.
     */
    if (reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED" || reason == "POLL") {
      for (root in roots) {
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg in offersByPkg) continue
        // Ausência só prova alguma coisa numa tela normal: uma raiz muda
        // significa que a árvore não expõe nada (o painel de oferta não é
        // publicado nela), então não dá para concluir nada por ali.
        if (textsByRoot[root].isNullOrEmpty()) continue
        if (!RideLifecycle.observeAbsent(pkg)) continue
        Log.d(TAG, "[KMPro][Ride] corrida encerrada pkg=$pkg (${RideLifecycle.describe(pkg)})")
        // O dedup tem que morrer JUNTO com a oferta: com a assinatura guardada,
        // a próxima corrida — mesmo com valores idênticos — parecia repetida e o
        // cartão não voltava nunca.
        OfferManager.dropOffersFor(this, pkg)
        OfferManager.hideOfferOverlayFor(pkg)
      }
    }

    if (offersByPkg.isEmpty()) {
      // The overlay card must leave when the offer is gone from a real screen.
      if (reason == "WINDOW_STATE" || reason == "SERVICE_CONNECTED" || reason == "POLL") {
        // Terceira leitura da mesma raiz eliminada: o texto já foi lido na
        // assinatura.
        val segs = roots.firstOrNull()?.let { textsByRoot[it] }.orEmpty()
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
      // A corrida voltou depois de ter sido declarada encerrada: este é o único
      // caminho que reexibe o cartão com os mesmos valores de antes.
      if (RideLifecycle.observePresent(pkg)) {
        Log.d(TAG, "[KMPro][Ride] nova corrida pkg=$pkg (${RideLifecycle.describe(pkg)})")
      }
      val signature = OfferParser.offerSignature(offer)
      if (signature == RideLifecycle.shownSignature(pkg)) {
        Log.d(TAG, "[KMPro][OfferParser] oferta repetida de $pkg, ignorada")
        continue
      }
      RideLifecycle.markShown(pkg, signature)
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
   * app itself. Classification prefers the cropped region (the full screen also
   * carries promo banners — 99's "9l 100" used to be read as a 99 header during
   * an Uber offer) and falls back to the full screen when there was no region.
   * The offer itself is read from the region when available: the second pass is
   * the authoritative one, over the big card text only.
   */
  private fun handleOcrRead(read: ScreenOcr.Read) {
    val now = System.currentTimeMillis()
    val full = withoutOwnCards(read.full)
    val regionLines = withoutOwnCards(read.regionLines)
    if (full.isEmpty()) return

    val packageName = regionLines.map { it.text }
      .takeIf { it.isNotEmpty() }
      ?.let { watchedPackageFromOcr(it) }
      ?: watchedPackageFromOcr(full.map { it.text })
      ?: run {
        Log.d(TAG, "[KMPro][Ocr] nenhuma tela vigiada reconhecida, ignorada")
        return
      }

    val fullTexts = full.map { it.text }
    val regionTexts = if (read.region != null && regionLines.isNotEmpty()) {
      regionLines.map { it.text }
    } else {
      emptyList()
    }
    fun extract(texts: List<String>): Map<String, Any?>? =
      if (texts.isEmpty()) {
        null
      } else {
        OfferParser.extractOffer(
          key = "ocr-$now",
          packageName = packageName,
          windowTexts = texts,
          capturedAt = now,
        )
      }

    // O recorte é a leitura preferida (texto maior, menos erro de dígito), mas
    // pode perder uma linha: numa oferta real da Uber em tela dividida o crop
    // deixou de fora o "8 min" e o "Selecionar", e a segunda passada, sozinha,
    // não fechou a oferta. Se o recorte não fechar, cai para a tela inteira —
    // que foi quem achou a região e normalmente fecha.
    val offer = extract(regionTexts) ?: extract(fullTexts)
    if (offer == null) {
      val attempted = regionTexts.ifEmpty { fullTexts }
      val analysis = OfferParser.analyze(attempted)
      Log.d(
        TAG,
        "[KMPro][Ocr] nao-oferta pkg=$packageName reason=${analysis.rejectReason} " +
          "texts=[${attempted.take(20).joinToString(" ;; ") { it.take(80) }}]",
      )
      // Uma tela vigiada está nos pixels mas sem corrida: a corrida acabou, mesmo
      // que a árvore demore a soltar. Mesmo ciclo da varredura da árvore.
      if (RideLifecycle.isOnScreen(packageName) && RideLifecycle.observeAbsent(packageName)) {
        Log.d(TAG, "[KMPro][Ride] corrida encerrada pkg=$packageName (via OCR)")
        OfferManager.dropOffersFor(this, packageName)
        OfferManager.hideOfferOverlayFor(packageName)
      }
      return
    }
    val signature = OfferParser.offerSignature(offer)
    // Mesmo caminho de ciclo da árvore: o OCR marca a corrida presente e guarda a
    // assinatura, e quem decide que a corrida acabou é a varredura da árvore (que
    // vê a tela normal voltar). Sem as duas pontas conversando, um lado limpava o
    // dedup que o outro tinha acabado de gravar.
    if (RideLifecycle.observePresent(packageName)) {
      Log.d(TAG, "[KMPro][Ride] nova corrida pkg=$packageName (via OCR)")
    }
    if (signature == RideLifecycle.shownSignature(packageName)) {
      Log.d(TAG, "[KMPro][Ocr] oferta repetida, ignorada")
      return
    }
    RideLifecycle.markShown(packageName, signature)
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