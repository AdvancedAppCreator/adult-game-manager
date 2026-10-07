package com.example.f95updater

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class SourceCatalogEnvelopeStreamResult(
    val schemaVersion: Int,
    val generatedAt: String,
    val source: String,
    val declaredCount: Int,
    val emittedCount: Int,
)

internal class SourceCatalogEnvelopeStreamException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Decodes a source-catalog envelope without ever decoding its `entries` array as a collection.
 * Each entry object is bounded and decoded separately, so the peak entry payload is one object.
 */
internal object SourceCatalogEnvelopeStreamReader {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun read(
        file: File,
        onEntry: (SourceCatalogEntry) -> Unit,
        checkCancelled: () -> Unit = {},
    ): SourceCatalogEnvelopeStreamResult = file.inputStream().use { input ->
        read(input, onEntry, checkCancelled)
    }

    fun read(
        input: InputStream,
        onEntry: (SourceCatalogEntry) -> Unit,
        checkCancelled: () -> Unit = {},
    ): SourceCatalogEnvelopeStreamResult {
        val buffered = BufferedInputStream(input)
        val payload = try {
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            if (first == GZIP_MAGIC_FIRST && second == GZIP_MAGIC_SECOND) {
                GZIPInputStream(buffered)
            } else {
                buffered
            }
        } catch (error: IOException) {
            throw malformed("could not read gzip header", error)
        }
        return try {
            InputStreamReader(
                payload,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT),
            ).use { reader ->
                Parser(reader, onEntry, checkCancelled).readEnvelope()
            }
        } catch (error: SourceCatalogEnvelopeStreamException) {
            throw error
        } catch (error: IOException) {
            throw malformed("invalid UTF-8 or truncated compressed payload", error)
        }
    }

    private class Parser(
        private val reader: InputStreamReader,
        private val onEntry: (SourceCatalogEntry) -> Unit,
        private val checkCancelled: () -> Unit,
    ) {
        private var pushedBack = NO_CHARACTER
        private var skippedCount = 0

        fun readEnvelope(): SourceCatalogEnvelopeStreamResult {
            expect('{', "top-level object")
            var schemaVersion: Int? = null
            var generatedAt: String? = null
            var source: String? = null
            var declaredCount: Int? = null
            var entriesSeen = false
            var emittedCount = 0
            var next = nextNonWhitespace("top-level property or closing brace")
            while (next != '}') {
                if (next != '"') throw malformed("expected a top-level property name")
                unread(next)
                val key = decodeString(readValue(), "top-level property name")
                expect(':', "colon after '$key'")
                if (key == "entries") {
                    if (entriesSeen) throw malformed("duplicate top-level 'entries' array")
                    entriesSeen = true
                    emittedCount = readEntries()
                } else {
                    val value = readValue()
                    when (key) {
                        "schemaVersion" -> schemaVersion = decodeInt(value, key)
                        "generatedAt" -> generatedAt = decodeString(value, key)
                        "source" -> source = decodeString(value, key)
                        "count" -> declaredCount = decodeInt(value, key)
                        else -> validateJsonValue(value, key)
                    }
                }
                next = nextNonWhitespace("comma or closing top-level brace")
                if (next == '}') break
                if (next != ',') throw malformed("expected ',' or '}' after top-level property")
                next = nextNonWhitespace("top-level property name")
            }
            while (true) {
                val trailing = readCharacter()
                if (trailing == END_OF_STREAM) break
                if (!trailing.toChar().isWhitespace()) {
                    throw malformed("trailing content after top-level object")
                }
            }

            val count = declaredCount ?: throw malformed("missing top-level 'count'")
            if (count < 0) throw malformed("top-level 'count' must not be negative")
            if (!entriesSeen) throw malformed("missing top-level 'entries' array")
            if (count != emittedCount + skippedCount) {
                throw malformed(
                    "top-level count $count does not match ${emittedCount + skippedCount} entries",
                )
            }
            if (skippedCount > 0) {
                AppLog.w(
                    "Catalog",
                    "Parsed source catalog with $skippedCount malformed entr" +
                        "${if (skippedCount == 1) "y" else "ies"} skipped ($emittedCount kept)",
                )
            }
            return SourceCatalogEnvelopeStreamResult(
                schemaVersion = schemaVersion ?: 1,
                generatedAt = generatedAt ?: throw malformed("missing top-level 'generatedAt'"),
                source = source ?: throw malformed("missing top-level 'source'"),
                declaredCount = count,
                emittedCount = emittedCount,
            )
        }

        private fun readEntries(): Int {
            expect('[', "start of 'entries' array")
            var emitted = 0
            var next = nextNonWhitespace("entry object or closing bracket")
            if (next == ']') return emitted
            while (true) {
                checkCancelled()
                if (next != '{') throw malformed("each 'entries' item must be an object")
                unread(next)
                val rawEntry = readValue(validateComposite = false)
                val entry = try {
                    json.decodeFromString<SourceCatalogEntry>(rawEntry)
                } catch (error: Exception) {
                    // Tolerate individual malformed entries: skipping one bad record is far
                    // better than discarding the entire catalog. It is still counted so the
                    // top-level count check keeps detecting genuine truncation.
                    skippedCount++
                    if (skippedCount <= MAX_SKIP_LOGS) {
                        AppLog.w(
                            "Catalog",
                            "Skipping malformed catalog entry at index " +
                                "${emitted + skippedCount - 1}: ${error.message}",
                        )
                    }
                    null
                }
                checkCancelled()
                if (entry != null) {
                    onEntry(entry)
                    emitted++
                }
                next = nextNonWhitespace("comma or closing 'entries' bracket")
                if (next == ']') return emitted
                if (next != ',') throw malformed("expected ',' or ']' after entry ${emitted + skippedCount}")
                next = nextNonWhitespace("entry object")
            }
        }

        private fun readValue(validateComposite: Boolean = true): String {
            val first = nextNonWhitespace("JSON value")
            val value = StringBuilder().append(first)
            when (first) {
                '"' -> readStringContents(value)
                '{', '[' -> readCompositeContents(first, value, validateComposite)
                else -> readPrimitiveContents(first, value)
            }
            return value.toString()
        }

        private fun readCompositeContents(
            first: Char,
            value: StringBuilder,
            validate: Boolean,
        ) {
            val closings = ArrayDeque<Char>()
            closings.addLast(if (first == '{') '}' else ']')
            while (closings.isNotEmpty()) {
                val character = requireCharacter("JSON value")
                value.append(character)
                when (character) {
                    '"' -> readStringContents(value)
                    '{' -> closings.addLast('}')
                    '[' -> closings.addLast(']')
                    '}', ']' -> {
                        val expected = closings.removeLast()
                        if (character != expected) {
                            throw malformed("mismatched '$character' in JSON value; expected '$expected'")
                        }
                    }
                }
            }
            if (validate) validateJsonValue(value.toString(), "top-level value")
        }

        private fun readStringContents(value: StringBuilder) {
            while (true) {
                val character = requireCharacter("JSON string")
                value.append(character)
                when (character) {
                    '"' -> return
                    '\\' -> {
                        val escaped = requireCharacter("JSON escape")
                        value.append(escaped)
                        if (escaped !in JSON_ESCAPES) throw malformed("invalid JSON escape '\\$escaped'")
                        if (escaped == 'u') {
                            repeat(4) {
                                val hex = requireCharacter("unicode escape")
                                value.append(hex)
                                if (hex.digitToIntOrNull(16) == null) {
                                    throw malformed("invalid unicode escape")
                                }
                            }
                        }
                    }
                    else -> if (character.code < 0x20) throw malformed("control character in JSON string")
                }
            }
        }

        private fun readPrimitiveContents(first: Char, value: StringBuilder) {
            if (first in "{}[],:") throw malformed("unexpected '$first' where a JSON value was required")
            while (true) {
                val character = readCharacter()
                if (character == END_OF_STREAM) break
                val decoded = character.toChar()
                if (decoded.isWhitespace()) break
                if (decoded == ',' || decoded == ']' || decoded == '}') {
                    unread(decoded)
                    break
                }
                value.append(decoded)
            }
            if (!JSON_NUMBER.matches(value) && value.toString() !in JSON_LITERALS) {
                throw malformed("invalid JSON primitive '${value}'")
            }
        }

        private fun decodeString(raw: String, field: String): String = try {
            json.decodeFromString(raw)
        } catch (error: Exception) {
            throw malformed("top-level '$field' must be a JSON string", error)
        }

        private fun decodeInt(raw: String, field: String): Int = try {
            json.decodeFromString(raw)
        } catch (error: Exception) {
            throw malformed("top-level '$field' must be an integer", error)
        }

        private fun validateJsonValue(raw: String, field: String) {
            try {
                json.parseToJsonElement(raw)
            } catch (error: Exception) {
                throw malformed("invalid $field", error)
            }
        }

        private fun expect(expected: Char, context: String) {
            val actual = nextNonWhitespace(context)
            if (actual != expected) throw malformed("expected '$expected' for $context, found '$actual'")
        }

        private fun nextNonWhitespace(context: String): Char {
            while (true) {
                checkCancelled()
                val character = requireCharacter(context)
                if (!character.isWhitespace()) return character
            }
        }

        private fun requireCharacter(context: String): Char {
            val character = readCharacter()
            if (character == END_OF_STREAM) throw malformed("unexpected end of input while reading $context")
            return character.toChar()
        }

        private fun readCharacter(): Int {
            checkCancelled()
            if (pushedBack != NO_CHARACTER) {
                return pushedBack.also { pushedBack = NO_CHARACTER }
            }
            return reader.read()
        }

        private fun unread(character: Char) {
            check(pushedBack == NO_CHARACTER) { "Source catalog parser pushback overflow" }
            pushedBack = character.code
        }
    }

    private fun malformed(message: String, cause: Throwable? = null): SourceCatalogEnvelopeStreamException =
        SourceCatalogEnvelopeStreamException("Malformed SourceCatalogEnvelope: $message", cause)

    private const val GZIP_MAGIC_FIRST = 0x1f
    private const val GZIP_MAGIC_SECOND = 0x8b
    private const val NO_CHARACTER = -2
    private const val END_OF_STREAM = -1
    private const val MAX_SKIP_LOGS = 20
    private const val JSON_ESCAPES = "\"\\/bfnrtu"
    private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
    private val JSON_LITERALS = setOf("true", "false", "null")
}
