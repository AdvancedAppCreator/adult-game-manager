package com.example.f95updater

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object CatalogTitleTranslator {
    private const val NO_TRANSLATION = "\u0000"
    private const val CACHE_LIMIT = 100_000
    private const val MEMORY_CACHE_LIMIT = 20_000
    private const val CACHE_VERSION = 1
    private const val MODEL_RETRY_DELAY_MS = 5 * 60 * 1000L
    private const val DETECTION_TIMEOUT_MS = 15_000L
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 3 * 60 * 1000L
    private const val TRANSLATION_TIMEOUT_MS = 30_000L
    private const val MODEL_PREFS = "catalog_translation_models"
    private const val MODEL_PAIRS_KEY = "ready_pairs"

    private val memoryCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MEMORY_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MEMORY_CACHE_LIMIT
        },
    )
    private val translators = ConcurrentHashMap<String, Translator>()
    private val translationSlots = Semaphore(2)
    private val foregroundRequests = AtomicInteger()
    private val titleLocks = Array(64) { Mutex() }
    private val modelLocks = Array(16) { Mutex() }
    private val readyModels = ConcurrentHashMap.newKeySet<String>()
    private val modelFailures = ConcurrentHashMap<String, ModelFailure>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private val remoteModelManager by lazy { RemoteModelManager.getInstance() }
    private val modelPrefsLock = Any()

    @Volatile
    private var cacheDb: TranslationCacheDb? = null

    fun supportedTargetLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    fun languageDisplayName(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayLanguage(Locale.getDefault())
            .takeIf { it.isNotBlank() }
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            ?: languageTag
    }

    internal suspend fun cachedTitleTranslations(
        context: Context,
        entries: List<SourceCatalogEntry>,
        targetLanguageTag: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return@withContext emptyMap()
        if (entries.isEmpty()) return@withContext emptyMap()
        val currentTitles = entries.associate { "${it.source}:${it.sourceId}" to it.title.trim() }
        database(context).readAliases(target, currentTitles)
    }

    internal fun cachedTitleTranslationBlocking(
        context: Context,
        entryKey: String,
        sourceTitle: String,
        targetLanguageTag: String,
    ): String? {
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return null
        return database(context).readAlias(
            targetLanguage = target,
            entryKey = entryKey,
            sourceTitle = sourceTitle.trim(),
        )
    }

    internal suspend fun titleAliasRevision(context: Context, targetLanguageTag: String): Long {
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return 0L
        return withContext(Dispatchers.IO) { database(context).latestAliasUpdatedAt(target) }
    }

    suspend fun translateIfNeeded(
        context: Context,
        entryKey: String,
        text: String,
        targetLanguageTag: String,
        allowModelDownload: Boolean = true,
        priority: TranslationPriority = TranslationPriority.Foreground,
        force: Boolean = false,
        bypassCache: Boolean = false,
    ): Result<String?> {
        return try {
            Result.success(
                translate(
                    context = context,
                    entryKey = entryKey,
                    text = text,
                    targetLanguageTag = targetLanguageTag,
                    allowModelDownload = allowModelDownload,
                    priority = priority,
                    force = force,
                    bypassCache = bypassCache,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun translate(
        context: Context,
        entryKey: String,
        text: String,
        targetLanguageTag: String,
        allowModelDownload: Boolean,
        priority: TranslationPriority,
        force: Boolean,
        bypassCache: Boolean,
    ): String? {
        val q = text.trim()
        if (q.length < 2) return null
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return null
        val key = cacheKey(entryKey, q, target, force)

        if (!bypassCache) {
            cachedValue(context, key)?.let { cached ->
                storeTitleAlias(context, entryKey, q, target, cached)
                return cached.takeIf { value -> value != NO_TRANSLATION }
            }
        }

        return titleLocks[(key.hashCode() and Int.MAX_VALUE) % titleLocks.size].withLock {
            if (!bypassCache) {
                cachedValue(context, key)?.let { cached ->
                    storeTitleAlias(context, entryKey, q, target, cached)
                    return@withLock cached.takeIf { value -> value != NO_TRANSLATION }
                }
            }

            val useful = withTranslationPermit(priority) {
                translateDetectedText(
                    context = context,
                    text = q,
                    targetLanguage = target,
                    allowModelDownload = allowModelDownload,
                    force = force,
                )
            }
            withContext(NonCancellable) {
                store(context, key, useful ?: NO_TRANSLATION)
                storeTitleAlias(context, entryKey, q, target, useful ?: NO_TRANSLATION)
            }
            useful
        }
    }

    private suspend fun translateDetectedText(
        context: Context,
        text: String,
        targetLanguage: String,
        allowModelDownload: Boolean,
        force: Boolean,
    ): String? {
        val detected = detectLanguage(text)
        val source = TranslateLanguage.fromLanguageTag(detected)
        if (!force) {
            if (detected == "und" || source.isNullOrBlank() || source == targetLanguage) return null
            return translateFrom(
                context,
                text,
                source,
                targetLanguage,
                allowModelDownload,
            )
        }
        if (detected != "und" && !source.isNullOrBlank() && source != targetLanguage) {
            return translateFrom(
                context,
                text,
                source,
                targetLanguage,
                allowModelDownload,
            )
        }

        var changed = false
        val translated = StringBuilder(text.length)
        for (segment in translationSegments(text)) {
            val content = segment.trim()
            if (content.length < 2) {
                translated.append(segment)
                continue
            }
            val segmentSource = TranslateLanguage.fromLanguageTag(detectLanguage(content))
            if (segmentSource.isNullOrBlank() || segmentSource == targetLanguage) {
                translated.append(segment)
            } else {
                val replacement = translateFrom(
                    context,
                    content,
                    segmentSource,
                    targetLanguage,
                    allowModelDownload,
                )
                if (replacement.isNullOrBlank() || replacement.equals(content, ignoreCase = true)) {
                    translated.append(segment)
                } else {
                    changed = true
                    translated.append(replaceTrimmedContent(segment, replacement))
                }
            }
        }
        return translated.toString().takeIf { changed }
    }

    private suspend fun detectLanguage(text: String): String =
        withOperationTimeout(
            label = "Language detection",
            timeoutMs = DETECTION_TIMEOUT_MS,
        ) {
            languageIdentifier.identifyLanguage(text).await()
        }

    private suspend fun translateFrom(
        context: Context,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        allowModelDownload: Boolean,
    ): String? {
        val translatorKey = "$sourceLanguage>$targetLanguage"
        val translator = translators[translatorKey] ?: synchronized(translators) {
            translators[translatorKey] ?: Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build(),
            ).also { translators[translatorKey] = it }
        }
        ensureModel(
            context = context,
            translatorKey = translatorKey,
            translator = translator,
            allowDownload = allowModelDownload,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
        )
        return withOperationTimeout(
            label = "Translation",
            timeoutMs = TRANSLATION_TIMEOUT_MS,
        ) {
            translator.translate(text).await()
        }.trim().takeIf {
            it.isNotBlank() && !it.equals(text, ignoreCase = true)
        }
    }

    private suspend fun <T> withTranslationPermit(
        priority: TranslationPriority,
        block: suspend () -> T,
    ): T {
        if (priority == TranslationPriority.Foreground) {
            foregroundRequests.incrementAndGet()
            return try {
                translationSlots.withPermit { block() }
            } finally {
                foregroundRequests.decrementAndGet()
            }
        }

        while (true) {
            while (foregroundRequests.get() > 0) delay(25)
            translationSlots.acquire()
            if (foregroundRequests.get() == 0) {
                return try {
                    block()
                } finally {
                    translationSlots.release()
                }
            }
            translationSlots.release()
        }
    }

    private suspend fun ensureModel(
        context: Context,
        translatorKey: String,
        translator: Translator,
        allowDownload: Boolean,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        if (translatorKey in readyModels) return
        modelLocks[(translatorKey.hashCode() and Int.MAX_VALUE) % modelLocks.size].withLock {
            if (translatorKey in readyModels) return
            if (!allowDownload) {
                if (!isRecordedModelPair(context, translatorKey) ||
                    !isModelDownloaded(sourceLanguage) ||
                    !isModelDownloaded(targetLanguage)
                ) {
                    throw ModelDownloadRequiredException(translatorKey)
                }
            }
            val now = System.currentTimeMillis()
            modelFailures[translatorKey]?.let { failure ->
                if (now < failure.retryAfterMs) {
                    throw IllegalStateException(
                        "The translation model is not available yet. Connect to the internet and try again.",
                        failure.cause,
                    )
                }
                modelFailures.remove(translatorKey, failure)
            }
            try {
                withOperationTimeout(
                    label = "Translation model download",
                    timeoutMs = MODEL_DOWNLOAD_TIMEOUT_MS,
                ) {
                    translator.downloadModelIfNeeded().await()
                }
                readyModels += translatorKey
                if (recordModelPair(context, translatorKey)) {
                    CatalogTranslationWork.onModelReady(context, targetLanguage)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                modelFailures[translatorKey] = ModelFailure(now + MODEL_RETRY_DELAY_MS, t)
                throw t
            }
        }
    }

    private fun isRecordedModelPair(context: Context, translatorKey: String): Boolean =
        synchronized(modelPrefsLock) {
            translatorKey in context.applicationContext
                .getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
                .getStringSet(MODEL_PAIRS_KEY, emptySet())
                .orEmpty()
        }

    private fun recordModelPair(context: Context, translatorKey: String): Boolean {
        synchronized(modelPrefsLock) {
            val prefs = context.applicationContext.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(MODEL_PAIRS_KEY, emptySet()).orEmpty().toSet()
            if (translatorKey !in current) {
                prefs.edit().putStringSet(MODEL_PAIRS_KEY, current + translatorKey).apply()
                return true
            }
            return false
        }
    }

    private suspend fun isModelDownloaded(language: String): Boolean =
        withOperationTimeout(
            label = "Translation model check",
            timeoutMs = DETECTION_TIMEOUT_MS,
        ) {
            remoteModelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(language).build(),
            ).await()
        }

    private suspend fun <T> withOperationTimeout(
        label: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (t: TimeoutCancellationException) {
        throw IOException("$label timed out.", t)
    }

    private suspend fun cachedValue(context: Context, key: String): String? {
        memoryCache[key]?.let { return it }
        val persisted = withContext(Dispatchers.IO) { database(context).read(key) } ?: return null
        memoryCache[key] = persisted
        return persisted
    }

    private suspend fun store(context: Context, key: String, value: String) {
        memoryCache[key] = value
        withContext(Dispatchers.IO) { database(context).write(key, value, CACHE_LIMIT) }
    }

    private suspend fun storeTitleAlias(
        context: Context,
        entryKey: String,
        sourceText: String,
        targetLanguage: String,
        value: String,
    ) {
        if (entryKey.startsWith("synopsis:") || entryKey.isBlank()) return
        withContext(Dispatchers.IO) {
            database(context).writeAlias(
                entryKey = entryKey,
                targetLanguage = targetLanguage,
                sourceText = sourceText,
                value = value.takeIf { it != NO_TRANSLATION },
            )
        }
    }

    private fun database(context: Context): TranslationCacheDb =
        cacheDb ?: synchronized(this) {
            cacheDb ?: TranslationCacheDb(context.applicationContext).also { cacheDb = it }
        }

    internal fun cacheKey(
        entryKey: String,
        text: String,
        targetLanguage: String,
        force: Boolean = false,
    ): String {
        val raw = "$CACHE_VERSION\u0001$entryKey\u0001$targetLanguage\u0001$text" +
            if (force) "\u0001forced" else ""
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val alphabet = "0123456789abcdef"
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xFF
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0F]
        }
        return hex.concatToString()
    }

    private data class ModelFailure(
        val retryAfterMs: Long,
        val cause: Throwable,
    )

    internal class ModelDownloadRequiredException(translatorKey: String) :
        IllegalStateException("Translation model $translatorKey has not been downloaded yet.")
}

enum class TranslationPriority {
    Foreground,
    Background,
}

internal fun translationSegments(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = java.text.BreakIterator.getSentenceInstance(Locale.ROOT)
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != java.text.BreakIterator.DONE) {
        result += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    if (start < text.length) result += text.substring(start)
    return result.ifEmpty { listOf(text) }
}

private fun replaceTrimmedContent(segment: String, replacement: String): String {
    val leading = segment.takeWhile(Char::isWhitespace)
    val trailing = segment.takeLastWhile(Char::isWhitespace)
    return leading + replacement + trailing
}

private class TranslationCacheDb(context: Context) :
    SQLiteOpenHelper(context, "catalog_translations.db", null, 3) {

    private val writesSincePrune = AtomicInteger()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE title_translation (
                cache_key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_title_translation_updated_at ON title_translation(updated_at)")
        createAliasTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_title_translation_updated_at ON title_translation(updated_at)",
            )
        }
        if (oldVersion < 3) createAliasTable(db)
    }

    private fun createAliasTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS title_alias (
                entry_key TEXT NOT NULL,
                target_language TEXT NOT NULL,
                source_text TEXT NOT NULL,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (entry_key, target_language)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_title_alias_target ON title_alias(target_language, updated_at)",
        )
    }

    fun read(key: String): String? =
        readableDatabase.query(
            "title_translation",
            arrayOf("value"),
            "cache_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun readAliases(
        targetLanguage: String,
        currentTitles: Map<String, String>,
    ): Map<String, String> =
        readableDatabase.query(
            "title_alias",
            arrayOf("entry_key", "source_text", "value"),
            "target_language = ?",
            arrayOf(targetLanguage),
            null,
            null,
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val entryKey = cursor.getString(0)
                    if (currentTitles[entryKey] == cursor.getString(1)) {
                        put(entryKey, cursor.getString(2))
                    }
                }
            }
        }

    fun readAlias(
        targetLanguage: String,
        entryKey: String,
        sourceTitle: String,
    ): String? =
        readableDatabase.query(
            "title_alias",
            arrayOf("value"),
            "entry_key = ? AND target_language = ? AND source_text = ?",
            arrayOf(entryKey, targetLanguage, sourceTitle),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun latestAliasUpdatedAt(targetLanguage: String): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(updated_at), 0) FROM title_alias WHERE target_language = ?",
            arrayOf(targetLanguage),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun writeAlias(
        entryKey: String,
        targetLanguage: String,
        sourceText: String,
        value: String?,
    ) {
        if (value == null) {
            writableDatabase.delete(
                "title_alias",
                "entry_key = ? AND target_language = ?",
                arrayOf(entryKey, targetLanguage),
            )
            return
        }
        val values = ContentValues().apply {
            put("entry_key", entryKey)
            put("target_language", targetLanguage)
            put("source_text", sourceText)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update(
            "title_alias",
            values,
            "entry_key = ? AND target_language = ? AND (source_text <> ? OR value <> ?)",
            arrayOf(entryKey, targetLanguage, sourceText, value),
        )
        if (updated == 0) {
            writableDatabase.insertWithOnConflict(
                "title_alias",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    fun write(key: String, value: String, limit: Int) {
        writableDatabase.insertWithOnConflict(
            "title_translation",
            null,
            ContentValues().apply {
                put("cache_key", key)
                put("value", value)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (writesSincePrune.incrementAndGet() >= 256) {
            writesSincePrune.set(0)
            writableDatabase.execSQL(
                """
                DELETE FROM title_translation
                WHERE cache_key IN (
                    SELECT cache_key FROM title_translation
                    ORDER BY updated_at DESC
                    LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
                arrayOf(limit),
            )
        }
    }
}
