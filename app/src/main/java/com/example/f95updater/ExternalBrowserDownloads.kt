package com.example.f95updater

import java.io.File

internal data class ExternalDownloadCandidate(
    val path: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
)

internal data class ExternalCandidateObservation(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
    val confirmations: Int,
)

internal fun snapshotExternalDownloadFiles(roots: List<String>): List<ExternalFileSnapshot> =
    scanExternalDownloadFiles(roots).map {
        ExternalFileSnapshot(it.path, it.size, it.modifiedAt)
    }

internal fun scanExternalDownloadFiles(roots: List<String>): List<ExternalDownloadCandidate> =
    roots.asSequence()
        .map(::File)
        .filter { it.isDirectory && it.canRead() }
        .flatMap { it.listFiles().orEmpty().asSequence() }
        .filter { it.isFile && !it.name.startsWith(".agm-") }
        .map {
            ExternalDownloadCandidate(
                path = it.absolutePath,
                name = it.name,
                size = it.length(),
                modifiedAt = it.lastModified(),
            )
        }
        .distinctBy { it.path.lowercase() }
        .toList()

internal fun selectExternalDownloadCandidate(
    record: DownloadRecord,
    files: List<ExternalDownloadCandidate>,
): ExternalDownloadCandidate? {
    if (record.expectedBytes <= 0L) return null
    val baseline = record.externalBaseline.associateBy { it.path.lowercase() }
    val candidates = files.filter { file ->
        val old = baseline[file.path.lowercase()]
        val changed = old == null || old.size != file.size || old.modifiedAt != file.modifiedAt
        changed &&
            file.size > 0L &&
            file.modifiedAt >= record.createdAt - 2_000L &&
            externalDownloadNamesMatch(record.fileName, file.name) &&
            file.size == record.expectedBytes
    }
    return candidates.singleOrNull()
}

internal fun updateExternalCandidateObservation(
    previous: ExternalCandidateObservation?,
    candidate: ExternalDownloadCandidate?,
): ExternalCandidateObservation? {
    if (candidate == null) return null
    val confirmations = if (
        previous?.path == candidate.path &&
        previous.size == candidate.size &&
        previous.modifiedAt == candidate.modifiedAt
    ) {
        previous.confirmations + 1
    } else {
        1
    }
    return ExternalCandidateObservation(
        candidate.path,
        candidate.size,
        candidate.modifiedAt,
        confirmations,
    )
}

internal fun externalDownloadNamesMatch(expected: String, actual: String): Boolean =
    normalizedExternalDownloadName(expected) == normalizedExternalDownloadName(actual)

private fun normalizedExternalDownloadName(value: String): String {
    val dot = value.lastIndexOf('.')
    val base = if (dot > 0) value.substring(0, dot) else value
    val extension = if (dot > 0) value.substring(dot) else ""
    return (base.replace(Regex(""" \(\d+\)$"""), "") + extension).lowercase()
}

internal fun newExternalDownloadId(nowMs: Long, existingIds: Collection<Long>): Long {
    var id = -nowMs.coerceAtLeast(1L)
    while (id in existingIds) id--
    return id
}
