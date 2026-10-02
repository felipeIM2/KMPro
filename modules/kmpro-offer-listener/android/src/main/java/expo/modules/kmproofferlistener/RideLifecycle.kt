package expo.modules.kmproofferlistener

/**
 * Ciclo de vida de UMA corrida por app vigiado (Uber/99), com a distinção entre
 * "a mesma corrida ainda está na tela" e "acabou e voltou".
 *
 * Antes o dedup era só um `Map` de assinatura por pacote, populado quando uma
 * oferta aparecia e NUNCA limpo quando a corrida sumia. Duas consequências
 * relatadas em campo:
 *
 * - A corrida saía da tela (motorista aceitou/recusou) e voltava com os mesmos
 *   valores: a assinatura continuava guardada, a nova leitura era classificada
 *   como repetida e o cartão do KMPro NUNCA aparecia.
 * - A corrida continuava na tela além do "Tempo de tela": o cartão sumia e, como
 *   a assinatura era a mesma, não voltava. Agora ele volta — mas só se a corrida
 *   tiver saído de verdade.
 *
 * Então o estado passa a ter ciclo explícito:
 * - `observePresent`: a corrida está na tela agora. Devolve `true` só quando
 *   isso é um **ciclo novo** (a corrida anterior já foi declarada encerrada),
 *   que é o momento em que o cartão pode aparecer de novo.
 * - `observeAbsent`: a corrida não está mais visível. Só declara o fim depois de
 *   [GONE_CONFIRMATIONS] ausências seguidas, para que um único quadro ruim (uma
 *   captura que falhou, um quadro de transição da animação da Uber) não derrube
 *   o cartão à toa.
 *
 * Sem `android.*` de propósito: o módulo roda os testes unitários com
 * `returnDefaultValues = true`, então qualquer coisa que dependa de
 * `android.graphics`/`android.os` voltaria zerada e não teria como ser testada.
 */
object RideLifecycle {
  /**
   * Ausências seguidas para dar a corrida por encerrada. Um é o mínimo para não
   * atrasar o sumiço; dois evita que um quadro isolado derrube o cartão, já que
   * quem responde por esse sinal é o OCR, que pode falhar ou devolver lixo.
   */
  const val GONE_CONFIRMATIONS = 2

  /**
   * Quantas ausências seguidas são ignoradas depois de uma presença. Existe
   * porque as duas fontes têm ritmos diferentes: a varredura da árvore roda a
   * cada ~750 ms e o OCR a cada 1200 ms ou mais. Durante a transição da oferta a
   * árvore pode devolver dois quadros de tela normal seguidos e fechar o ciclo
   * sozinha — o que limparia o dedup, esconderia o cartão e faria o OCR
   * reapresentar a MESMA corrida, que é justamente o "a mesma corrida toca mais
   * de uma vez" relatado em campo. Uma presença do OCR segura o ciclo por
   * [PRESENCE_PIN] observações de ausência.
   */
  const val PRESENCE_PIN = 3

  private class Session {
    /** Capturas consecutivas com a corrida visível. */
    var presentStreak: Int = 0
    /** Capturas consecutivas sem a corrida. */
    var absentStreak: Int = 0
    /** A corrida já foi declarada encerrada (ou nunca apareceu). */
    var gone: Boolean = true
    /** Assinatura exibida nesta corrida; a mesma não pode reexibir o cartão. */
    var shownSignature: String? = null
    /** Ausências ainda ignoradas por causa da última presença confirmada. */
    var presencePin: Int = 0
  }

  private val sessions = mutableMapOf<String, Session>()

  private fun sessionOf(packageName: String): Session =
    sessions.getOrPut(packageName) { Session() }

  /**
   * A corrida deste pacote está na tela. Devolve `true` quando **começou um
   * ciclo novo** (a corrida anterior já tinha sido declarada encerrada), que é a
   * única hora em que um cartão pode aparecer para valores idênticos aos de antes.
   */
  fun observePresent(packageName: String): Boolean {
    if (packageName.isBlank()) return false
    val session = sessionOf(packageName)
    val cycleStarted = session.gone
    session.presentStreak += 1
    session.absentStreak = 0
    session.gone = false
    session.presencePin = PRESENCE_PIN
    return cycleStarted
  }

  /**
   * A corrida deste pacote não está visível. Devolve `true` só na passagem que
   * confirma o fim do ciclo — nesse retorno o chamador deve limpar o dedup, soltar
   * as ofertas pendentes do pacote e esconder o cartão.
   *
   * Ausências logo depois de uma presença são ignoradas (ver [PRESENCE_PIN]),
   * porque a fonte mais rápida (varredura da árvore) pode não enxergar o painel
   * que a fonte mais lenta (OCR) acabou de confirmar.
   */
  fun observeAbsent(packageName: String): Boolean {
    if (packageName.isBlank()) return false
    val session = sessionOf(packageName)
    if (session.presencePin > 0) {
      session.presencePin -= 1
      return false
    }
    session.absentStreak += 1
    session.presentStreak = 0
    if (session.gone || session.absentStreak < GONE_CONFIRMATIONS) return false
    session.gone = true
    session.shownSignature = null
    return true
  }

  /** A corrida continua na tela: nada a limpar, é a mesma corrida. */
  fun isOnScreen(packageName: String): Boolean =
    packageName.isNotBlank() && sessions[packageName]?.gone == false

  /** Assinatura já exibida nesta corrida, ou null se nada foi exibido ainda. */
  fun shownSignature(packageName: String): String? = sessions[packageName]?.shownSignature

  /** Marca que o cartão desta corrida já foi exibido (dedup dentro do ciclo). */
  fun markShown(packageName: String, signature: String) {
    if (packageName.isBlank()) return
    sessionOf(packageName).shownSignature = signature
  }

  /** Esquece o pacote por completo (app fechado, serviço reconectado). */
  fun forget(packageName: String) {
    sessions.remove(packageName)
  }

  fun clear() = sessions.clear()

  /** Estado atual, para os logs de campo. */
  fun describe(packageName: String): String {
    val session = sessions[packageName] ?: return "nenhum"
    return "presente=${session.presentStreak} ausente=${session.absentStreak} encerrada=${session.gone}"
  }
}