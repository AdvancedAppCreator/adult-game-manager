package com.example.f95updater

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap

/**
 * Reads RPG Maker VX Ace's concatenated Ruby Marshal 4.8 streams and edits scalar tokens without
 * reserializing the object graph. Keeping every untouched byte intact avoids changing object-link
 * indexes in saves that contain shared or cyclic Ruby objects.
 */
object RubyMarshalSave {
    const val CODEC = "ruby-marshal"

    data class Inspection(
        val values: List<RpgmEditableValue>,
        val summary: String,
        val capped: Boolean,
    )

    data class PatchResult(
        val bytes: ByteArray? = null,
        val displayValue: String? = null,
        val error: String? = null,
    )

    fun isFormat(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 4.toByte() && bytes[1] == 8.toByte()

    fun inspect(bytes: ByteArray, limit: Int): Inspection {
        val document = Parser(bytes).parse()
        val values = linkedMapOf<String, ScalarRef>()
        val seen = IdentityHashMap<Node, Boolean>()
        collect(document.body, "", values, seen, limit)
        val editable = values.map { (path, ref) ->
            val descriptor = describe(path)
            RpgmEditableValue(
                path = path,
                type = ref.type,
                displayValue = display(ref.node),
                name = descriptor.label,
                category = descriptor.category,
            )
        }
        return Inspection(
            values = editable,
            summary = summarize(document),
            capped = editable.size >= limit,
        )
    }

    fun patch(bytes: ByteArray, path: String, newValue: String): PatchResult =
        runCatching {
            val before = Parser(bytes).parse()
            val refs = linkedMapOf<String, ScalarRef>()
            collect(before.body, "", refs, IdentityHashMap(), Int.MAX_VALUE)
            val target = refs[path] ?: return PatchResult(error = "Value no longer exists.")
            val replacement = encodeReplacement(target.node, target.type, newValue)
                ?: return PatchResult(error = "Invalid ${target.type} value.")
            val output = ByteArrayOutputStream(bytes.size - (target.node.end - target.node.start) + replacement.size)
            output.write(bytes, 0, target.node.start)
            output.write(replacement)
            output.write(bytes, target.node.end, bytes.size - target.node.end)
            val patched = output.toByteArray()

            val after = Parser(patched).parse()
            val verified = linkedMapOf<String, ScalarRef>()
            collect(after.body, "", verified, IdentityHashMap(), Int.MAX_VALUE)
            val actual = verified[path] ?: return PatchResult(error = "Edited value could not be read back.")
            val expectedDisplay = displayReplacement(target.type, newValue)
                ?: return PatchResult(error = "Invalid ${target.type} value.")
            if (display(actual.node) != expectedDisplay) {
                return PatchResult(error = "Edited value did not verify after Marshal reparse.")
            }
            PatchResult(bytes = patched, displayValue = expectedDisplay)
        }.getOrElse { t ->
            PatchResult(error = "Could not safely edit this Ruby Marshal save (${t.message ?: "unknown error"}).")
        }

    fun canParse(bytes: ByteArray): Boolean = runCatching { Parser(bytes).parse() }.isSuccess

    private data class Document(val header: Node, val body: Node)
    private data class ScalarRef(val node: Node, val type: String)
    private data class Descriptor(val label: String?, val category: String)

    private class Node(val tag: Char, val start: Int) {
        var end: Int = start
        var scalar: Any? = null
        var raw: ByteArray? = null
        var classNode: Node? = null
        var wrapped: Node? = null
        var reference: Node? = null
        var defaultValue: Node? = null
        val children = mutableListOf<Node>()
        val pairs = mutableListOf<Pair<Node, Node>>()
        val attributes = linkedMapOf<String, Node>()
    }

    private class Parser(private val bytes: ByteArray) {
        private var position = 0
        private val symbols = mutableListOf<Node>()
        private val objects = mutableListOf<Node>()

        fun parse(): Document {
            val header = parseRoot()
            if (position >= bytes.size) throw IllegalArgumentException("VX Ace save is missing its contents stream")
            val body = parseRoot()
            if (position != bytes.size) throw IllegalArgumentException("Unexpected trailing data at byte $position")
            return Document(header, body)
        }

        private fun parseRoot(): Node {
            if (readByte() != 4 || readByte() != 8) {
                throw IllegalArgumentException("Unsupported Ruby Marshal version")
            }
            symbols.clear()
            objects.clear()
            return readNode()
        }

        private fun readNode(): Node {
            val start = position
            val tag = readByte().toChar()
            val node = Node(tag, start)
            if (tag in REGISTERED_TAGS) objects += node
            when (tag) {
                '0' -> node.scalar = null
                'T' -> node.scalar = true
                'F' -> node.scalar = false
                'i' -> node.scalar = readLong().toLong()
                'l' -> node.scalar = readBigInteger()
                'f' -> node.scalar = readBlob().toString(StandardCharsets.US_ASCII).toDouble()
                '"' -> node.raw = readBlob()
                ':' -> {
                    node.scalar = readBlob().toString(StandardCharsets.UTF_8)
                    symbols += node
                }
                ';' -> node.reference = symbols.getOrNull(readLongChecked())
                    ?: throw IllegalArgumentException("Invalid symbol link at byte $start")
                '@' -> node.reference = objects.getOrNull(readLongChecked())
                    ?: throw IllegalArgumentException("Invalid object link at byte $start")
                '[' -> repeat(readLongChecked()) { node.children += readNode() }
                '{', '}' -> {
                    repeat(readLongChecked()) { node.pairs += readNode() to readNode() }
                    if (tag == '}') node.defaultValue = readNode()
                }
                'o' -> {
                    node.classNode = readNode()
                    readAttributes(node)
                }
                'I' -> {
                    node.wrapped = readNode()
                    readAttributes(node)
                }
                '/' -> {
                    node.raw = readBlob()
                    readByte()
                }
                'S' -> {
                    node.classNode = readNode()
                    readAttributes(node)
                }
                'c', 'm', 'M' -> node.raw = readBlob()
                'u' -> {
                    node.classNode = readNode()
                    node.raw = readBlob()
                }
                'U' -> {
                    node.classNode = readNode()
                    node.wrapped = readNode()
                    objects += node
                }
                'd', 'e', 'C' -> {
                    node.classNode = readNode()
                    node.wrapped = readNode()
                }
                else -> throw IllegalArgumentException("Unsupported Marshal token '$tag' at byte $start")
            }
            node.end = position
            return node
        }

        private fun readAttributes(node: Node) {
            repeat(readLongChecked()) {
                val name = symbolName(readNode())
                    ?: throw IllegalArgumentException("Invalid attribute name at byte $position")
                node.attributes[name] = readNode()
            }
        }

        private fun readBigInteger(): BigInteger {
            val negative = readByte().toChar() == '-'
            val words = readLongChecked()
            var value = BigInteger.ZERO
            repeat(words) { index ->
                val word = readByte() or (readByte() shl 8)
                value = value.or(BigInteger.valueOf(word.toLong()).shiftLeft(index * 16))
            }
            return if (negative) value.negate() else value
        }

        private fun readBlob(): ByteArray {
            val length = readLongChecked()
            ensureAvailable(length)
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        private fun readLongChecked(): Int {
            val value = readLong()
            if (value < 0 || value > Int.MAX_VALUE) {
                throw IllegalArgumentException("Invalid Marshal collection length $value")
            }
            return value.toInt()
        }

        private fun readLong(): Long {
            var length = readByte()
            if (length >= 128) length -= 256
            if (length == 0) return 0
            if (length in 6..127) return (length - 5).toLong()
            if (length in -128..-6) return (length + 5).toLong()
            val byteCount = kotlin.math.abs(length)
            if (byteCount > 8) throw IllegalArgumentException("Marshal integer is too wide")
            var value = 0L
            repeat(byteCount) { index -> value = value or (readByte().toLong() shl (index * 8)) }
            if (length < 0 && byteCount < 8) value -= 1L shl (byteCount * 8)
            return value
        }

        private fun readByte(): Int {
            ensureAvailable(1)
            return bytes[position++].toInt() and 0xff
        }

        private fun ensureAvailable(count: Int) {
            if (count < 0 || position + count > bytes.size) {
                throw IllegalArgumentException("Truncated Marshal data at byte $position")
            }
        }
    }

    private fun collect(
        source: Node,
        path: String,
        out: MutableMap<String, ScalarRef>,
        seen: IdentityHashMap<Node, Boolean>,
        limit: Int,
    ) {
        if (out.size >= limit) return
        val node = dereference(source)
        when (node.tag) {
            'T', 'F' -> addScalar(path, node, "bool", out)
            'i', 'l' -> addScalar(path, node, "int", out)
            'f' -> addScalar(path, node, "float", out)
            '"' -> addScalar(path, node, "string", out)
            'I', 'e', 'C', 'U', 'd' -> node.wrapped?.let { collect(it, path, out, seen, limit) }
            '[' -> {
                if (seen.put(node, true) != null) return
                node.children.forEachIndexed { index, child ->
                    collect(child, "$path[$index]", out, seen, limit)
                }
            }
            '{', '}' -> {
                if (seen.put(node, true) != null) return
                node.pairs.forEach { (key, value) ->
                    val keyText = keyText(key)
                    val childPath = if (path.isBlank() && isIdentifier(keyText)) keyText else "$path[$keyText]"
                    collect(value, childPath, out, seen, limit)
                }
            }
            'o', 'S' -> {
                if (seen.put(node, true) != null) return
                node.attributes.forEach { (name, value) ->
                    val normalized = name.replaceFirst("@", "_")
                    val childPath = if (path.isBlank()) normalized else "$path.$normalized"
                    collect(value, childPath, out, seen, limit)
                }
            }
        }
    }

    private fun addScalar(path: String, node: Node, type: String, out: MutableMap<String, ScalarRef>) {
        if (path.isNotBlank()) out.putIfAbsent(path, ScalarRef(node, type))
    }

    private fun dereference(source: Node): Node {
        var node = source
        val seen = IdentityHashMap<Node, Boolean>()
        while (node.tag == '@' || node.tag == ';') {
            if (seen.put(node, true) != null) break
            node = node.reference ?: break
        }
        return node
    }

    private fun symbolName(source: Node): String? {
        var node = dereference(source)
        if (node.tag == 'I') node = node.wrapped?.let(::dereference) ?: node
        return node.scalar as? String
    }

    private fun keyText(source: Node): String {
        val node = dereference(source)
        return when (node.tag) {
            ':', '"' -> quoteIfNeeded(symbolName(node) ?: decodeString(node))
            'i', 'l', 'f' -> display(node)
            'T', 'F' -> display(node)
            '[' -> node.children.joinToString(separator = ",", prefix = "[", postfix = "]") { keyText(it) }
            else -> "\"${node.tag}@${node.start}\""
        }
    }

    private fun quoteIfNeeded(value: String): String =
        if (isIdentifier(value)) value else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun isIdentifier(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' }

    private fun display(node: Node): String = when (node.tag) {
        'T' -> "true"
        'F' -> "false"
        'i', 'l', 'f' -> node.scalar.toString()
        '"' -> decodeString(node)
        else -> ""
    }

    private fun decodeString(source: Node): String {
        var node = dereference(source)
        if (node.tag == 'I') node = node.wrapped?.let(::dereference) ?: node
        return (node.raw ?: ByteArray(0)).toString(StandardCharsets.UTF_8)
    }

    private fun describe(path: String): Descriptor {
        val variable = Regex("""^variables\._data\[(\d+)]$""").matchEntire(path)
        if (variable != null) return Descriptor("Variable #${variable.groupValues[1]}", RpgmCategory.VARIABLES)
        val switch = Regex("""^switches\._data\[(\d+)]$""").matchEntire(path)
        if (switch != null) return Descriptor("Switch #${switch.groupValues[1]}", RpgmCategory.SWITCHES)
        val selfSwitch = Regex("""^self_switches\._data\[\[(\d+),(\d+),""?([^]"]+)""?]]$""").matchEntire(path)
        if (selfSwitch != null) {
            return Descriptor(
                "Self switch · map ${selfSwitch.groupValues[1]} · event ${selfSwitch.groupValues[2]} · ${selfSwitch.groupValues[3]}",
                RpgmCategory.SWITCHES,
            )
        }
        if (path == "party._gold") return Descriptor("Gold", RpgmCategory.PARTY)
        if (path == "party._steps") return Descriptor("Steps", RpgmCategory.PARTY)
        Regex("""^party\._(items|weapons|armors)\[(\d+)]$""").matchEntire(path)?.let { match ->
            val label = when (match.groupValues[1]) {
                "items" -> "Item"
                "weapons" -> "Weapon"
                else -> "Armor"
            }
            return Descriptor("$label #${match.groupValues[2]} (qty)", RpgmCategory.INVENTORY)
        }
        Regex("""^actors\._data\[(\d+)]\.(.+)$""").matchEntire(path)?.let { match ->
            val field = actorFieldLabel(match.groupValues[2])
            return Descriptor("Actor #${match.groupValues[1]} · $field", RpgmCategory.ACTORS)
        }
        if (path == "map._map_id") return Descriptor("Map ID", RpgmCategory.OTHER)
        if (path == "player._x") return Descriptor("Player X", RpgmCategory.OTHER)
        if (path == "player._y") return Descriptor("Player Y", RpgmCategory.OTHER)
        return Descriptor(null, RpgmCategory.OTHER)
    }

    private fun actorFieldLabel(value: String): String = when {
        value == "_name" -> "Name"
        value == "_nickname" -> "Nickname"
        value == "_level" -> "Level"
        value == "_hp" -> "HP"
        value == "_mp" -> "MP"
        value == "_tp" -> "TP"
        value == "_class_id" -> "Class"
        value.startsWith("_exp") -> "EXP"
        else -> value.trimStart('_').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun summarize(document: Document): String {
        val parts = mutableListOf<String>()
        hashValue(document.header, "characters")
            ?.let(::dereference)
            ?.children
            ?.firstOrNull()
            ?.let(::dereference)
            ?.children
            ?.firstOrNull()
            ?.let(::dereference)
            ?.let(::decodeString)
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)
        hashValue(document.header, "playtime_s")
            ?.let(::dereference)
            ?.let(::decodeString)
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)
        if (parts.isEmpty()) {
            val values = linkedMapOf<String, ScalarRef>()
            collect(document.body, "", values, IdentityHashMap(), 100)
            values["party._gold"]?.let { parts += "gold ${display(it.node)}" }
            values["map._map_id"]?.let { parts += "map ${display(it.node)}" }
        }
        return parts.ifEmpty { listOf("Ruby Marshal VX Ace save") }.joinToString(" • ")
    }

    private fun hashValue(source: Node, key: String): Node? {
        val node = dereference(source)
        if (node.tag != '{' && node.tag != '}') return null
        return node.pairs.firstOrNull { (candidate, _) -> symbolName(candidate) == key }?.second
    }

    private fun encodeReplacement(node: Node, type: String, value: String): ByteArray? = when (type) {
        "bool" -> parseBoolean(value)?.let { byteArrayOf(if (it) 'T'.code.toByte() else 'F'.code.toByte()) }
        "int" -> value.trim().toBigIntegerOrNull()?.let { number ->
            when (node.tag) {
                'i' -> number.takeIf {
                    it >= BigInteger.valueOf(Int.MIN_VALUE.toLong()) && it <= BigInteger.valueOf(Int.MAX_VALUE.toLong())
                }?.let { byteArrayOf('i'.code.toByte()) + encodeLong(it.toLong()) }
                'l' -> encodeBigInteger(number)
                else -> null
            }
        }
        "float" -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { number ->
            byteArrayOf('f'.code.toByte()) + encodeBlob(number.toString().toByteArray(StandardCharsets.US_ASCII))
        }
        "string" -> byteArrayOf('"'.code.toByte()) + encodeBlob(value.toByteArray(StandardCharsets.UTF_8))
        else -> null
    }

    private fun displayReplacement(type: String, value: String): String? = when (type) {
        "bool" -> parseBoolean(value)?.toString()
        "int" -> value.trim().toBigIntegerOrNull()?.toString()
        "float" -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toString()
        "string" -> value
        else -> null
    }

    private fun parseBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    private fun encodeBigInteger(value: BigInteger): ByteArray {
        val absolute = value.abs()
        val words = if (absolute == BigInteger.ZERO) 0 else (absolute.bitLength() + 15) / 16
        val out = ByteArrayOutputStream()
        out.write('l'.code)
        out.write(if (value.signum() < 0) '-'.code else '+'.code)
        out.write(encodeLong(words.toLong()))
        repeat(words) { index ->
            val word = absolute.shiftRight(index * 16).and(BigInteger.valueOf(0xffff)).toInt()
            out.write(word and 0xff)
            out.write((word ushr 8) and 0xff)
        }
        return out.toByteArray()
    }

    private fun encodeBlob(bytes: ByteArray): ByteArray = encodeLong(bytes.size.toLong()) + bytes

    private fun encodeLong(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        if (value in 1..122) return byteArrayOf((value + 5).toByte())
        if (value in -123..-1) return byteArrayOf((value - 5).toByte())
        var byteCount = 1
        while (byteCount < 4) {
            val min = -(1L shl (byteCount * 8 - 1))
            val max = (1L shl (byteCount * 8 - 1)) - 1
            if (value in min..max) break
            byteCount++
        }
        val out = ByteArrayOutputStream(byteCount + 1)
        out.write(if (value < 0) (-byteCount) and 0xff else byteCount)
        repeat(byteCount) { index -> out.write((value shr (index * 8)).toInt() and 0xff) }
        return out.toByteArray()
    }

    private val REGISTERED_TAGS = setOf('"', '/', '[', '{', '}', 'o', 'f', 'l', 'S', 'c', 'm', 'M', 'u', 'd')
}
