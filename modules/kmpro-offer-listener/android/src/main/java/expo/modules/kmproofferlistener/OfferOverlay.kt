package expo.modules.kmproofferlistener

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cartões flutuantes exibidos SOBRE o app de transporte quando uma oferta aparece.
 *
 * O layout espelha exatamente o componente `OfferCard` do app (Ajustes >
 * Aparência do cartão), usando os mesmos tokens de cor do Tailwind: fundo
 * `card` escuro, faixa inferior `secondary`, separadores `border` e a cor de
 * destaque apenas na BORDA e nas métricas de lucro. A prévia nas configurações
 * e o cartão real precisam ser idênticos, então as duas implementões seguem o
 * mesmo desenho.
 *
 * Multi-cartão e âncora por app:
 * - Cada app vigiado (Uber/99) tem no máximo UM cartão, identificado pelo
 *   pacote. Ofertas novas do mesmo app atualizam o cartão existente.
 * - O [RideAccessibilityService] publica os bounds de tela de cada app em
 *   [AppWindowBounds]; em tela dividida o cartão é ancorado DENTRO da janela do
 *   app de origem (a oferta da Uber fica sobre a Uber, a da 99 sobre a 99) e a
 *   escolha esquerda/centro/direita de Ajustes é aplicada dentro dessa janela.
 * - Sem bounds conhecidos (app em segundo plano, oferta via notificação), o
 *   cartão cai na âncora clássica de tela inteira. Se já houver outro cartão na
 *   tela nessa situação, a oferta entra numa FILA FIFO e é exibida na ordem de
 *   captura, sem atraso artificial: a próxima entra assim que a atual esconder.
 *
 * Exige a permissão SYSTEM_ALERT_WINDOW. Ocultado quando a oferta some.
 */
object OfferOverlay {
  private const val TAG = "KMProOverlay"
  private const val PREFS_KEY_ENABLED = "overlay_enabled"

  /** Oferta raramente fica na tela por mais que isso; esconde por segurança. */
  /** Geometria fixa do cartão, em dp. */
  private const val CARD_WIDTH_DP = 360
  /** Largura mínima quando a janela do app for mais estreita que o cartão. */
  private const val MIN_CARD_WIDTH_DP = 240
  /** Espaço entre o cartão e a borda (da tela ou da janela do app). */
  private const val CARD_MARGIN_DP = 12f
  /** Folga entre a barra de status/o topo da janela e o topo do cartão. */
  private const val CARD_TOP_MARGIN_DP = 16f

  /** Limite da fila de ofertas aguardando exibição. */
  private const val MAX_QUEUE = 4

  /** Espessuras em dp, espelhando o `border` e o `<Separator />` do `OfferCard`. */
  /** `border-4` do cartão (Ajustes e `OfferCard` usam 4px). */
  private const val BORDER_DP = 4f
  private const val DIVIDER_DP = 1f

  // Tokens de cor do app (tailwind.config.js).
  private const val COLOR_CARD = 0xFF0a0a0a.toInt()
  private const val COLOR_SECONDARY = 0xFF131313.toInt()
  private const val COLOR_BORDER = 0xFF1f1f1f.toInt()
  private const val COLOR_FOREGROUND = 0xFFfafafa.toInt()
  private const val COLOR_MUTED = 0xFFa3a3a3.toInt()
  private const val COLOR_PRIMARY = 0xFF10b981.toInt()
  private const val COLOR_WARNING = 0xFFf59e0b.toInt()
  private const val COLOR_DESTRUCTIVE = 0xFFef4444.toInt()

  /** Id do cartão "genérico" (oferta sem pacote conhecido / prévia). */
  private const val SINGLE_CARD_ID = "__screen__"

  /** Estado de UM cartão na tela: um por app vigiado, no máximo. */
  private class CardState(val id: String) {
    var anchoredPkg: String = ""
    var view: ViewGroup? = null
    var params: WindowManager.LayoutParams? = null
    var wm: WindowManager? = null
    var metricOrder: List<String>? = null
    var hideRunnable: Runnable? = null
    // Última âncora aplicada à janela, para só chamar updateViewLayout quando
    // algo realmente mudou (a varredura de acessibilidade roda a cada ~750 ms).
    var lastGravity: Int = Int.MIN_VALUE
    var lastX: Int = Int.MIN_VALUE
    var lastY: Int = Int.MIN_VALUE
    var lastWidth: Int = Int.MIN_VALUE
    /**
     * Retângulo do cartão na tela. O OCR da tela inteira lê o texto do PRÓPRIO
     * cartão do KMPro e o "15 min · 5,7 km" daqui já virou uma perna fantasma
     * numa sessão real; este retângulo permite descartar as linhas que caem
     * dentro do cartão antes de analisar a oferta.
     */
    @Volatile var screenRect: OcrRect? = null
  }

  /** Oferta pronta para exibição, possivelmente aguardando na fila. */
  private class Pending(
    val context: Context,
    val offer: Map<String, Any?>,
    val key: String,
    val accent: Int,
    val anchoredPkg: String,
  ) {
    val cardId: String get() = anchoredPkg.ifEmpty { SINGLE_CARD_ID }
  }

  /** Cartões visíveis: chave = pacote do app (ou [SINGLE_CARD_ID]). */
  private val cards = ConcurrentHashMap<String, CardState>()

  /** Fila FIFO: exibidas na ordem de captura, sem atraso, uma após a outra. */
  private val queue = ArrayDeque<Pending>()

  private var enabled = true
  private var debugHold = false

  /** Contexto de app do último cartão criado, para drenar a fila sem depender
   *  de quem pediu a remoção passar um contexto. */
  private var lastContext: Context? = null

  private val mainHandler = Handler(Looper.getMainLooper())

  fun setEnabled(context: Context, value: Boolean) {
    enabled = value
    prefs(context).edit().putBoolean(PREFS_KEY_ENABLED, value).apply()
    if (!value) hide()
  }

  fun isEnabled(context: Context): Boolean =
    prefs(context).getBoolean(PREFS_KEY_ENABLED, true)

  fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

  fun openSettings(context: Context) {
    val intent = android.content.Intent(
      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
      android.net.Uri.parse("package:${context.packageName}"),
    )
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  /**
   * Mostra (ou atualiza) o cartão do app de origem da oferta. Chamado a cada
   * detecção válida. Oferta repetida do mesmo app atualiza o cartão no lugar;
   * oferta de outro app cria um cartão próprio ou entra na fila.
   */
  @Synchronized
  fun show(context: Context, offer: Map<String, Any?>) {
    if (!isEnabled(context)) {
      Log.d(TAG, "ignorado: overlay desativado")
      return
    }
    if (!canDraw(context)) {
      Log.d(TAG, "ignorado: sem permissao SYSTEM_ALERT_WINDOW")
      return
    }

    val app = context.applicationContext
    lastContext = app
    val key = OfferParser.offerSignature(offer)
    val classification = offer["classification"]?.toString() ?: RideCalc.GREEN
    val accent = when (classification) {
      RideCalc.GREEN -> COLOR_PRIMARY
      RideCalc.YELLOW -> COLOR_WARNING
      else -> COLOR_DESTRUCTIVE
    }
    val pkg = offer["packageName"]?.toString().orEmpty()
    val anchoredPkg = if (pkg in OfferManager.DEFAULT_RIDE_PACKAGES) pkg else ""

    mainHandler.post {
      val pending = Pending(app, offer, key, accent, anchoredPkg)
      if (!present(pending)) {
        // Já existe um cartão sem âncora própria na tela: entra na fila e
        // aparece assim que o cartão atual esconder, na ordem de captura.
        queue.addLast(pending)
        while (queue.size > MAX_QUEUE) {
          val dropped = queue.removeFirst()
          Log.d(TAG, "fila cheia, descartando o mais antigo pkg=${dropped.anchoredPkg}")
        }
        Log.d(TAG, "cartao em fila pkg=$pkg fila=${queue.size}")
      }
    }
  }

  /**
   * Apresenta (cria/atualiza) o cartão de [pending]. Retorna false quando a
   * oferta precisou esperar: sem âncora de janela própria e com outro cartão
   * já visível na mesma área da tela.
   */
  private fun present(pending: Pending): Boolean {
    val existing = cards[pending.cardId]
    if (existing != null) {
      // Mesmo app: a oferta nova substitui a anterior no mesmo cartão.
      existing.anchoredPkg = pending.anchoredPkg
      buildOrUpdate(pending, existing)
      return true
    }
    val canAnchor =
      pending.anchoredPkg.isNotEmpty() && AppWindowBounds.get(pending.anchoredPkg) != null
    // Sem âncora própria E com cartão já na tela: os dois cairiam no mesmo
    // lugar. Fila — a próxima entra sem atraso quando a atual esconder.
    if (!canAnchor && cards.isNotEmpty()) return false

    // O cartão genérico de tela inteira (sem âncora) é sempre inferior ao
    // ancorado: assim que a âncora de um app existe, ele sai para não haver
    // dois cartões disputando o topo da tela.
    if (canAnchor) cards.remove(SINGLE_CARD_ID)?.let(::removeCard)

    val state = CardState(pending.cardId)
    state.anchoredPkg = pending.anchoredPkg
    cards[pending.cardId] = state
    buildOrUpdate(pending, state)
    return true
  }

  /**
   * Reâncora os cartões visíveis e drena a fila. Chamado pelo serviço de
   * acessibilidade a cada varredura (bounds podem ter mudado com rotação ou
   * tela dividida) e sempre que um cartão esconde.
   */
  fun drainQueue(context: Context) {
    mainHandler.post { drainQueueInternal(context.applicationContext) }
  }

  /** Executa no main thread: reancora e promove as ofertas da fila. */
  private fun drainQueueInternal(app: Context?) {
    if (app == null) return
    lastContext = app
    reanchorAll(app)
    while (queue.isNotEmpty()) {
      val head = queue.first()
      if (present(head)) queue.removeFirst() else break
    }
  }

  /**
   * Renderiza o cartão com dados fictícios para conferência visual do layout.
   * Só é acionado pelo receiver de debug; o caminho de captura real continua
   * sendo OCR e nunca usa este método.
   */
  fun showPreview(context: Context) {
    val sample = linkedMapOf<String, Any?>(
      "fare" to 11.01,
      "distance" to 4.4,
      "durationMinutes" to 7.0,
      "costPerKm" to 0.62,
      "rating" to 4.89,
      "classification" to RideCalc.GREEN,
    )
    show(context, sample)
  }

  /**
   * Impede o auto-hide durante a conferência de layout. Só usado pelo receiver
   * de debug; a captura real nunca chama isto.
   */
  fun holdForDebug() {
    debugHold = true
  }

  fun releaseDebugHold() {
    debugHold = false
  }

  /**
   * Loga texto, cor e bounds de cada nó do cartão. Usado só pelo receiver de
   * debug para conferir a paridade com a prévia sem depender de screenshot.
   */
  fun dumpTree() {
    val root = cards.values.firstOrNull()?.view ?: return
    val sb = StringBuilder()
    fun walk(v: android.view.View, depth: Int) {
      val pad = "  ".repeat(depth)
      when (v) {
        is TextView -> {
          val color = runCatching { String.format("#%06X", 0xFFFFFF and v.currentTextColor) }
            .getOrDefault("?")
          sb.append("$pad#$depth text=${v.text} color=$color size=${v.textSize} bounds=${v.left},${v.top},${v.right},${v.bottom}\n")
        }
        is LinearLayout -> {
          val bg = v.background?.let { b ->
            (b as? android.graphics.drawable.ColorDrawable)?.color
          }?.let { String.format("#%06X", 0xFFFFFF and it) } ?: "none"
          val lp = (v.layoutParams as? LinearLayout.LayoutParams)
          val ps = if (lp == null) "lp=null" else "w=${lp.width} h=${lp.height} wt=${lp.weight}"
          sb.append("$pad#$depth LinearLayout bg=$bg $ps bounds=${v.left},${v.top},${v.right},${v.bottom}\n")
        }
        else -> {
          val lp = (v.layoutParams as? LinearLayout.LayoutParams)
          val ps = if (lp == null) "lp=null" else "w=${lp.width} h=${lp.height} wt=${lp.weight}"
          sb.append("$pad#$depth ${v.javaClass.simpleName} $ps bounds=${v.left},${v.top},${v.right},${v.bottom}\n")
        }
      }
      if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
    }
    walk(root, 0)
    Log.d(TAG, "TREE\n$sb")
  }

  /**
   * Retângulos dos cartões visíveis, em coordenadas de tela. O OCR da tela
   * inteira descarta as linhas que caem aqui antes de analisar a oferta — assim
   * o próprio cartão do KMPro não vira oferta (nem some com a perna real) sem
   * precisar pausar o OCR enquanto ele está na tela. A pausa antiga tinha o
   * efeito colateral de não detectar a corrida seguinte enquanto um cartão
   * estivesse visível.
   */
  fun visibleCardRects(): List<OcrRect> =
    cards.values.mapNotNull { it.screenRect }

  /** Remove TODOS os cartões e limpa a fila (parar Copiloto, debug, etc.). */
  fun hide() {
    mainHandler.post {
      // Durante a conferência de debug o cartão não sai por conta própria;
      // só o receiver de debug o remove.
      if (debugHold) return@post
      cards.values.forEach(::removeCard)
      cards.clear()
      queue.clear()
    }
  }

  /** Remove só o cartão do app informado; os demais permanecem. A fila é
   *  drenada: a vaga aberta pode permitir exibir a próxima oferta. */
  fun hideFor(packageName: String) {
    if (packageName.isBlank()) return
    mainHandler.post {
      if (debugHold) return@post
      cards.remove(packageName)?.let(::removeCard)
      drainQueueInternal(lastContext)
    }
  }

  private fun removeCard(state: CardState) {
    state.hideRunnable?.let { mainHandler.removeCallbacks(it) }
    state.hideRunnable = null
    val wm = state.wm
    val v = state.view
    if (wm != null && v != null) runCatching { wm.removeView(v) }
    state.view = null
    state.wm = null
    state.params = null
    state.screenRect = null
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun buildOrUpdate(
    pending: Pending,
    state: CardState,
  ) {
    val context = pending.context
    val dpi = context.resources.displayMetrics.density
    val appearance = OfferManager.cardAppearance(context)
    val order = appearance.metricOrder
    val position = appearance.cardPosition
    val bounds = state.anchoredPkg.takeIf { it.isNotEmpty() }
      ?.let { AppWindowBounds.get(it) }

    // A borda segue o pior tom entre a classificação e cada métrica exibida,
    // para nunca ficar verde com um mostrador vermelho/amarelo. A classificação
    // entra como piso; sem metas configuradas, cai nela.
    val goals = OfferManager.cardGoals(context)
    val metrics = renderMetrics(order, pending.offer, goals)
    val classification = pending.offer["classification"]?.toString() ?: RideCalc.GREEN
    val accent =
      colorFor(CardGoals.worst(listOf(classification) + metrics.map { it.tone }))
        ?: pending.accent

    val container: ViewGroup
    if (state.view == null || state.metricOrder != order) {
      // Cartão novo, ou a ordem das métricas mudou em Ajustes: reconstrói.
      removeCard(state)
      // Falha ao montar o cartão: tira o estado do mapa para não deixar um
      // CardState órfão (sem view) que bloquearia o cartão genérico e a fila,
      // como se houvesse um cartão na tela.
      val built = buildCard(context, order, accent) ?: run {
        cards.remove(state.id)
        return
      }
      container = built

      // O card é fixo: sem arraste. Tocar nele fecha, para não cobrir os
      // botões de aceitar/recusar do app de corrida.
      container.setOnClickListener {
        removeCard(state)
        cards.remove(state.id)
        drainQueue(context)
      }

      val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
      if (wm == null) {
        cards.remove(state.id)
        return
      }
      val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          @Suppress("DEPRECATION")
          WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
      state.view = container
      state.wm = wm
      state.params = lp
      state.metricOrder = order
      applyLayout(state, bounds, position, dpi, context, isNew = true)
    } else {
      container = state.view as? ViewGroup ?: return
      applyLayout(state, bounds, position, dpi, context, isNew = false)
    }
    applyAccent(container, context, accent)
    applyContent(container, metrics, goals, pending.offer)

    // Visibilidade limitada pelo seletor "Tempo de tela" de Ajustes, por cartão.
    state.hideRunnable?.let { mainHandler.removeCallbacks(it) }
    val lifetime = appearance.screenDurationMs
    val autoHide = Runnable {
      // Durante a conferência de debug o auto-hide fica suspenso; só o receiver
      // de debug (ou tocar no cartão) o remove.
      if (debugHold) return@Runnable
      removeCard(state)
      cards.remove(state.id)
      drainQueue(context)
    }
    state.hideRunnable = autoHide
    mainHandler.postDelayed(autoHide, lifetime)
  }

  /**
   * Posiciona o cartão:
   * - COM bounds do app (tela dividida): âncora absoluta dentro da janela do
   *   app, com a posição de Ajustes (esquerda/centro/direita) aplicada ao
   *   intervalo horizontal da janela e o topo encostado no topo da janela.
   * - SEM bounds: âncora clássica na tela inteira, logo abaixo da barra de
   *   status, na posição configurada.
   */
  private fun applyLayout(
    state: CardState,
    bounds: AppWindowBounds.Bounds?,
    position: String,
    dpi: Float,
    context: Context,
    isNew: Boolean,
  ) {
    val lp = state.params ?: return
    val container = state.view ?: return
    val lpChanged: Boolean
    if (bounds != null) {
      val marginPx = (CARD_MARGIN_DP * dpi).toInt()
      val widthPx = minOf(
        (CARD_WIDTH_DP * dpi).toInt(),
        (bounds.width - 2 * marginPx).coerceAtLeast((MIN_CARD_WIDTH_DP * dpi).toInt()),
      )
      lp.gravity = Gravity.TOP or Gravity.START
      lp.width = widthPx
      lp.x = when (position) {
        "esquerda" -> bounds.left + marginPx
        "direita" -> bounds.right - marginPx - widthPx
        else -> bounds.centerX - widthPx / 2
      }
      lp.y = bounds.top + (CARD_TOP_MARGIN_DP * dpi).toInt()
      lpChanged = lp.gravity != state.lastGravity || lp.x != state.lastX ||
        lp.y != state.lastY || lp.width != state.lastWidth
      state.lastGravity = lp.gravity
      state.lastX = lp.x
      state.lastY = lp.y
      state.lastWidth = lp.width
    } else {
      // `cardPosition` ancora o cartão em Ajustes: centro na tela, ou
      // encostado na borda esquerda/direita, sempre centralizado na vertical.
      lp.gravity = gravityFor(position)
      lp.x = horizontalOffset(position, dpi)
      lp.y = 0
      lp.width = (CARD_WIDTH_DP * dpi).toInt()
      lpChanged = lp.gravity != state.lastGravity || lp.x != state.lastX ||
        lp.y != state.lastY || lp.width != state.lastWidth
      state.lastGravity = lp.gravity
      state.lastX = lp.x
      state.lastY = lp.y
      state.lastWidth = lp.width
    }

    if (isNew) {
      runCatching { state.wm?.addView(container, lp) }
        .onFailure { Log.e(TAG, "addView falhou: ${it.message}", it) }
        .onSuccess {
          // Só depois de anexado à janela é que `rootWindowInsets` devolve a
          // altura real da barra de status (necessária no fallback de tela).
          // O snapshot de y acompanha, senão a próxima varredura veria mudança
          // falsa e religaria o cartão sob a barra de status.
          if (bounds == null) {
            lp.y = topOffset(container, context)
            state.lastY = lp.y
            runCatching { state.wm?.updateViewLayout(container, lp) }
          }
          Log.d(
            TAG,
            "addView ok id=${state.id} ancorado=${bounds != null} x=${lp.x} y=${lp.y} w=${lp.width}",
          )
        }
    } else {
      // A varredura de acessibilidade roda a cada ~750 ms; só remonta a janela
      // quando a âncora realmente mudou (tela dividida aberta/fechada, rotação,
      // posição alterada em Ajustes), senão é redesenho desperdiçado.
      if (lpChanged) {
        runCatching { state.wm?.updateViewLayout(container, lp) }
      }
    }
    // O retângulo só existe depois do layout; `post` garante que o cartão já foi
    // medido. É o que o OCR usa para descartar o texto do próprio cartão.
    container.post { state.screenRect = rectOnScreen(container) }
  }

  /** Retângulo do cartão em coordenadas de tela, ou null se ainda não anexado. */
  private fun rectOnScreen(container: View): OcrRect? {
    if (!container.isAttachedToWindow) return null
    val location = IntArray(2)
    container.getLocationOnScreen(location)
    val width = container.width
    val height = container.height
    if (width <= 0 || height <= 0) return null
    return OcrRect(
      left = location[0],
      top = location[1],
      right = location[0] + width,
      bottom = location[1] + height,
    )
  }

  /** Reaplica a âncora dos cartões visíveis (bounds/posição podem ter mudado). */
  private fun reanchorAll(context: Context) {
    if (cards.isEmpty()) return
    val dpi = context.resources.displayMetrics.density
    val position = OfferManager.cardAppearance(context).cardPosition
    for (state in cards.values) {
      val view = state.view ?: continue
      val bounds = state.anchoredPkg.takeIf { it.isNotEmpty() }
        ?.let { AppWindowBounds.get(it) }
      applyLayout(state, bounds, position, dpi, context, isNew = false)
      view.requestLayout()
    }
  }

  /** `rounded-xl` do Tailwind equivale a 12dp. */
  private fun cornerRadiusPx(density: Float): Float = 12f * density

  /**
   * Estrutura do cartão, espelhando `OfferCard`:
   *   container (borda colorida, cantos arredondados)
   *     ├── topo: fundo `card`, fileira horizontal com as métricas na ordem
   *   │          configurada (rótulo pequeno por cima, valor embaixo)
   *     ├── separador
   *     └── base: fundo `secondary` com min/km e avaliação
   */
  private fun buildCard(
    context: Context,
    order: List<String>,
    accent: Int,
  ): FrameLayout? {
    val density = context.resources.displayMetrics.density
    fun dp(value: Float): Int = (value * density).toInt()

    val root = FrameLayout(context)
    // A borda é do `card` interno, não do root: o root é só a moldura que
    // carrega a badge por cima.
    val card = LinearLayout(context)
    card.orientation = LinearLayout.VERTICAL
    // O `setStroke` do fundo é desenhado dentro dos limites do card, e as seções
    // internas (topo/base) pintam a mesma área. Sem um recuo elas cobririam a
    // borda colorida e ela sumiria — o recuo é a própria espessura da borda
    // (4dp, o `border-4` do `OfferCard`).
    card.setPadding(
      px(BORDER_DP, density), px(BORDER_DP, density),
      px(BORDER_DP, density), px(BORDER_DP, density),
    )

    val inner = LinearLayout(context)
    inner.orientation = LinearLayout.VERTICAL

    val top = LinearLayout(context)
    top.orientation = LinearLayout.HORIZONTAL
    top.background = solid(COLOR_CARD, innerRadiusPx(density), topOnly = true)
    top.gravity = Gravity.CENTER
    top.setPadding(dp(12f), dp(14f), dp(12f), dp(14f))

    // Card de fileira: as métricas ficam lado a lado, cada uma com rótulo
    // pequeno em cima e o valor embaixo, em colunas de peso igual. A margem
    // lateral garante folga entre os mostradores mesmo com valores largos,
    // ex.: "R$ 999,00" em todas as colunas.
    order.forEachIndexed { index, metricId ->
      val column = LinearLayout(context)
      column.orientation = LinearLayout.VERTICAL
      column.gravity = Gravity.CENTER_HORIZONTAL
      val label = TextView(context)
      label.id = ID_MLABEL_BASE + index
      label.sizeDp(14f, density)
      label.includeFontPadding = false
      label.setTextColor(COLOR_MUTED)
      label.text = metricCardLabel(metricId)
      column.addView(label)
      val tv = TextView(context)
      tv.id = ID_METRIC_BASE + index
      tv.sizeDp(24f, density)
      tv.typeface = Typeface.DEFAULT_BOLD
      tv.setTextColor(COLOR_FOREGROUND)
      tv.includeFontPadding = false
      column.addView(tv)
      top.addView(column, LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
      ).apply { setMargins(dp(4f), 0, dp(4f), 0) })
    }
    inner.addView(top, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT,
    ))

    inner.addView(divider(context), LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      dp(1f),
    ))

    val bottom = LinearLayout(context)
    bottom.orientation = LinearLayout.VERTICAL
    bottom.background = solid(COLOR_SECONDARY, innerRadiusPx(density), topOnly = false)
    bottom.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))

    // Linha 1: duração • distância .......... avaliação
    val facts = LinearLayout(context)
    facts.orientation = LinearLayout.HORIZONTAL
    facts.gravity = Gravity.CENTER_VERTICAL
    facts.id = ID_FACTS
    val minutes = TextView(context)
    minutes.id = ID_MINUTES
    minutes.sizeDp(14f, density)
    minutes.includeFontPadding = false
    minutes.setTextColor(COLOR_FOREGROUND)
    facts.addView(minutes)
    facts.addView(dot(context))
    val km = TextView(context)
    km.id = ID_KM
    km.sizeDp(14f, density)
    km.includeFontPadding = false
    km.setTextColor(COLOR_FOREGROUND)
    facts.addView(km)
    // Espaçador que empurra a avaliação para a direita, como o `flex-1` da UI.
    facts.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    // Estrela sempre amarela; só o número muda de cor conforme a meta.
    val ratingGroup = LinearLayout(context)
    ratingGroup.orientation = LinearLayout.HORIZONTAL
    ratingGroup.gravity = Gravity.CENTER_VERTICAL
    val star = TextView(context)
    star.id = ID_STAR
    star.sizeDp(13f, density)
    star.includeFontPadding = false
    star.text = "★"
    star.setTextColor(COLOR_WARNING)
    ratingGroup.addView(star)
    val rating = TextView(context)
    rating.id = ID_RATING
    rating.sizeDp(13f, density)
    rating.includeFontPadding = false
    rating.typeface = Typeface.DEFAULT_BOLD
    rating.setTextColor(COLOR_FOREGROUND)
    ratingGroup.addView(rating)
    facts.addView(ratingGroup)
    bottom.addView(facts)

    inner.addView(bottom, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT,
    ))

    card.addView(inner, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT,
    ))

    // A borda é desenhada pelo background do card, sem clipToOutline: recortar
    // aqui apagaria a cor justamente nas curvas. Quem faz os cantos arredondados
    // são as seções internas, com raio igual ao externo menos a borda.
    card.background = bordered(accent, density)
    card.id = ID_CARD_ROOT

    // Badge do valor da corrida: pílula com a cor do tom, pendurada na borda
    // superior do card e centralizada, no mesmo contraste do valor "cravado".
    // Fica na mesma janela, então acompanha a âncora esquerda/centro/direita.
    //
    // A janela do overlay é WRAP_CONTENT: a badge precisa caber dentro do root,
    // senão a superfície a recorta. O `-mt-4`/`-mb-4` do `OfferCard` vira um
    // `root` em FrameLayout com o card deslocado para baixo em BADGE_TOP_DP —
    // a badge fica no topo, sobrepondo a borda superior do card, e o próprio
    // wrap_content do root já reserva a faixa que ela ocupa.
    val badge = TextView(context)
    badge.id = ID_BADGE
    badge.sizeDp(16f, density)
    badge.typeface = Typeface.DEFAULT_BOLD
    badge.includeFontPadding = false
    badge.setTextColor(COLOR_FOREGROUND)
    badge.setPadding(dp(10f), dp(5f), dp(10f), dp(6f))
    badge.gravity = Gravity.CENTER
    badge.background = pill(accent, 12f * density)
    val badgeWrap = LinearLayout(context)
    badgeWrap.orientation = LinearLayout.HORIZONTAL
    badgeWrap.gravity = Gravity.CENTER_HORIZONTAL
    badgeWrap.addView(badge)

    root.addView(card, FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(BADGE_TOP_DP) })
    // Adicionada por último para ficar por cima do card na sobreposição.
    root.addView(badgeWrap, FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT,
      Gravity.TOP,
    ))
    return root
  }
  private fun applyAccent(container: ViewGroup, context: Context, accent: Int) {
    // A borda mora no `card` interno e a cor da badge precisa acompanhar o
    // tom da oferta.
    val d = currentDensity(context)
    container.findViewById<LinearLayout>(ID_CARD_ROOT)?.background = bordered(accent, d)
    container.findViewById<TextView>(ID_BADGE)?.background = pill(accent, 12f * d)
  }

  private fun applyContent(
    container: ViewGroup,
    metrics: List<RenderedMetric>,
    goals: CardGoals,
    offer: Map<String, Any?>,
  ) {
    metrics.forEachIndexed { index, metric ->
      val tv = container.findViewById<TextView>(ID_METRIC_BASE + index) ?: return@forEachIndexed
      tv.text = metric.label
      tv.setTextColor(colorFor(metric.tone) ?: COLOR_FOREGROUND)
    }

    val minutes = (offer["durationMinutes"] as? Number)?.toDouble()
    val km = (offer["distance"] as? Number)?.toDouble()
    container.findViewById<TextView>(ID_MINUTES)?.text =
      minutes?.let { "${formatInt(it)} min" } ?: "—"
    container.findViewById<TextView>(ID_KM)?.text =
      km?.let { "${formatDecimal(it, 1)} km" } ?: "—"

    val ratingValue = (offer["rating"] as? Number)?.toDouble()
    container.findViewById<TextView>(ID_STAR)?.visibility =
      if (ratingValue != null) android.view.View.VISIBLE else android.view.View.GONE
    container.findViewById<TextView>(ID_RATING)?.let { rating ->
      rating.text = ratingValue?.let { formatDecimal(it, 1) } ?: ""
      // A nota também segue a meta: vermelha abaixo do mínimo, verde acima.
      rating.setTextColor(
        colorFor(goals.toneFor("rating", ratingValue)) ?: COLOR_FOREGROUND,
      )
    }

    // A badge carrega o valor total da corrida, mesmo formato dos mostradores:
    // número puro, sem "R$".
    val fare = (offer["fare"] as? Number)?.toDouble()
    container.findViewById<TextView>(ID_BADGE)?.text =
      fare?.let { formatDecimal(it, 2) } ?: "—"
  }

  /** `green`/`yellow`/`red` viram as cores de destaque; sem meta fica neutro. */
  private fun colorFor(tone: String?): Int? = when (tone) {
    RideCalc.GREEN -> COLOR_PRIMARY
    RideCalc.YELLOW -> COLOR_WARNING
    RideCalc.RED -> COLOR_DESTRUCTIVE
    else -> null
  }

  /** Uma métrica pronta para o card: o texto a exibir e a faixa em que caiu. */
  private data class RenderedMetric(val label: String, val tone: String?)

  /**
   * Valores das métricas na ordem pedida em Ajustes. Sem o símbolo R$ (ex.:
   * "100,00"); a moeda fica implícita e a unidade (/km, /h) no rótulo pequeno
   * da coluna, `metricCardLabel`.
   *
   * As métricas de Ajustes > Metas (ganho/km, ganho/hora, nota) seguem as
   * faixas configuradas. O lucro/h sai do custo por hora de Informações de
   * Custos; o lucro por viagem usa o mesmo alvo por hora, escalado pela duração
   * da oferta.
   */
  private fun renderMetrics(
    order: List<String>,
    offer: Map<String, Any?>,
    goals: CardGoals,
  ): List<RenderedMetric> {
    val minutes = (offer["durationMinutes"] as? Number)?.toDouble()
    return order.map { id ->
      val (value, _) = metricParts(id, offer)
      val tone = if (id == "lucro") {
        goals.toneForLucro(value, minutes)
      } else {
        goals.toneFor(id, value)
      }
      RenderedMetric(label = formatDecimal(value, 2), tone = tone)
    }
  }

  /** Rótulo da coluna no card de fileira, na língua do usuário. */
  private fun metricCardLabel(id: String): String = when (id) {
    "ganhoKm" -> "Valor/km"
    "ganhoHora" -> "Valor/h"
    "lucro" -> "Lucro"
    "lucroHora" -> "Lucro/h"
    else -> id
  }

  /** (valor, sufixo) de uma métrica, mesmas regras de formatMetric do app. */
  private fun metricParts(id: String, offer: Map<String, Any?>): Pair<Double, String> {
    val fare = (offer["fare"] as? Number)?.toDouble() ?: 0.0
    val km = (offer["distance"] as? Number)?.toDouble() ?: 0.0
    val minutes = (offer["durationMinutes"] as? Number)?.toDouble() ?: 0.0
    val costPerKm = (offer["costPerKm"] as? Number)?.toDouble() ?: 0.0
    val hours = minutes / 60.0
    val profit = if (km > 0) fare - costPerKm * km else fare
    return when (id) {
      "ganhoKm" -> (if (km > 0) fare / km else 0.0) to "/km"
      "lucro" -> profit to ""
      "ganhoHora" -> (if (hours > 0) fare / hours else 0.0) to "/h"
      "lucroHora" -> (if (hours > 0) profit / hours else 0.0) to "/h"
      else -> 0.0 to ""
    }
  }

  /** Rótulos das métricas na ordem configurada, para a push refletir o cartão. */
  fun metricLabelsFor(offer: Map<String, Any?>, metricOrder: List<String>): List<String> =
    metricOrder.map { id ->
      val (value, suffix) = metricParts(id, offer)
      money(value) + suffix
    }

  /** Valor da corrida formatado (o que aparece no alto do cartão). */
  fun fareLabel(offer: Map<String, Any?>): String =
    money((offer["fare"] as? Number)?.toDouble() ?: 0.0)

  private fun formatInt(value: Double): String =
    String.format(Locale.US, "%.0f", value)

  private fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

  private fun money(value: Double): String =
    String.format(Locale.US, "R$ %.2f", value)

  /** Raio interno: o externo menos a espessura da borda. */
  private fun innerRadiusPx(density: Float): Float =
    (cornerRadiusPx(density) - px(BORDER_DP, density)).coerceAtLeast(0f)

  /**
   * Fundo com cantos arredondados só de um lado, para as seções internas
   * acompanharem a curva do card sem precisar de `clipToOutline`.
   */
  private fun solid(color: Int, radiusPx: Float, topOnly: Boolean) = GradientDrawable().apply {
    setColor(color)
    if (topOnly) {
      cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
    } else {
      cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radiusPx, radiusPx, radiusPx, radiusPx)
    }
  }

  /** Pílula totalmente arredondada: fundo da badge do valor da corrida. */
  private fun pill(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
  }

  /**
   * Tailwind resolve `text-lg`/`text-xs` em px, mas o React Native multiplica
   * esse valor pelo font scale do aparelho. Aqui o texto precisa usar a mesma
   * escala, senão o card nativo sai menor que a prévia de Ajustes.
   */
  private fun TextView.sizeDp(value: Float, @Suppress("UNUSED_PARAMETER") density: Float) {
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, value)
  }

  private fun divider(context: Context): View {
    val v = View(context)
    v.setBackgroundColor(COLOR_BORDER)
    val density = context.resources.displayMetrics.density
    v.layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      px(DIVIDER_DP, density),
    )
    return v
  }

  private fun dot(context: Context): View {
    val v = View(context)
    v.background = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(COLOR_MUTED)
    }
    val s = (4 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val lp = LinearLayout.LayoutParams(s, s)
    lp.leftMargin = (6 * context.resources.displayMetrics.density).toInt()
    lp.rightMargin = (6 * context.resources.displayMetrics.density).toInt()
    v.layoutParams = lp
    return v
  }

  /** Borda colorida com cantos arredondados, como o `rounded-xl border-4`. */
  private fun bordered(accent: Int, density: Float): GradientDrawable = GradientDrawable().apply {
    cornerRadius = cornerRadiusPx(density)
    setColor(COLOR_CARD)
    // `setStroke` recebe px, então converter truncando deixava a borda com
    // menos px que a prévia.
    setStroke(px(BORDER_DP, density), accent)
  }

  private fun px(dp: Float, density: Float): Int =
    kotlin.math.round(dp * density).toInt().coerceAtLeast(1)

  private fun currentDensity(context: Context): Float =
    context.resources.displayMetrics.density

  // Âncora de tela inteira (fallback): centro na tela inteira ou encostado
  // na borda, sempre com o conteúdo centralizado na vertical.
  // O cartão fica sempre no topo, logo abaixo da área de notificações do
  // Android; a escolha em Ajustes só muda o eixo horizontal.
  private fun gravityFor(position: String): Int = when (position) {
    "direita" -> Gravity.TOP or Gravity.END
    "centro" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    else -> Gravity.TOP or Gravity.START
  }

  // Margem em dp para o cartão não encostar na borda. Com gravidade START um
  // x positivo afasta da esquerda; com END o x negativo afasta da direita.
  private fun horizontalOffset(position: String, dpi: Float): Int = when (position) {
    "esquerda" -> (CARD_MARGIN_DP * dpi).toInt()
    "direita" -> -(CARD_MARGIN_DP * dpi).toInt()
    else -> 0
  }

  /**
   * Distância do topo da tela até o topo do cartão: a altura real da barra de
   * status (lida da janela, para respeitar recorte/cutout) mais uma folga.
   */
  private fun topOffset(container: View, context: Context): Int {
    val insets = container.rootWindowInsets
    val statusBar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    } else {
      @Suppress("DEPRECATION")
      insets?.systemWindowInsetTop ?: 0
    }
    val base = if (statusBar > 0) {
      statusBar
    } else {
      val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
      if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    return base + (CARD_TOP_MARGIN_DP * currentDensity(context)).toInt()
  }

  /** Reaplica Ajustes > Aparência do cartão aos cartões já visíveis. */
  fun refreshAppearance(context: Context) {
    mainHandler.post { reanchorAll(context.applicationContext) }
  }

  // Faixas separadas de propósito: `findViewById` devolve a primeira view da
  // árvore, então os ids da badge e do card não podem cair na faixa dos rótulos
  // das métricas nem na dos valores.
  private const val ID_METRIC_BASE = 0x4b4d0010
  private const val ID_MLABEL_BASE = 0x4b4d0040
  private const val ID_BADGE = 0x4b4d0033
  private const val ID_CARD_ROOT = 0x4b4d0034

  /** Distância do topo do card até o topo da badge: 12dp + ~15dp de sobreposição. */
  private const val BADGE_TOP_DP = 12f
  private const val ID_FACTS = 0x4b4d0020
  private const val ID_MINUTES = 0x4b4d0021
  private const val ID_KM = 0x4b4d0022
  private const val ID_RATING = 0x4b4d0023
  private const val ID_STAR = 0x4b4d0031

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(
      OfferManager.PREFS_NAME,
      Context.MODE_PRIVATE,
    )
}
