package kr.toxicity.hud.shader

/**
 * The dispatch which maps a glyph's element id to the matching `#CreateLayout` case of `text.vsh`.
 *
 * A switch is lowered by some drivers into an if/else chain nested once per case, and one of them
 * refuses more than 64 levels - a pack of about 62 elements reaches that, and the client then drops
 * every resource pack. The cases are laid out here as a balanced tree of `if (id <= mid)` instead:
 * `log2(n)` nested ifs, and as many comparisons per vertex, instead of `n`.
 *
 * A copy of `text.vsh` which was edited by hand predates [MARKER] and keeps its own switch, so the
 * `case` labels are emitted as well.
 */
object ShaderDispatch {
    const val MARKER = "BH_FLAT_LAYOUT"

    fun lines(bodies: List<List<String>>): ArrayList<String> {
        val out = ArrayList<String>(bodies.sumOf { it.size } + bodies.size * 2 + 4)
        if (bodies.isEmpty()) return out
        out.add("#ifdef $MARKER")
        tree(bodies, out)
        out.add("#else")
        bodies.forEachIndexed { index, body ->
            out.add("case ${index + 1}:")
            out.addAll(body)
            out.add("    break;")
        }
        out.add("#endif")
        return out
    }

    private fun tree(bodies: List<List<String>>, out: MutableList<String>) {
        // An id outside of the range would fall into the last leaf.
        out.add("if (id >= 1 && id <= ${bodies.size}) {")

        fun emit(from: Int, to: Int) {
            if (from == to) {
                out.addAll(bodies[from - 1])
                return
            }
            val mid = (from + to) / 2
            out.add("if (id <= $mid) {")
            emit(from, mid)
            out.add("} else {")
            emit(mid + 1, to)
            out.add("}")
        }

        emit(1, bodies.size)
        out.add("}")
    }
}
