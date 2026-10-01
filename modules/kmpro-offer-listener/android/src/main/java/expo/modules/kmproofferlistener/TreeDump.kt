package expo.modules.kmproofferlistener

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Diagnostic helpers that walk the AccessibilityNodeInfo tree exposed by the
 * app in the active window.
 *
 * The Uber Driver app frequently exposes its data through `contentDescription`
 * (not only `text`) and through child nodes several levels deep, so the tree
 * is collected recursively and both attributes are captured.
 *
 * Used for two purposes:
 *  1. Diagnostic: dumping what the accessibility tree really contains.
 *  2. Capture: building the normalized text feed that feeds [OfferParser].
 */
object TreeDump {
  const val MAX_DEPTH = 32
  const val MAX_NODES = 300

  data class NodeSummary(
    val className: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val childCount: Int,
    val visible: Boolean,
  )

  /** Full recursive walk. Returns at most [MAX_NODES] nodes to bound cost. */
  fun collect(node: AccessibilityNodeInfo?): List<NodeSummary> {
    val out = mutableListOf<NodeSummary>()
    walk(node, out, 0)
    return out
  }

  private fun walk(
    node: AccessibilityNodeInfo?,
    out: MutableList<NodeSummary>,
    depth: Int,
  ) {
    if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
    out += NodeSummary(
      className = node.className?.toString().orEmpty(),
      text = node.text?.toString().orEmpty(),
      contentDescription = node.contentDescription?.toString().orEmpty(),
      viewId = node.viewIdResourceName.orEmpty(),
      childCount = node.childCount,
      visible = node.isVisibleToUser,
    )
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      walk(child, out, depth + 1)
    }
  }

  /**
   * All user-visible strings in the tree: `text` and `contentDescription`,
   * excluding editable fields.
   */
  fun allText(node: AccessibilityNodeInfo?): List<String> {
    val out = mutableListOf<String>()

    fun visit(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > MAX_DEPTH) return
      if (n.isVisibleToUser) {
        val className = n.className?.toString().orEmpty()
        val text = n.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && !className.contains("EditText")) out.add(text)
        val cd = n.contentDescription?.toString()?.trim().orEmpty()
        if (cd.isNotEmpty()) out.add(cd)
      }
      for (i in 0 until n.childCount) {
        val child = n.getChild(i) ?: continue
        visit(child, depth + 1)
      }
    }

    visit(node, 0)
    return out
  }

  /**
   * Same as [allText] but WITHOUT the `isVisibleToUser` filter. Some offer panels
   * keep their content on nodes reported as off-screen/invisible; this captures
   * those strings so the parser can inspect them.
   */
  fun allTextUnfiltered(node: AccessibilityNodeInfo?): List<String> {
    val out = mutableListOf<String>()

    fun visit(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > MAX_DEPTH) return
      val className = n.className?.toString().orEmpty()
      val text = n.text?.toString()?.trim().orEmpty()
      if (text.isNotEmpty() && !className.contains("EditText")) out.add(text)
      val cd = n.contentDescription?.toString()?.trim().orEmpty()
      if (cd.isNotEmpty()) out.add(cd)
      for (i in 0 until n.childCount) {
        val child = n.getChild(i) ?: continue
        visit(child, depth + 1)
      }
    }

    visit(node, 0)
    return out
  }

  /**
   * Text mining through `findAccessibilityNodeInfosByText`. This query API
   * traverses INTO virtual subtrees (AccessibilityNodeProvider, used by
   * Compose/Flutter-ish panels) that the plain `getChild` walk never reaches.
   * Only runs on "muted" windows so home-screen banners aren't mined.
   */
  fun search(node: AccessibilityNodeInfo?, vararg tokens: String): List<String> {
    if (node == null) return emptyList()
    val out = mutableListOf<String>()
    for (token in tokens) {
      runCatching {
        node.findAccessibilityNodeInfosByText(token).forEach { hit ->
          hit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
          hit.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(
            out::add,
          )
          hit.recycle()
        }
      }
    }
    return out.distinct()
  }

  /** Compact one-line snapshot of the first [limit] nodes (diagnostic only). */
  fun describe(root: AccessibilityNodeInfo?, limit: Int = 8): List<String> {
    if (root == null) return emptyList()
    val out = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    fun visit(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || out.size >= limit) return
      runCatching {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = n.text?.toString()?.replace('\n', ' ')?.take(18) ?: ""
        val cd = n.contentDescription?.toString()?.replace('\n', ' ')?.take(18) ?: ""
        val line = "$cls vis=${n.isVisibleToUser} children=${n.childCount} t='$txt' cd='$cd'"
        if (seen.add(line)) out += line
      }
      for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
    }
    visit(root, 0)
    return out
  }
}