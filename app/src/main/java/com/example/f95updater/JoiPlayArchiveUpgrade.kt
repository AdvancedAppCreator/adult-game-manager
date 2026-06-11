package com.example.f95updater

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.junrar.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JoiPlayArchiveAnalysis(
    val displayName: String,
    val candidateNames: List<String>,
    val entryCount: Int,
)

data class JoiPlayUpgradePrompt(
    val archive: File,
    val analysis: JoiPlayArchiveAnalysis,
    val matches: List<InstalledApp>,
)

data class JoiPlayUpgradeResult(
    val app: InstalledApp,
    val newFolder: String,
    val backupFolder: String,
    val saveItemsCopied: Int,
)

object JoiPlayArchiveInspector {
    suspend fun analyze(archive: File): JoiPlayArchiveAnalysis = withContext(Dispatchers.IO) {
        val format = ArchiveExtractor.detectFormatByExt(archive.name)
            ?: archive.inputStream().use { ArchiveExtractor.detectFormat(it) }
        val entries = when (format) {
            ArchiveExtractor.Format.ZIP -> zipEntries(archive)
            ArchiveExtractor.Format.RAR -> rarEntries(archive)
            ArchiveExtractor.Format.SEVENZ -> sevenZEntries(archive)
            null -> emptyList()
        }.map { it.replace('\\', '/').trimStart('/') }.filter { it.isNotBlank() }

        val metadataNames = if (format == ArchiveExtractor.Format.ZIP) zipMetadataNames(archive) else emptyList()
        val commonRoot = commonRootFolder(entries)
        val launchNames = entries
            .filter { it.substringAfterLast('/').substringAfterLast('.').lowercase() in launchExtensions }
            .map { it.substringBeforeLast('.', it.substringAfterLast('/')).substringAfterLast('/') }
        val fallback = archive.nameWithoutExtension
        val names = (metadataNames + listOfNotNull(commonRoot) + launchNames + fallback)
            .map { cleanCandidateName(it) }
            .filter { it.length >= 3 }
            .distinctBy { CatalogRepository.normalizeTitle(it) }

        JoiPlayArchiveAnalysis(
            displayName = names.firstOrNull() ?: cleanCandidateName(fallback),
            candidateNames = names,
            entryCount = entries.size,
        )
    }

    fun findUpgradeMatches(analysis: JoiPlayArchiveAnalysis, apps: List<InstalledApp>): List<InstalledApp> {
        val scored = apps.asSequence()
            .filter { it.source == AppSource.JoiPlay }
            .filter { !it.storagePath.isNullOrBlank() && !it.joiPlayExecFile.isNullOrBlank() }
            .mapNotNull { app ->
                val labels = listOfNotNull(app.label, app.launcherLabel, app.storageFolderName, app.storagePath?.substringAfterLast(File.separatorChar))
                val best = analysis.candidateNames.maxOfOrNull { candidate ->
                    labels.maxOfOrNull { label -> titleScore(candidate, label) } ?: 0
                } ?: 0
                if (best >= 62) app to best else null
            }
            .sortedWith(compareByDescending<Pair<InstalledApp, Int>> { it.second }.thenBy { it.first.label.lowercase() })
            .take(6)
            .map { it.first }
            .toList()
        return scored
    }

    private fun zipEntries(archive: File): List<String> =
        ZipFile(archive).fileHeaders.map { it.fileName }

    private fun rarEntries(archive: File): List<String> {
        if (UnrarNative.available) {
            return runCatching { UnrarNative.listEntries(archive, null).map { it.name } }.getOrDefault(emptyList())
        }
        return runCatching {
            Archive(archive).use { rar ->
                val out = mutableListOf<String>()
                var h = rar.nextFileHeader()
                while (h != null) {
                    out += h.fileNameString
                    h = rar.nextFileHeader()
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun sevenZEntries(archive: File): List<String> =
        runCatching {
            SevenZFile(archive).use { sz ->
                val out = mutableListOf<String>()
                var e = sz.nextEntry
                while (e != null) {
                    out += e.name
                    e = sz.nextEntry
                }
                out
            }
        }.getOrDefault(emptyList())

    private fun zipMetadataNames(archive: File): List<String> {
        val zf = ZipFile(archive)
        if (zf.isEncrypted) return emptyList()
        val out = mutableListOf<String>()
        for (h in zf.fileHeaders) {
            val name = h.fileName.replace('\\', '/')
            if (h.isDirectory || h.uncompressedSize > 128 * 1024L) continue
            val base = name.substringAfterLast('/').lowercase()
            if (base !in setOf("package.json", "game.ini", "options.rpy")) continue
            val text = runCatching {
                zf.getInputStream(h).bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            when (base) {
                "package.json" -> runCatching {
                    val json = JSONObject(text)
                    listOf(json.optString("title"), json.optString("name"))
                        .filter { it.isNotBlank() }
                        .forEach { out += it }
                }
                "game.ini" -> Regex("""(?im)^\s*(?:title|name)\s*=\s*(.+?)\s*$""")
                    .find(text)?.groupValues?.getOrNull(1)?.let { out += it }
                "options.rpy" -> Regex("""config\.name\s*=\s*(?:_\()?["'](.+?)["']""")
                    .find(text)?.groupValues?.getOrNull(1)?.let { out += it }
            }
        }
        return out
    }

    private val launchExtensions = setOf("exe", "sh", "py", "html", "swf", "jgp")

    private fun titleScore(a: String, b: String): Int {
        val an = CatalogRepository.normalizeTitle(a)
        val bn = CatalogRepository.normalizeTitle(b)
        if (an.isBlank() || bn.isBlank()) return 0
        if (an == bn) return 100
        if (an.length >= 5 && bn.contains(an)) return 88
        if (bn.length >= 5 && an.contains(bn)) return 86
        val aw = CatalogRepository.titleWords(a).toSet()
        val bw = CatalogRepository.titleWords(b).toSet()
        if (aw.isEmpty() || bw.isEmpty()) return 0
        val overlap = aw.intersect(bw).size
        val smaller = minOf(aw.size, bw.size)
        val larger = maxOf(aw.size, bw.size)
        return when {
            overlap == smaller && smaller >= 2 -> 82 - (larger - smaller) * 5
            overlap >= 2 -> 68
            overlap == 1 && (aw.singleOrNull()?.length ?: 0) >= 6 -> 62
            overlap == 1 && (bw.singleOrNull()?.length ?: 0) >= 6 -> 62
            else -> 0
        }
    }

    private fun commonRootFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val firstSegments = names.map { it.trimStart('/').substringBefore('/') }
        val unique = firstSegments.toSet()
        if (unique.size != 1) return null
        val candidate = unique.single()
        if (candidate.isBlank()) return null
        return if (names.any { it.contains('/') }) candidate else null
    }

    private fun cleanCandidateName(raw: String): String =
        raw.substringAfterLast('/')
            .substringBeforeLast('.', raw)
            .replace(Regex("""[_\-.]+"""), " ")
            .replace(Regex("""(?i)\b(?:pc|windows|win64|win32|linux|mac|android|compressed|public|patreon|build|release)\b"""), " ")
            .replace(Regex("""(?i)\bv?\d+(?:\.\d+)*[a-z]?\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}

class JoiPlayArchiveUpgradeFlow(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var archiveName by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf(JoiPlayExtractFlow.Phase.Preparing); private set
    var progress by mutableStateOf<ArchiveExtractor.Progress?>(null); private set
    var passwordPromptFor by mutableStateOf<ArchiveExtractor.Format?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<JoiPlayUpgradeResult?>(null); private set

    private var currentJob: Job? = null
    private var pending: Pending? = null

    val inProgress: Boolean
        get() = progress != null || (phase == JoiPlayExtractFlow.Phase.Cancelling && archiveName != null)

    fun start(archive: File, app: InstalledApp, deleteSourceOnSuccess: File? = null) {
        cancelInProgress(restoreBackup = false)
        archiveName = archive.name
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing ${archive.name}…")
        currentJob = scope.launch {
            try {
                prepareAndExtract(archive, app, deleteSourceOnSuccess)
            } catch (ce: CancellationException) {
                AppLog.i("JoiPlayUpgrade", "Cancelled")
                clearInFlightState(clearPending = false)
            }
        }
    }

    fun submitPassword(password: String) {
        val p = pending ?: return
        passwordPromptFor = null
        phase = JoiPlayExtractFlow.Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing ${p.archive.name}…")
        currentJob = scope.launch {
            try {
                runExtraction(p, password.toCharArray())
            } catch (ce: CancellationException) {
                AppLog.i("JoiPlayUpgrade", "Cancelled")
                clearInFlightState(clearPending = false)
            }
        }
    }

    fun cancelPasswordPrompt() {
        cancelInProgress(restoreBackup = true)
    }

    fun cancelInProgress(restoreBackup: Boolean = true) {
        val job = currentJob
        if (job?.isActive == true) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            progress = progress ?: ArchiveExtractor.Progress(0L, 0L, 0, 1, "Cancelling…")
            passwordPromptFor = null
            job.cancel()
            return
        }
        val p = pending
        if (restoreBackup && p != null) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Restoring previous version…")
            passwordPromptFor = null
            currentJob = scope.launch {
                withContext(Dispatchers.IO) { restoreBackup(p) }
                pending = null
                clearInFlightState(clearPending = false)
            }
            return
        }
        clearInFlightState(clearPending = true)
    }

    private fun clearInFlightState(clearPending: Boolean) {
        currentJob = null
        progress = null
        passwordPromptFor = null
        archiveName = null
        if (clearPending) pending = null
    }

    fun acknowledgeError() {
        errorMessage = null
    }

    fun acknowledgeResult() {
        result = null
        archiveName = null
        progress = null
        pending = null
    }

    private suspend fun prepareAndExtract(archive: File, app: InstalledApp, deleteSourceOnSuccess: File?) = withContext(Dispatchers.IO) {
        val storagePath = app.storagePath?.takeIf { it.isNotBlank() }
            ?: return@withContext fail("This JoiPlay entry does not include a game folder path. Re-import the JoiPlay backup first.")
        val execFile = app.joiPlayExecFile?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotBlank() }
            ?: return@withContext fail("This JoiPlay entry does not include a launch-file path. Re-import the JoiPlay backup first.")
        val target = File(storagePath)
        if (!target.isDirectory) return@withContext fail("Game folder does not exist: ${target.absolutePath}")
        val parent = target.parentFile ?: return@withContext fail("Game folder has no parent: ${target.absolutePath}")
        if (!parent.canWrite()) return@withContext fail("Can't write to the game folder parent: ${parent.absolutePath}")
        val backup = uniqueBackupFolder(target)
        if (!target.renameTo(backup)) return@withContext fail("Could not rename existing game folder to backup.")
        val p = Pending(archive, app, execFile, target, backup, deleteSourceOnSuccess)
        pending = p
        phase = JoiPlayExtractFlow.Phase.Extracting
        runExtraction(p, password = null)
    }

    private suspend fun runExtraction(pendingWork: Pending, password: CharArray?) {
        val format = ArchiveExtractor.detectFormatByExt(pendingWork.archive.name)
            ?: pendingWork.archive.inputStream().use { ArchiveExtractor.detectFormat(it) }
            ?: return failAndRestore(pendingWork, "Unknown archive format. Supported: ZIP, RAR, 7Z.")
        val outcome = try {
            ArchiveExtractor.extract(
                context = context,
                archive = pendingWork.archive,
                format = format,
                password = password,
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(pendingWork.target.parentFile!!),
                suggestedName = pendingWork.target.name,
                forcedSubfolderName = pendingWork.target.name,
            ) { p -> progress = p }
        } catch (ce: CancellationException) {
            phase = JoiPlayExtractFlow.Phase.Cancelling
            progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Restoring previous version…")
            restoreBackup(pendingWork)
            clearInFlightState(clearPending = true)
            throw ce
        }
        when (outcome) {
            is ArchiveExtractor.Outcome.Ok -> finishUpgrade(pendingWork)
            is ArchiveExtractor.Outcome.NeedsPassword -> {
                progress = null
                passwordPromptFor = outcome.format
                currentJob = null
            }
            is ArchiveExtractor.Outcome.Failed -> failAndRestore(pendingWork, outcome.message)
            is ArchiveExtractor.Outcome.Cancelled -> failAndRestore(pendingWork, "Upgrade cancelled.")
        }
    }

    private fun finishUpgrade(pendingWork: Pending) {
        val execReady = ensureExecPath(pendingWork.target, pendingWork.execFile)
        if (!execReady) {
            failAndRestore(
                pendingWork,
                "The new archive does not contain the previous launch file path: ${pendingWork.execFile}. The old folder was restored."
            )
            return
        }
        val copied = copySaveFiles(pendingWork.backup, pendingWork.target)
        pendingWork.deleteSourceOnSuccess?.let { src ->
            runCatching { if (src.exists()) src.delete() }
                .onFailure { AppLog.w("JoiPlayUpgrade", "Could not delete source archive ${src.absolutePath}", it) }
        }
        progress = null
        passwordPromptFor = null
        currentJob = null
        result = JoiPlayUpgradeResult(
            app = pendingWork.app,
            newFolder = pendingWork.target.absolutePath,
            backupFolder = pendingWork.backup.absolutePath,
            saveItemsCopied = copied,
        )
        AppLog.i("JoiPlayUpgrade", "Upgraded ${pendingWork.app.label}: new=${pendingWork.target} backup=${pendingWork.backup} saves=$copied")
    }

    private fun ensureExecPath(target: File, execFile: String): Boolean {
        val wanted = File(target, execFile)
        if (wanted.isFile) return true
        val suffix = execFile.replace('\\', '/')
        val wrapper = target.walkTopDown()
            .filter { it.isFile }
            .firstOrNull { it.relativeTo(target).invariantSeparatorsPath.equals(suffix, ignoreCase = true) }
            ?.let { return true }
            ?: target.walkTopDown()
                .filter { it.isFile }
                .firstOrNull { it.relativeTo(target).invariantSeparatorsPath.endsWith("/$suffix", ignoreCase = true) }
                ?.relativeTo(target)
                ?.invariantSeparatorsPath
                ?.removeSuffix("/$suffix")
                ?.substringBefore('/')
        if (wrapper.isNullOrBlank()) return false
        val wrapperDir = File(target, wrapper)
        val children = wrapperDir.listFiles() ?: return false
        for (child in children) {
            val dest = File(target, child.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!child.renameTo(dest)) {
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        wrapperDir.deleteRecursively()
        return wanted.isFile
    }

    private fun copySaveFiles(backup: File, target: File): Int {
        var copied = 0
        val saveDirs = listOf("game/saves", "saves", "www/save", "save")
        for (rel in saveDirs) {
            val src = File(backup, rel)
            if (!src.isDirectory) continue
            val dst = File(target, rel)
            runCatching {
                src.copyRecursively(dst, overwrite = true)
                copied++
            }.onFailure { AppLog.w("JoiPlayUpgrade", "Could not copy save folder $rel", it) }
        }
        val saveFilePatterns = listOf(
            Regex("""(?i)^Save\d+\.rvdata2?$"""),
            Regex("""(?i)^Save\d+\.rxdata$"""),
            Regex("""(?i)^Save\d+\.lsd$"""),
        )
        backup.listFiles()?.filter { file -> file.isFile && saveFilePatterns.any { it.matches(file.name) } }?.forEach { src ->
            runCatching {
                src.copyTo(File(target, src.name), overwrite = true)
                copied++
            }.onFailure { AppLog.w("JoiPlayUpgrade", "Could not copy save file ${src.name}", it) }
        }
        return copied
    }

    private fun fail(message: String) {
        progress = null
        passwordPromptFor = null
        currentJob = null
        errorMessage = message
        AppLog.w("JoiPlayUpgrade", message)
    }

    private fun failAndRestore(pendingWork: Pending, message: String) {
        progress = null
        passwordPromptFor = null
        restoreBackup(pendingWork)
        fail(message)
    }

    private fun restoreBackup(pendingWork: Pending) {
        runCatching {
            if (pendingWork.target.exists()) pendingWork.target.deleteRecursively()
            if (pendingWork.backup.exists() && !pendingWork.target.exists()) {
                pendingWork.backup.renameTo(pendingWork.target)
            }
        }.onFailure { AppLog.w("JoiPlayUpgrade", "Could not restore backup ${pendingWork.backup}", it) }
    }

    private fun uniqueBackupFolder(target: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var backup = File(target.parentFile, "${target.name}.bak-$stamp")
        var i = 2
        while (backup.exists()) {
            backup = File(target.parentFile, "${target.name}.bak-$stamp-$i")
            i++
        }
        return backup
    }

    private data class Pending(
        val archive: File,
        val app: InstalledApp,
        val execFile: String,
        val target: File,
        val backup: File,
        val deleteSourceOnSuccess: File?,
    )
}
