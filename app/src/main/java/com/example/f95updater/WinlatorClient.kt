package com.example.f95updater

import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only client for the Winlator Secure AGM API v2+ silent ContentProvider.
 *
 * Authority, paths, and cursor columns mirror the Winlator contract
 * (com.winlator.api.GameApiContract / GameQueryProvider). Winlator authorizes only the
 * AGM package plus the AGM release signing certificate, enforced on the Binder caller UID,
 * so these queries succeed only from a correctly-signed AGM build in the same Android profile.
 *
 * This is the read half of the Winlator integration (capability negotiation + game listing).
 * Mutations (create/configure/launch/delete) go through the Activity Result API separately.
 */
object WinlatorClient {
    const val PACKAGE = WinlatorApi.PACKAGE
    const val AUTHORITY = "com.winlator.secure.agm"

    private const val PATH_CAPABILITIES = "capabilities"
    private const val PATH_CONFIG_SCHEMA = "config-schema"
    private const val PATH_SETTINGS_SCHEMA = "settings-schema"
    private const val PATH_GAMES = "games"
    private const val PATH_SETTINGS = "settings"
    private const val PATH_DIAGNOSTICS = "diagnostics"
    private const val PATH_SUGGESTED_CONFIG = "suggested-config"

    // Fixed cursor columns defined by GameQueryProvider.COLUMNS.
    private const val COL_SUCCESS = "success"
    private const val COL_API_VERSION = "api_version"
    private const val COL_ERROR_CODE = "error_code"
    private const val COL_ERROR_MESSAGE = "error_message"
    private const val COL_CAPABILITIES_JSON = "capabilities_json"
    private const val COL_CONFIG_SCHEMA_JSON = "config_schema_json"
    private const val COL_SETTINGS_SCHEMA_JSON = "settings_schema_json"
    private const val COL_GAMES_JSON = "games_json"
    private const val COL_GAME_JSON = "game_json"
    private const val COL_SETTINGS_JSON = "settings_json"
    private const val COL_REPORT_JSON = "report_json"
    private const val COL_DIAGNOSTICS_JSON = "diagnostics_json"
    private const val COL_SUGGESTED_CONFIG_JSON = "suggested_config_json"
    private const val COL_HAS_MORE = "has_more"
    private const val COL_NEXT_OFFSET = "next_offset"

    /** Result of a provider read. */
    sealed class Read {
        /** Provider replied success. [payload] is the requested JSON column (object or array). */
        data class Ok(
            val payload: String,
            val apiVersion: Int,
            val hasMore: Boolean,
            val nextOffset: Int,
        ) : Read()

        /** Provider replied success=0 with a stable error code (e.g. GAME_NOT_FOUND, UNAUTHORIZED). */
        data class Err(val code: String, val message: String) : Read()

        /** Winlator absent, provider unresolvable, or an unexpected local failure. */
        data class Unavailable(val reason: String) : Read()
    }

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    suspend fun getCapabilities(context: Context): Read = withContext(Dispatchers.IO) {
        query(context, Uri.parse("content://$AUTHORITY/$PATH_CAPABILITIES"), COL_CAPABILITIES_JSON)
    }

    suspend fun getConfigSchema(context: Context): Read = withContext(Dispatchers.IO) {
        query(context, Uri.parse("content://$AUTHORITY/$PATH_CONFIG_SCHEMA"), COL_CONFIG_SCHEMA_JSON)
    }

    /** Reads the per-game *settings* schema (namespaced fields; see [WinlatorSettingsSchema.parse]). */
    suspend fun getSettingsSchema(context: Context): Read = withContext(Dispatchers.IO) {
        query(context, Uri.parse("content://$AUTHORITY/$PATH_SETTINGS_SCHEMA"), COL_SETTINGS_SCHEMA_JSON)
    }

    /**
     * Lists managed games. Omitting [offset] and [limit] returns every game (unpaged).
     * Providing either enables paging; [limit] must be 1..100 and the caller should follow
     * [Read.Ok.hasMore]/[Read.Ok.nextOffset].
     */
    suspend fun listGames(context: Context, offset: Int? = null, limit: Int? = null): Read =
        withContext(Dispatchers.IO) {
            val builder = Uri.parse("content://$AUTHORITY/$PATH_GAMES").buildUpon()
            if (offset != null) builder.appendQueryParameter("offset", offset.toString())
            if (limit != null) builder.appendQueryParameter("limit", limit.toString())
            query(context, builder.build(), COL_GAMES_JSON)
        }

    suspend fun getGame(context: Context, gameId: String): Read = withContext(Dispatchers.IO) {
        query(context, Uri.parse("content://$AUTHORITY/$PATH_GAMES/${Uri.encode(gameId)}"), COL_GAME_JSON)
    }

    /**
     * Reads the per-game *settings* envelope (localization/runtimeLocale + baseSettingsSha256).
     * Payload column is [COL_SETTINGS_JSON]; see [WinlatorGameSettings.parse].
     */
    suspend fun getGameSettings(context: Context, gameId: String): Read = withContext(Dispatchers.IO) {
        query(
            context,
            Uri.parse("content://$AUTHORITY/$PATH_GAMES/${Uri.encode(gameId)}/$PATH_SETTINGS"),
            COL_SETTINGS_JSON,
        )
    }

    /**
     * Reads Winlator's file-fingerprinted suggested configuration for a game (read-only, game-scoped;
     * safe to call anytime). Payload column is [COL_SUGGESTED_CONFIG_JSON]; see
     * [WinlatorSuggestedConfig.parse].
     */
    suspend fun getSuggestedConfig(context: Context, gameId: String): Read = withContext(Dispatchers.IO) {
        query(
            context,
            Uri.parse("content://$AUTHORITY/$PATH_GAMES/${Uri.encode(gameId)}/$PATH_SUGGESTED_CONFIG"),
            COL_SUGGESTED_CONFIG_JSON,
        )
    }

    suspend fun getDiagnostic(context: Context, reportId: String): Read = withContext(Dispatchers.IO) {
        query(
            context,
            Uri.parse("content://$AUTHORITY/$PATH_DIAGNOSTICS/${Uri.encode(reportId)}"),
            COL_REPORT_JSON,
        )
    }

    suspend fun listDiagnostics(context: Context, gameId: String, limit: Int = 20): Read =
        withContext(Dispatchers.IO) {
            require(limit in 1..20) { "Diagnostic limit must be between 1 and 20." }
            val uri = Uri.parse(
                "content://$AUTHORITY/$PATH_GAMES/${Uri.encode(gameId)}/$PATH_DIAGNOSTICS"
            ).buildUpon()
                .appendQueryParameter("limit", limit.toString())
                .build()
            query(context, uri, COL_DIAGNOSTICS_JSON)
        }

    suspend fun capabilities(context: Context): Result<WinlatorApi.Capabilities> =
        when (val read = getCapabilities(context)) {
            is Read.Ok -> runCatching { WinlatorApi.Capabilities.parse(read.payload) }
            is Read.Err -> Result.failure(IllegalStateException("${read.code}: ${read.message}"))
            is Read.Unavailable -> Result.failure(IllegalStateException(read.reason))
        }

    suspend fun requiredV2Capabilities(context: Context): Result<WinlatorApi.Capabilities> =
        capabilities(context).mapCatching { value ->
            require(value.apiVersion >= 2) { "Winlator Secure API v2 is required." }
            require(value.supportsPortableGames) { "Winlator does not advertise portable-game support." }
            require(value.supportsWindowsInstallers) { "Winlator does not advertise Windows-installer support." }
            require(value.sameProfilePaths) { "Winlator does not advertise same-profile filesystem paths." }
            require(!value.safUri) { "This integration requires Winlator's filesystem-path API." }
            value
        }

    suspend fun requiredSharedContainerCapabilities(context: Context): Result<WinlatorApi.Capabilities> =
        requiredV2Capabilities(context).mapCatching { value ->
            require(value.apiVersion >= 3) { "Winlator Secure API v3 is required for shared containers." }
            require(value.sharedContainers && value.sharedDefaultContainer) {
                "Winlator does not advertise shared-default container support."
            }
            require(value.referenceSafeContainerDeletion) {
                "Winlator does not advertise reference-safe container deletion."
            }
            value
        }

    suspend fun requiredManagementCapabilities(context: Context): Result<WinlatorApi.Capabilities> =
        capabilities(context).mapCatching { value ->
            require(value.apiVersion >= 4) { "Winlator Secure API v4 is required." }
            require(value.managedGameConfiguration && value.gameConfigSchemaVersion >= 1) {
                "Winlator does not advertise managed game configuration."
            }
            require(value.configConflictDetection && value.configUpdateExtra == "config_update_json") {
                "Winlator does not advertise conflict-safe game configuration."
            }
            value
        }

    suspend fun managedGames(context: Context): Result<List<WinlatorApi.ManagedGame>> {
        if (!isInstalled(context)) return Result.success(emptyList())
        return runCatching {
            val games = mutableListOf<WinlatorApi.ManagedGame>()
            var offset = 0
            do {
                val page = when (val read = listGames(context, offset = offset, limit = 8)) {
                    is Read.Ok -> read
                    is Read.Err -> error("${read.code}: ${read.message}")
                    is Read.Unavailable -> error(read.reason)
                }
                games += WinlatorApi.ManagedGame.parseList(page.payload)
                if (!page.hasMore) break
                if (page.nextOffset <= offset) error("Winlator returned an invalid next_offset.")
                offset = page.nextOffset
            } while (true)
            games
        }
    }

    suspend fun installedApps(context: Context): List<InstalledApp> =
        managedGames(context)
            .onFailure { AppLog.w("Winlator", "Managed-game scan failed", it) }
            .getOrDefault(emptyList())
            .map { it.toInstalledApp() }

    private fun query(context: Context, uri: Uri, payloadColumn: String): Read {
        if (!isInstalled(context)) return Read.Unavailable("Winlator is not installed in this profile.")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return Read.Err("EMPTY_CURSOR", "Provider returned no rows.")
                val success = colIndex(c, COL_SUCCESS)?.let { c.getInt(it) != 0 } ?: false
                val apiVersion = colIndex(c, COL_API_VERSION)?.let { c.getInt(it) } ?: 0
                if (!success) {
                    val code = colIndex(c, COL_ERROR_CODE)?.let { c.getString(it) } ?: "UNKNOWN"
                    val msg = colIndex(c, COL_ERROR_MESSAGE)?.let { c.getString(it) } ?: code
                    return Read.Err(code, msg)
                }
                val payload = colIndex(c, payloadColumn)?.let { c.getString(it) } ?: ""
                val hasMore = colIndex(c, COL_HAS_MORE)?.let { c.getInt(it) != 0 } ?: false
                val nextOffset = colIndex(c, COL_NEXT_OFFSET)?.let { c.getInt(it) } ?: 0
                Read.Ok(payload, apiVersion, hasMore, nextOffset)
            } ?: Read.Unavailable("Winlator provider returned no cursor (authority $AUTHORITY unresolvable).")
        } catch (e: SecurityException) {
            // Provider throws SecurityException("UNAUTHORIZED: ...") for a non-AGM / wrong-signature caller.
            Read.Err("UNAUTHORIZED", e.message ?: "Caller not authorized by Winlator.")
        } catch (e: Exception) {
            Read.Unavailable(e.message ?: e.toString())
        }
    }

    private fun colIndex(c: Cursor, name: String): Int? = c.getColumnIndex(name).takeIf { it >= 0 }

    /**
     * Diagnostic round-trip: queries capabilities + all games and writes the raw provider
     * responses to Documents/AdultGameManager/logs/winlator-probe.json (overwritten each run)
     * and to AppLog. This is the definitive on-device proof that the authenticated provider
     * path works from a release-signed AGM build.
     */
    suspend fun runStartupProbe(context: Context) {
        if (!isInstalled(context)) {
            AppLog.i("Winlator", "Not installed in this profile; skipping capability probe.")
            return
        }
        val caps = getCapabilities(context)
        val games = listGames(context)

        val out = JSONObject()
        out.put("probedAt", System.currentTimeMillis())
        out.put("agmVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        out.put("winlatorInstalled", true)
        out.put("capabilities", describe(caps))
        out.put("games", describe(games))

        AppLog.i("Winlator", "probe capabilities=${summary(caps)} games=${summary(games)}")
        runCatching { AppConfigStore.writeLogToDocuments(context, "winlator-probe.json", out.toString(2)) }
            .onSuccess { AppLog.i("Winlator", "probe snapshot written to $it") }
            .onFailure { AppLog.w("Winlator", "probe snapshot write failed", it) }
    }

    private fun describe(read: Read): JSONObject = JSONObject().apply {
        when (read) {
            is Read.Ok -> {
                put("status", "ok")
                put("apiVersion", read.apiVersion)
                put("hasMore", read.hasMore)
                put("nextOffset", read.nextOffset)
                put("payload", parseJson(read.payload))
            }
            is Read.Err -> {
                put("status", "error")
                put("code", read.code)
                put("message", read.message)
            }
            is Read.Unavailable -> {
                put("status", "unavailable")
                put("reason", read.reason)
            }
        }
    }

    /** Reparse a payload string into a JSON object/array so the snapshot is readable, else keep raw. */
    private fun parseJson(raw: String): Any {
        if (raw.isBlank()) return JSONObject.NULL
        return runCatching { JSONObject(raw) as Any }
            .recoverCatching { JSONArray(raw) as Any }
            .getOrDefault(raw)
    }

    private fun summary(read: Read): String = when (read) {
        is Read.Ok -> "ok(api=${read.apiVersion},len=${read.payload.length})"
        is Read.Err -> "err(${read.code})"
        is Read.Unavailable -> "unavailable(${read.reason})"
    }
}
