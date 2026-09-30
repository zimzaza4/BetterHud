package kr.toxicity.hud.shader

/**
 * The dispatch which maps the element id of a glyph to the matching `#CreateLayout` body of `text.vsh`.
 *
 * BetterHud encodes the id of an element into the ascent of every glyph it generates, and the vertex
 * shader decodes it to apply that element's own transform to the glyph. That has always been a
 * `switch (id) { case 1: ... }`, but a driver may compile a switch any way it likes: NVIDIA's Cg
 * frontend lowers it into an if/else chain **nested once per case**, so the nesting depth of the
 * program grows linearly with the number of elements. That frontend refuses more than 64 nested `if`s
 * ("IF statement nested too deeply"), and - with the two `if`s above the dispatch - a pack breaks as
 * soon as it has about 62 elements: the whole `rendertype_text` program fails to link, the client
 * drops *every* selected resource pack, and the player reports "the resource pack does not load"
 * while seeing no hud at all. Nothing about it is visible on servers whose packs stay below that
 * count, and nothing about it is Minecraft-version specific.
 *
 * The bundled `text.vsh` therefore defines [MARKER] and runs the cases without a switch, and the cases
 * are emitted as a **balanced binary tree**: one `if (id <= mid)` per level narrows a contiguous range
 * of ids, so a leaf needs no comparison of its own and the nesting depth is about `log2(n)` instead of
 * `n` - 9 levels for 255 elements instead of 255. It is cheaper per vertex as well: the chain walks up
 * to `n` comparisons before it reaches the matching case, the tree walks `log2(n)`.
 *
 * A copy of `text.vsh` which somebody edited by hand predates the marker, keeps its own `switch` and
 * still needs `case` labels, so both forms are emitted and the preprocessor picks one.
 *
 * How many elements fit is decided by the encoding rather than by the dispatch: the id travels in
 * `HUD_MAX_BIT` (= 8) bits of the ascent, so at most 255 elements can exist at all.
 */
object ShaderDispatch {
    /**
     * What the bundled `text.vsh` defines when it expects the flat dispatch instead of a switch.
     */
    const val MARKER = "BH_FLAT_LAYOUT"

    /**
     * The lines to inject at `#CreateLayout`, in both forms.
     *
     * @param bodies the body of element `i + 1` at index `i`, in id order
     */
    fun lines(bodies: List<List<String>>): ArrayList<String> {
        val out = ArrayList<String>(bodies.sumOf { it.size } + bodies.size * 2 + 4)
        if (bodies.isEmpty()) return out
        out.add("#ifdef $MARKER")
        tree(bodies, out)
        out.add("#else")
        chain(bodies, out)
        out.add("#endif")
        return out
    }

    /**
     * Emits one `if (id <= mid)` per level, which ends in the body of exactly one id.
     */
    private fun tree(bodies: List<List<String>>, out: MutableList<String>) {
        // The tree partitions a range, so an id outside of it would fall into the last leaf: guard first.
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

    /**
     * Emits the listed cases of a `switch (id)`, for a copy of `text.vsh` which still has one.
     */
    private fun chain(bodies: List<List<String>>, out: MutableList<String>) {
        bodies.forEachIndexed { index, body ->
            out.add("case ${index + 1}:")
            out.addAll(body)
            out.add("    break;")
        }
    }
}
