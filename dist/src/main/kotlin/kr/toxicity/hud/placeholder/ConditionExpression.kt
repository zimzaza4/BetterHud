package kr.toxicity.hud.placeholder

import kr.toxicity.hud.manager.PlaceholderManagerImpl
import java.util.concurrent.ConcurrentHashMap

object ConditionExpression {

    private val cache = ConcurrentHashMap<String, Node>()

    fun parse(text: String, source: PlaceholderSource): ConditionBuilder = compile(node(text), source)

    internal fun node(text: String): Node = cache.computeIfAbsent(text) {
        Parser(it).parse()
    }

    internal fun compile(node: Node, source: PlaceholderSource): ConditionBuilder = when (node) {
        is Node.Value -> Conditions.hold(node.text, find(node.text, source))
        is Node.Compare -> Conditions.compare(find(node.first.text, source), find(node.last.text, source), node.operation)
        is Node.Negate -> compile(node.node, source).not()
        is Node.Both -> node.nodes.fold(ConditionBuilder.alwaysTrue) { builder, child ->
            builder and compile(child, source)
        }
        is Node.Either -> node.nodes.drop(1).fold(compile(node.nodes.first(), source)) { builder, child ->
            builder or compile(child, source)
        }
        is Node.Among -> {
            val first = find(node.first.text, source)
            node.values.drop(1).fold(Conditions.compare(first, find(node.values.first().text, source), "==")) { builder, value ->
                builder or Conditions.compare(first, find(value.text, source), "==")
            }
        }
    }

    private fun find(text: String, source: PlaceholderSource) = PlaceholderManagerImpl.find(text, source)

    internal sealed interface Node {
        data class Value(val text: String) : Node
        data class Compare(val first: Value, val operation: String, val last: Value) : Node
        data class Negate(val node: Node) : Node
        data class Both(val nodes: List<Node>) : Node
        data class Either(val nodes: List<Node>) : Node
        data class Among(val first: Value, val values: List<Value>) : Node
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Node {
            val node = or()
            skipSpace()
            if (index < text.length) throw RuntimeException("unexpected token: ${text.substring(index)} in '$text'")
            return node
        }

        private fun or(): Node {
            val nodes = mutableListOf(and())
            while (consume("||")) {
                nodes += and()
            }
            return if (nodes.size == 1) nodes.first() else Node.Either(nodes)
        }

        private fun and(): Node {
            val nodes = mutableListOf(unary())
            while (consume("&&")) {
                nodes += unary()
            }
            return if (nodes.size == 1) nodes.first() else Node.Both(nodes)
        }

        private fun unary(): Node {
            skipSpace()
            if (text.startsWith("!", index) && !text.startsWith("!=", index)) {
                index++
                return Node.Negate(unary())
            }
            return primary()
        }

        private fun primary(): Node {
            if (!typePrefixed() && consume("(")) {
                val node = or()
                expect(")")
                return node
            }
            val first = value()
            val operation = operation() ?: return first
            if (operation == "in") {
                expect("(")
                val values = mutableListOf(value())
                while (consume(",")) {
                    values += value()
                }
                expect(")")
                return Node.Among(first, values)
            }
            return Node.Compare(first, operation, value())
        }

        private fun operation(): String? {
            skipSpace()
            arrayOf("==", "!=", ">=", "<=", ">", "<").forEach {
                if (consume(it)) return it
            }
            if (text.startsWith("in", index)) {
                val next = index + 2
                if (next >= text.length || !text[next].isLetterOrDigit() && text[next] != '_') {
                    index = next
                    return "in"
                }
            }
            return null
        }

        private fun value(): Node.Value {
            skipSpace()
            if (index >= text.length) throw RuntimeException("value not set in '$text'")
            val start = index
            if (text[index] == '\'') {
                index++
                while (index < text.length && text[index] != '\'') {
                    index++
                }
                if (index >= text.length) throw RuntimeException("unterminated string in '$text'")
                return Node.Value(text.substring(start, ++index))
            }
            if (text[index] == '[') {
                while (index < text.length && text[index] != ']') {
                    index++
                }
                if (index >= text.length) throw RuntimeException("unterminated reference in '$text'")
                return Node.Value(text.substring(start, ++index))
            }
            if (typePrefixed()) {
                index = text.indexOf(')', index) + 1
            }
            while (index < text.length && !text[index].isDelimiter()) {
                index++
            }
            if (start == index) throw RuntimeException("value not set in '$text'")
            return Node.Value(text.substring(start, index))
        }

        private fun typePrefixed(): Boolean {
            if (index >= text.length || text[index] != '(') return false
            val close = text.indexOf(')', index)
            if (close <= index) return false
            val inner = text.substring(index + 1, close)
            val next = close + 1
            return inner.isNotEmpty() && inner.all { it.isLetterOrDigit() || it == '_' } && next < text.length && !text[next].isWhitespace() && !text[next].isDelimiter()
        }

        private fun Char.isDelimiter(): Boolean = isWhitespace() || this in "!,&|=<>()"

        private fun consume(token: String): Boolean {
            skipSpace()
            if (!text.startsWith(token, index)) return false
            index += token.length
            return true
        }

        private fun expect(token: String) {
            if (!consume(token)) throw RuntimeException("'$token' expected in '$text'")
        }

        private fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }
    }
}
