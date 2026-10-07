package com.example.f95updater

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.max

/**
 * Read-only inspector for the small, well-defined subset of desktop Unity SerializedFiles needed
 * to explain Texture2D memory. It deliberately rejects a layout it cannot prove rather than
 * attempting a best-effort read of game data.
 */
object UnityTextureMemoryAnalyzer {
    private const val TEXTURE_2D_CLASS_ID = 28
    private const val QUALITY_SETTINGS_CLASS_ID = 47
    private const val MAX_TYPES = 100_000
    private const val MAX_TREE_NODES = 20_000
    private const val MAX_TREE_STRINGS = 4 * 1024 * 1024
    private const val MAX_OBJECTS = 1_000_000
    private const val MAX_COLLECTION_ITEMS = 100_000
    data class UnityGameFiles(
        val executable: File,
        val dataDirectory: File,
        val resourcesAssets: File,
        /** Located for reporting only. Analyzer code never opens this payload file. */
        val resourcesAssetsResS: File?,
        val globalGameManagers: File?,
    )

    sealed interface Discovery {
        data class Found(val files: UnityGameFiles) : Discovery
        data class Missing(val reason: String) : Discovery
    }

    sealed interface Result {
        data class Success(val report: Report) : Result
        data class Unsupported(val reason: String) : Result
        data class Missing(val reason: String) : Result
    }

    data class Report(
        val files: UnityGameFiles,
        val unityVersions: Set<String>,
        val textures: List<Texture>,
        val streamedPayloadBytes: Long,
        val projections: List<Projection>,
        val currentQuality: QualitySettings?,
        val currentProjectionLimit: Int,
        val currentProjectionUsesConservativeFullResolution: Boolean,
        val risk: Risk,
        val recommendation: Recommendation,
        val warnings: List<String>,
    )

    data class Texture(
        val width: Int,
        val height: Int,
        val textureFormat: Int,
        val textureFormatName: String,
        val mipCount: Int,
        val completeImageSize: Long,
        val streamedPayloadBytes: Long,
        val estimatedResidentBytes: Long?,
    ) {
        val isAtLeast8k: Boolean get() = max(width, height) >= 8_192
        val isUncompressed1080pOrLarger: Boolean
            get() = textureFormatInfo(textureFormat)?.blockBytes == null &&
                max(width, height) >= 1_920 && minOf(width, height) >= 1_080
    }

    data class QualitySettings(
        val currentProfileIndex: Int,
        /** Unity QualitySettings.textureQuality / master texture limit, when stored in the active profile. */
        val textureQuality: Int?,
        val streamingMipmapsActive: Boolean?,
        /** Unity stores this setting in MiB. */
        val streamingMipmapsBudgetMiB: Double?,
    )

    data class Projection(
        /** 0 is Winlator's `off`; 1..3 are the managed texture limits. */
        val limit: Int,
        val estimatedResidentBytes: Long,
        val unknownFormatTextureCount: Int,
    )

    enum class RiskLevel { None, High, Critical }

    data class Risk(
        val level: RiskLevel,
        val projectedResidentBytes: Long,
        val physicalRamBytes: Long?,
        val flags: List<String>,
    )

    data class Recommendation(
        /** `off`, `1`, `2`, or `3`; null means that unknown data prevents a safe recommendation. */
        val value: String?,
        val meetsTwentyPercentTarget: Boolean,
        val reason: String,
    )

    /**
     * Finds a Windows executable and only its sibling `<exe>_Data` directory. Every path is
     * canonicalized and symbolic links are excluded, so a recursive library root cannot escape
     * into an unrelated directory.
     */
    fun discover(gameRoot: File, preferredExecutable: File? = null): Discovery {
        val root = runCatching { gameRoot.canonicalFile }.getOrNull()
            ?: return Discovery.Missing("The selected game folder cannot be resolved.")
        if (!root.isDirectory || isSymbolicLink(root)) {
            return Discovery.Missing("The selected game folder is unavailable or is a symbolic link.")
        }
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separatorChar
        fun inside(file: File): File? {
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
            return canonical.takeIf { it.path == root.path || it.path.startsWith(rootPath) }
        }
        fun directChild(parent: File, name: String, directory: Boolean): File? =
            parent.listFiles()
                ?.asSequence()
                ?.filter { it.name.equals(name, ignoreCase = true) }
                ?.mapNotNull(::inside)
                ?.firstOrNull {
                    !isSymbolicLink(it) &&
                        if (directory) it.isDirectory else it.isFile
                }

        val preferred = preferredExecutable
            ?.let(::inside)
            ?.takeIf {
                it.isFile &&
                    it.name.endsWith(".exe", ignoreCase = true) &&
                    !isSymbolicLink(it)
            }
        val executables = buildList {
            preferred?.let(::add)
            root.walkTopDown()
            .maxDepth(8)
            .onEnter { directory ->
                inside(directory) != null && !isSymbolicLink(directory)
            }
            .filter { file ->
                file.isFile &&
                    file.name.endsWith(".exe", ignoreCase = true) &&
                    inside(file) != null &&
                    !isSymbolicLink(file)
            }
            .filter { it != preferred }
            .sortedBy { it.path.lowercase() }
            .forEach(::add)
        }
        val candidates = executables
            .asSequence()
            .mapNotNull { executable ->
                val dataName = executable.name.substringBeforeLast('.', executable.name) + "_Data"
                val data = directChild(executable.parentFile ?: return@mapNotNull null, dataName, true)
                    ?: return@mapNotNull null
                val resources = directChild(data, "resources.assets", directory = false)
                    ?: return@mapNotNull null
                UnityGameFiles(
                    executable = executable,
                    dataDirectory = data,
                    resourcesAssets = resources,
                    resourcesAssetsResS = directChild(data, "resources.assets.resS", directory = false),
                    globalGameManagers = directChild(data, "globalgamemanagers", directory = false),
                )
            }
            .toList()

        val files = candidates.firstOrNull()
            ?: return Discovery.Missing(
                "No Windows Unity `<exe>_Data/resources.assets` layout was found under this game folder.",
            )
        return Discovery.Found(files)
    }

    fun analyze(
        gameRoot: File,
        physicalRamBytes: Long? = null,
        preferredExecutable: File? = null,
    ): Result = when (val found = discover(gameRoot, preferredExecutable)) {
        is Discovery.Missing -> Result.Missing(found.reason)
        is Discovery.Found -> analyze(found.files, physicalRamBytes)
    }

    /** This overload makes the read-only parser independently testable without discovery. */
    fun analyze(files: UnityGameFiles, physicalRamBytes: Long? = null): Result = try {
        val resources = parseSerializedFile(files.resourcesAssets, collectTextures = true, collectQuality = false)
        val global = files.globalGameManagers?.let {
            parseSerializedFile(it, collectTextures = false, collectQuality = true)
        }
        val quality = global?.qualitySettings?.firstOrNull()
        val currentLimit = quality?.textureQuality?.takeIf { it in 0..3 } ?: 0
        val conservativeCurrent = quality?.textureQuality !in 0..3
        val projections = (0..3).map { limit ->
            Projection(
                limit = limit,
                estimatedResidentBytes = resources.textures.sumOf { texture ->
                    texture.estimatedResidentBytesAt(limit) ?: 0L
                },
                unknownFormatTextureCount = resources.textures.count {
                    it.estimatedResidentBytesAt(limit) == null
                },
            )
        }
        val currentProjection = projections[currentLimit]
        val warnings = buildList {
            if (files.resourcesAssetsResS == null && resources.textures.any { it.streamedPayloadBytes > 0 }) {
                add("Streamed texture metadata references payload bytes, but resources.assets.resS was not found.")
            }
            if (quality == null) {
                add("QualitySettings was not available; full-resolution projection is used conservatively.")
            } else if (quality.textureQuality !in 0..3) {
                add("The active QualitySettings textureQuality is unavailable or outside 0–3; full-resolution projection is used conservatively.")
            }
            if (currentProjection.unknownFormatTextureCount > 0) {
                add("${currentProjection.unknownFormatTextureCount} Texture2D format(s) are not supported by the estimator.")
                add("Resident projections and risk are lower bounds until every TextureFormat is supported.")
            }
        }
        Result.Success(
            Report(
                files = files,
                unityVersions = resources.unityVersions + global?.unityVersions.orEmpty(),
                textures = resources.textures,
                streamedPayloadBytes = resources.textures.sumOf { it.streamedPayloadBytes },
                projections = projections,
                currentQuality = quality,
                currentProjectionLimit = currentLimit,
                currentProjectionUsesConservativeFullResolution = conservativeCurrent,
                risk = UnityTextureRiskPolicy.evaluate(
                    projectedResidentBytes = currentProjection.estimatedResidentBytes,
                    physicalRamBytes = physicalRamBytes,
                    textures = resources.textures,
                ),
                recommendation = UnityTextureRiskPolicy.recommend(projections, physicalRamBytes),
                warnings = warnings,
            ),
        )
    } catch (error: UnsupportedLayout) {
        Result.Unsupported(error.message ?: "Unsupported Unity SerializedFile layout.")
    } catch (error: Exception) {
        Result.Unsupported("Could not safely read the Unity metadata: ${error.message ?: error.javaClass.simpleName}.")
    }

    private data class ParsedFile(
        val unityVersions: Set<String>,
        val textures: List<Texture>,
        val qualitySettings: List<QualitySettings>,
    )

    private fun parseSerializedFile(
        file: File,
        collectTextures: Boolean,
        collectQuality: Boolean,
    ): ParsedFile {
        if (!file.isFile || isSymbolicLink(file)) throw UnsupportedLayout("${file.name} is not a regular file.")
        RandomAccessFile(file, "r").use { random ->
            val reader = LittleEndianReader(random, random.length())
            val header = readHeader(reader)
            reader.seek(header.metadataStart)
            val unityVersion = reader.readNullTerminatedUtf8(256)
            if (!SUPPORTED_UNITY_VERSION.matches(unityVersion)) {
                throw UnsupportedLayout(
                    "${file.name} reports Unity $unityVersion; only 2019.4, 2020.3, 2021.3, and 2022.3 LTS are supported.",
                )
            }
            val targetPlatform = reader.readInt()
            if (targetPlatform !in DESKTOP_TARGET_PLATFORMS) {
                throw UnsupportedLayout("$file is not a supported desktop Unity SerializedFile (platform $targetPlatform).")
            }
            if (reader.readUnsignedByte() == 0) {
                throw UnsupportedLayout("$file has no embedded type tree; AGM will not infer object layouts.")
            }
            val typeCount = reader.readInt().also {
                if (it !in 0..MAX_TYPES) throw UnsupportedLayout("$file has an invalid type count.")
            }
            val types = ArrayList<SerializedType>(typeCount)
            repeat(typeCount) {
                val classId = reader.readInt()
                if (header.formatVersion >= 16) reader.skip(1) // isStripped
                if (header.formatVersion >= 17) reader.skip(2) // scriptTypeIndex
                if (header.formatVersion >= 13) {
                    if (classId == 114) reader.skip(16) // script ID
                    reader.skip(16) // old type hash
                }
                val needTree = classId == TEXTURE_2D_CLASS_ID || classId == QUALITY_SETTINGS_CLASS_ID
                types += SerializedType(classId, readTypeTree(reader, header.formatVersion, needTree))
                if (header.formatVersion >= 21) {
                    val dependencyCount = reader.readInt().also {
                        if (it !in 0..MAX_TYPES) throw UnsupportedLayout("Invalid Unity type dependency count.")
                    }
                    reader.skip(safeMultiply(dependencyCount.toLong(), 4L, "Unity type dependencies"))
                }
            }

            val objectCount = reader.readInt().also {
                if (it !in 0..MAX_OBJECTS) throw UnsupportedLayout("$file has an invalid object count.")
            }
            val objects = ArrayList<ObjectInfo>(objectCount)
            repeat(objectCount) {
                reader.align(4)
                val pathId = reader.readLong()
                val relativeStart = if (header.formatVersion >= 22) {
                    reader.readNonNegativeUnsignedLong()
                } else {
                    reader.readUnsignedInt()
                }
                val size = reader.readUnsignedInt()
                val typeId = reader.readInt()
                if (header.formatVersion < 16) reader.skip(2) // Unsupported by version gate, documented for completeness.
                if (header.formatVersion < 11) reader.skip(2)
                if (header.formatVersion in 11..16) reader.skip(2)
                if (header.formatVersion in 15..16) reader.skip(1)
                if (typeId !in types.indices) {
                    throw UnsupportedLayout("$file object $pathId references an unknown serialized type.")
                }
                val start = safeAdd(header.dataOffset, relativeStart, "$file object $pathId")
                val end = safeAdd(start, size, "$file object $pathId")
                if (end > reader.limit) throw UnsupportedLayout("$file object $pathId exceeds file bounds.")
                objects += ObjectInfo(start, end, types[typeId])
            }

            val textures = mutableListOf<Texture>()
            val qualities = mutableListOf<QualitySettings>()
            objects.forEach { info ->
                if (info.type.classId == TEXTURE_2D_CLASS_ID && collectTextures) {
                    val tree = info.type.tree
                        ?: throw UnsupportedLayout("$file Texture2D object has no embedded type tree.")
                    textures += readTexture(reader, info, tree)
                } else if (info.type.classId == QUALITY_SETTINGS_CLASS_ID && collectQuality) {
                    val tree = info.type.tree
                        ?: throw UnsupportedLayout("$file QualitySettings object has no embedded type tree.")
                    readQualitySettings(reader, info, tree)?.let(qualities::add)
                }
            }
            return ParsedFile(setOf(unityVersion), textures, qualities)
        }
    }

    private fun readHeader(reader: LittleEndianReader): Header {
        reader.readUnsignedInt() // Legacy metadata size; superseded by the extended header in v22.
        reader.readUnsignedInt() // Legacy file size.
        val formatVersion = reader.readInt()
        if (formatVersion !in 17..22) {
            throw UnsupportedLayout("SerializedFile format $formatVersion is unsupported (supported layouts: 17–22).")
        }
        val legacyDataOffset = reader.readUnsignedInt()
        if (formatVersion >= 9) {
            if (reader.readUnsignedByte() != 0) {
                throw UnsupportedLayout("Big-endian Unity SerializedFiles are unsupported.")
            }
            reader.skip(3)
        }
        val dataOffset = if (formatVersion >= 22) {
            reader.readUnsignedInt() // extended metadata size
            reader.readNonNegativeUnsignedLong() // extended file size
            reader.readNonNegativeUnsignedLong().also { reader.skip(8) } // extended data offset, then unknown header value
        } else {
            // Layouts 17–21 use the legacy 32-bit data offset already read above.
            legacyDataOffset
        }
        if (dataOffset !in reader.position..reader.limit) {
            throw UnsupportedLayout("SerializedFile data offset is outside the file.")
        }
        return Header(formatVersion, metadataStart = reader.position, dataOffset = dataOffset)
    }

    private fun readTypeTree(
        reader: LittleEndianReader,
        formatVersion: Int,
        retain: Boolean,
    ): List<TreeNode>? {
        val nodeCount = reader.readInt().also {
            if (it !in 1..MAX_TREE_NODES) throw UnsupportedLayout("Invalid embedded type-tree node count.")
        }
        val stringSize = reader.readInt().also {
            if (it !in 0..MAX_TREE_STRINGS) throw UnsupportedLayout("Invalid embedded type-tree string buffer.")
        }
        data class RawNode(
            val level: Int,
            val typeOffset: Long,
            val nameOffset: Long,
            val byteSize: Int,
            val flags: Int,
        )
        val raw = if (retain) ArrayList<RawNode>(nodeCount) else null
        repeat(nodeCount) {
            reader.skip(2) // version
            val level = reader.readUnsignedByte()
            reader.skip(1) // type flags
            val typeOffset = reader.readUnsignedInt()
            val nameOffset = reader.readUnsignedInt()
            val byteSize = reader.readInt()
            reader.skip(4) // index
            val flags = reader.readInt()
            if (formatVersion >= 19) reader.skip(8) // ref type hash
            raw?.add(RawNode(level, typeOffset, nameOffset, byteSize, flags))
        }
        if (!retain) {
            reader.skip(stringSize.toLong())
            return null
        }
        val strings = reader.readBytes(stringSize)
        return raw!!.map { node ->
            TreeNode(
                level = node.level,
                type = resolveTreeString(strings, node.typeOffset),
                name = resolveTreeString(strings, node.nameOffset),
                byteSize = node.byteSize,
                flags = node.flags,
            )
        }.also { nodes ->
            if (nodes.first().level != 0) throw UnsupportedLayout("Embedded type tree has no root node.")
        }
    }

    private fun resolveTreeString(buffer: ByteArray, offset: Long): String {
        if ((offset and 0x8000_0000L) != 0L) {
            return COMMON_TYPE_TREE_STRINGS[offset and 0x7fff_ffffL]
                ?: throw UnsupportedLayout("Unity type tree uses an unknown common-string offset.")
        }
        if (offset !in 0 until buffer.size.toLong()) {
            throw UnsupportedLayout("Unity type tree string offset is outside its buffer.")
        }
        val start = offset.toInt()
        var end = start
        while (end < buffer.size && buffer[end] != 0.toByte()) end++
        if (end == buffer.size) throw UnsupportedLayout("Unity type tree string is unterminated.")
        return String(buffer, start, end - start, StandardCharsets.UTF_8)
    }

    private fun readTexture(reader: LittleEndianReader, info: ObjectInfo, tree: List<TreeNode>): Texture {
        val values = readObjectValues(reader, info, tree)
        fun requiredInt(name: String): Int = values.scalar(name)?.longValue
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: throw UnsupportedLayout("Texture2D is missing a supported $name field.")
        val width = requiredInt("m_Width")
        val height = requiredInt("m_Height")
        val format = requiredInt("m_TextureFormat")
        val mipCount = requiredInt("m_MipCount")
        val completeSize = values.scalar("m_CompleteImageSize")?.longValue
            ?: throw UnsupportedLayout("Texture2D is missing m_CompleteImageSize.")
        val streamedSize = values.scalar("m_StreamData.size")?.longValue ?: 0L
        if (width <= 0 || height <= 0 || mipCount <= 0 || completeSize < 0 || streamedSize < 0) {
            throw UnsupportedLayout("Texture2D has invalid dimensions, mip count, or size metadata.")
        }
        val formatInfo = textureFormatInfo(format)
        return Texture(
            width = width,
            height = height,
            textureFormat = format,
            textureFormatName = formatInfo?.name ?: "Unknown ($format)",
            mipCount = mipCount,
            completeImageSize = completeSize,
            streamedPayloadBytes = streamedSize,
            estimatedResidentBytes = formatInfo?.estimate(width, height, mipCount),
        )
    }

    private fun readQualitySettings(
        reader: LittleEndianReader,
        info: ObjectInfo,
        tree: List<TreeNode>,
    ): QualitySettings? {
        val values = readObjectValues(reader, info, tree)
        val current = values.scalar("m_CurrentQuality")?.longValue?.toInt() ?: return null
        if (current < 0) return null
        fun activeField(vararg names: String): Scalar? =
            names.asSequence().mapNotNull { name ->
                values.scalar("m_QualitySettings[$current].$name") ?: values.scalar(name)
            }.firstOrNull()
        return QualitySettings(
            currentProfileIndex = current,
            textureQuality = activeField(
                "textureQuality",
                "m_TextureQuality",
                "globalTextureMipmapLimit",
                "m_GlobalTextureMipmapLimit",
            )?.longValue?.toInt(),
            streamingMipmapsActive = activeField("streamingMipmapsActive", "m_StreamingMipmapsActive")
                ?.longValue
                ?.let { it != 0L },
            streamingMipmapsBudgetMiB =
                activeField("streamingMipmapsMemoryBudget", "m_StreamingMipmapsMemoryBudget")?.doubleValue,
        )
    }

    private fun readObjectValues(
        reader: LittleEndianReader,
        info: ObjectInfo,
        tree: List<TreeNode>,
    ): ObjectValues {
        reader.seek(info.start)
        val fileLimit = reader.limit
        reader.limit = info.end
        return try {
        val collector = ObjectValues()
        fun subtreeEnd(index: Int): Int {
            val level = tree[index].level
            var cursor = index + 1
            while (cursor < tree.size && tree[cursor].level > level) cursor++
            return cursor
        }
        fun directChildren(index: Int): List<Int> {
            val childLevel = tree[index].level + 1
            val result = mutableListOf<Int>()
            var cursor = index + 1
            val end = subtreeEnd(index)
            while (cursor < end) {
                if (tree[cursor].level == childLevel) result += cursor
                cursor = subtreeEnd(cursor)
            }
            return result
        }
        fun alignedIfNeeded(node: TreeNode) {
            if ((node.flags and ALIGN_BYTES_FLAG) != 0) reader.align(4)
        }
        fun readLeaf(node: TreeNode, path: String) {
            val primitive = primitiveSize(node.type)
            val size = primitive ?: node.byteSize.takeIf { it >= 0 }
                ?: throw UnsupportedLayout("Cannot safely skip variable Unity field $path (${node.type}).")
            if (size < 0) throw UnsupportedLayout("Negative Unity field size at $path.")
            if (collector.isRelevant(path)) {
                val scalar = readScalar(reader, node.type, size)
                    ?: throw UnsupportedLayout("Unsupported numeric layout for $path (${node.type}).")
                collector.put(path, scalar)
            } else {
                reader.skip(size.toLong())
            }
            alignedIfNeeded(node)
        }
        fun readString(node: TreeNode) {
            val size = reader.readInt()
            if (size < 0) throw UnsupportedLayout("Negative Unity string length.")
            reader.skip(size.toLong())
            // Unity strings are a byte array with AlignBytes. Align even if an older tree omitted
            // the child marker: this is part of the supported serialized string representation.
            reader.align(4)
            alignedIfNeeded(node)
        }
        fun readTypelessData(node: TreeNode, path: String) {
            val size = reader.readInt()
            if (size < 0) throw UnsupportedLayout("Negative Unity byte-array length at $path.")
            reader.skip(size.toLong())
            alignedIfNeeded(node)
        }
        fun readArray(nodeIndex: Int, path: String, walk: (Int, String) -> Unit) {
            val children = directChildren(nodeIndex)
            if (children.size < 2 || tree[children[0]].name != "size") {
                throw UnsupportedLayout("Unsupported Unity Array type tree at $path.")
            }
            val count = reader.readInt()
            if (count !in 0..MAX_COLLECTION_ITEMS) {
                throw UnsupportedLayout("Unity Array at $path has an unsafe item count.")
            }
            val dataIndex = children[1]
            val data = tree[dataIndex]
            val itemSize = primitiveSize(data.type) ?: data.byteSize.takeIf { it >= 0 }
            if (subtreeEnd(dataIndex) == dataIndex + 1 && itemSize != null) {
                reader.skip(safeMultiply(count.toLong(), itemSize.toLong(), "Unity Array at $path"))
                alignedIfNeeded(data)
            } else {
                val itemPath = path.removeSuffix(".Array")
                repeat(count) { item -> walk(dataIndex, "$itemPath[$item]") }
            }
            alignedIfNeeded(nodeIndex.let(tree::get))
        }
        lateinit var walk: (Int, String) -> Unit
        walk = fun(index: Int, path: String) {
            val node = tree[index]
            val children = directChildren(index)
            when {
                node.type == "string" -> readString(node)
                node.type == "TypelessData" -> readTypelessData(node, path)
                node.type == "Array" -> readArray(index, path, walk)
                children.isEmpty() -> readLeaf(node, path)
                else -> {
                    children.forEach { child ->
                        val childName = tree[child].name
                        val childPath = if (path.isBlank()) childName else "$path.$childName"
                        walk(child, childPath)
                    }
                    alignedIfNeeded(node)
                }
            }
        }
        directChildren(0).forEach { child -> walk(child, tree[child].name) }
        if (reader.position != info.end) {
            throw UnsupportedLayout("Unity object data does not match its embedded type tree.")
        }
        collector
        } finally {
            reader.limit = fileLimit
        }
    }

    private data class Header(val formatVersion: Int, val metadataStart: Long, val dataOffset: Long)
    private data class SerializedType(val classId: Int, val tree: List<TreeNode>?)
    private data class ObjectInfo(val start: Long, val end: Long, val type: SerializedType)
    private data class TreeNode(
        val level: Int,
        val type: String,
        val name: String,
        val byteSize: Int,
        val flags: Int,
    )

    private data class Scalar(val longValue: Long, val doubleValue: Double)

    private class ObjectValues {
        private val values = linkedMapOf<String, MutableList<Scalar>>()
        fun isRelevant(path: String): Boolean =
            path == "m_Width" ||
                path == "m_Height" ||
                path == "m_TextureFormat" ||
                path == "m_MipCount" ||
                path == "m_CompleteImageSize" ||
                path == "m_StreamData.size" ||
                path == "m_CurrentQuality" ||
                path.endsWith(".textureQuality") ||
                path.endsWith(".m_TextureQuality") ||
                path.endsWith(".globalTextureMipmapLimit") ||
                path.endsWith(".m_GlobalTextureMipmapLimit") ||
                path.endsWith(".streamingMipmapsActive") ||
                path.endsWith(".m_StreamingMipmapsActive") ||
                path.endsWith(".streamingMipmapsMemoryBudget") ||
                path.endsWith(".m_StreamingMipmapsMemoryBudget")

        fun put(path: String, value: Scalar) {
            values.getOrPut(path) { mutableListOf() } += value
        }

        fun scalar(path: String): Scalar? = values[path]?.singleOrNull()
    }

    private class LittleEndianReader(
        private val file: RandomAccessFile,
        var limit: Long,
    ) {
        val position: Long get() = file.filePointer
        fun seek(position: Long) {
            if (position !in 0..limit) throw UnsupportedLayout("Read position is outside file bounds.")
            file.seek(position)
        }

        fun skip(count: Long) {
            if (count < 0 || position > limit - count) throw UnsupportedLayout("Unity metadata read exceeds file bounds.")
            file.seek(position + count)
        }

        fun align(bytes: Int) {
            val padding = ((bytes - (position % bytes)) % bytes).toLong()
            skip(padding)
        }

        fun readUnsignedByte(): Int {
            if (position >= limit) throw UnsupportedLayout("Unexpected end of Unity metadata.")
            return file.read().also {
                if (it < 0) throw UnsupportedLayout("Unexpected end of Unity metadata.")
            }
        }

        fun readInt(): Int = readUnsignedByte() or
            (readUnsignedByte() shl 8) or
            (readUnsignedByte() shl 16) or
            (readUnsignedByte() shl 24)

        fun readUnsignedInt(): Long = readInt().toLong() and 0xffff_ffffL

        fun readLong(): Long {
            val low = readUnsignedInt()
            val high = readUnsignedInt()
            return low or (high shl 32)
        }

        fun readNonNegativeUnsignedLong(): Long {
            val value = readLong()
            if (value < 0) throw UnsupportedLayout("Unity metadata uses an unsupported unsigned 64-bit offset.")
            return value
        }

        fun readBytes(size: Int): ByteArray {
            if (size < 0) throw UnsupportedLayout("Negative Unity byte count.")
            skip(0)
            if (size.toLong() > limit - position) throw UnsupportedLayout("Unity metadata read exceeds file bounds.")
            return ByteArray(size).also { file.readFully(it) }
        }

        fun readNullTerminatedUtf8(maxBytes: Int): String {
            val bytes = ArrayList<Byte>(32)
            repeat(maxBytes) {
                val value = readUnsignedByte()
                if (value == 0) return String(bytes.toByteArray(), StandardCharsets.UTF_8)
                bytes += value.toByte()
            }
            throw UnsupportedLayout("Unity version string is too long or unterminated.")
        }
    }

    private data class TextureFormatInfo(
        val name: String,
        val bytesPerPixel: Int? = null,
        val blockWidth: Int = 4,
        val blockHeight: Int = 4,
        val blockBytes: Int? = null,
    ) {
        fun estimate(width: Int, height: Int, mipCount: Int): Long {
            var total = 0L
            repeat(mipCount) { level ->
                val levelWidth = max(1, width shr minOf(level, 30))
                val levelHeight = max(1, height shr minOf(level, 30))
                val levelBytes = if (blockBytes != null) {
                    safeMultiply(
                        ceil(levelWidth.toDouble() / blockWidth).toLong(),
                        ceil(levelHeight.toDouble() / blockHeight).toLong(),
                        "compressed texture blocks",
                    ).let { blocks -> safeMultiply(blocks, blockBytes.toLong(), "compressed texture bytes") }
                } else {
                    safeMultiply(
                        safeMultiply(levelWidth.toLong(), levelHeight.toLong(), "texture pixels"),
                        requireNotNull(bytesPerPixel).toLong(),
                        "texture bytes",
                    )
                }
                total = safeAdd(total, levelBytes, "texture resident estimate")
            }
            return total
        }
    }

    private fun Texture.estimatedResidentBytesAt(limit: Int): Long? {
        val info = textureFormatInfo(textureFormat) ?: return null
        val reducedWidth = max(1, width shr minOf(limit, 30))
        val reducedHeight = max(1, height shr minOf(limit, 30))
        return info.estimate(reducedWidth, reducedHeight, max(1, mipCount - limit))
    }

    private fun textureFormatInfo(format: Int): TextureFormatInfo? = TEXTURE_FORMATS[format]

    private fun primitiveSize(type: String): Int? = when (type) {
        "bool", "char", "SInt8", "UInt8" -> 1
        "short", "unsigned short", "SInt16", "UInt16" -> 2
        "int", "unsigned int", "SInt32", "UInt32", "Type*", "float" -> 4
        "long long", "unsigned long long", "SInt64", "UInt64", "FileSize", "double" -> 8
        else -> null
    }

    private fun readScalar(reader: LittleEndianReader, type: String, size: Int): Scalar? = when (type) {
        "bool", "char", "SInt8" -> reader.readUnsignedByte().toByte().toLong().let { Scalar(it, it.toDouble()) }
        "UInt8" -> reader.readUnsignedByte().toLong().let { Scalar(it, it.toDouble()) }
        "short", "SInt16" -> Scalar(reader.readUnsignedInt16().toShort().toLong(), 0.0)
        "unsigned short", "UInt16" -> Scalar(reader.readUnsignedInt16().toLong(), 0.0)
        "int", "SInt32" -> Scalar(reader.readInt().toLong(), 0.0)
        "unsigned int", "UInt32", "Type*" -> Scalar(reader.readUnsignedInt(), 0.0)
        "long long", "SInt64", "unsigned long long", "UInt64", "FileSize" -> {
            val value = reader.readLong()
            Scalar(value, value.toDouble())
        }
        "float" -> {
            val value = Float.fromBits(reader.readInt()).toDouble()
            Scalar(value.toLong(), value)
        }
        "double" -> {
            val value = Double.fromBits(reader.readLong())
            Scalar(value.toLong(), value)
        }
        else -> if (size == 4) {
            // The relevant Unity fields above are always emitted using an explicit primitive name.
            null
        } else null
    }

    private fun LittleEndianReader.readUnsignedInt16(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)

    private fun safeAdd(left: Long, right: Long, label: String): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) throw UnsupportedLayout("$label overflows a supported size.")
        return left + right
    }

    private fun safeMultiply(left: Long, right: Long, label: String): Long {
        if (left < 0 || right < 0 || (left != 0L && right > Long.MAX_VALUE / left)) {
            throw UnsupportedLayout("$label overflows a supported size.")
        }
        return left * right
    }

    private class UnsupportedLayout(message: String) : IllegalArgumentException(message)

    private const val ALIGN_BYTES_FLAG = 0x4000
    private val SUPPORTED_UNITY_VERSION = Regex("""^(2019\.4|2020\.3|2021\.3|2022\.3)\.\d+[A-Za-z]\d+.*$""")
    private val DESKTOP_TARGET_PLATFORMS = setOf(2, 3, 4, 5, 24, 25, 26, 27)

    // Unity's documented common-string table. Asset-specific fields are normally in the embedded
    // buffer; unknown common-string offsets remain an explicit unsupported layout.
    private val COMMON_TYPE_TREE_STRINGS = mapOf(
        0L to "AABB",
        5L to "AnimationClip",
        19L to "AnimationCurve",
        34L to "AnimationState",
        49L to "Array",
        55L to "Base",
        60L to "BitField",
        69L to "bitset",
        76L to "bool",
        81L to "char",
        86L to "ColorRGBA",
        96L to "Component",
        106L to "data",
        111L to "deque",
        117L to "double",
        124L to "dynamic_array",
        138L to "FastPropertyName",
        155L to "first",
        161L to "float",
        167L to "Font",
        172L to "GameObject",
        183L to "Generic Mono",
        196L to "GradientNEW",
        208L to "GUID",
        213L to "GUIStyle",
        222L to "int",
        226L to "list",
        231L to "long long",
        241L to "map",
        245L to "Matrix4x4f",
        256L to "MdFour",
        263L to "MonoBehaviour",
        276L to "MonoScript",
        287L to "m_ByteSize",
        298L to "m_Curve",
        306L to "m_EditorClassIdentifier",
        330L to "m_EditorHideFlags",
        348L to "m_Enabled",
        358L to "m_ExtensionPtr",
        373L to "m_GameObject",
        386L to "m_Index",
        394L to "m_IsArray",
        404L to "m_IsStatic",
        415L to "m_MetaFlag",
        426L to "m_Name",
        433L to "m_ObjectHideFlags",
        452L to "m_PrefabInternal",
        469L to "m_PrefabParentObject",
        490L to "m_Script",
        499L to "m_StaticEditorFlags",
        519L to "m_Type",
        526L to "m_Version",
        536L to "Object",
        543L to "pair",
        548L to "PPtr<Component>",
        564L to "PPtr<GameObject>",
        581L to "PPtr<Material>",
        596L to "PPtr<MonoBehaviour>",
        616L to "PPtr<MonoScript>",
        633L to "PPtr<Object>",
        646L to "PPtr<Prefab>",
        659L to "PPtr<Sprite>",
        672L to "PPtr<TextAsset>",
        688L to "PPtr<Texture>",
        702L to "PPtr<Texture2D>",
        718L to "PPtr<Transform>",
        734L to "Prefab",
        741L to "Quaternionf",
        753L to "Rectf",
        759L to "RectInt",
        767L to "RectOffset",
        778L to "second",
        785L to "set",
        789L to "short",
        795L to "size",
        800L to "SInt16",
        807L to "SInt32",
        814L to "SInt64",
        821L to "SInt8",
        827L to "staticvector",
        840L to "string",
        847L to "TextAsset",
        857L to "TextMesh",
        866L to "Texture",
        874L to "Texture2D",
        884L to "Transform",
        894L to "TypelessData",
        907L to "UInt16",
        914L to "UInt32",
        921L to "UInt64",
        928L to "UInt8",
        934L to "unsigned int",
        947L to "unsigned long long",
        966L to "unsigned short",
        981L to "vector",
        988L to "Vector2f",
        997L to "Vector3f",
        1006L to "Vector4f",
        1015L to "m_ScriptingClassIdentifier",
        1042L to "Gradient",
        1051L to "Type*",
        1057L to "int",
    )

    private val TEXTURE_FORMATS = mapOf(
        1 to TextureFormatInfo("Alpha8", bytesPerPixel = 1),
        2 to TextureFormatInfo("ARGB4444", bytesPerPixel = 2),
        3 to TextureFormatInfo("RGB24", bytesPerPixel = 3),
        4 to TextureFormatInfo("RGBA32", bytesPerPixel = 4),
        5 to TextureFormatInfo("ARGB32", bytesPerPixel = 4),
        7 to TextureFormatInfo("RGB565", bytesPerPixel = 2),
        9 to TextureFormatInfo("R16", bytesPerPixel = 2),
        10 to TextureFormatInfo("DXT1 / BC1", blockBytes = 8),
        11 to TextureFormatInfo("DXT3 / BC2", blockBytes = 16),
        12 to TextureFormatInfo("DXT5 / BC3", blockBytes = 16),
        13 to TextureFormatInfo("RGBA4444", bytesPerPixel = 2),
        14 to TextureFormatInfo("BGRA32", bytesPerPixel = 4),
        15 to TextureFormatInfo("RHalf", bytesPerPixel = 2),
        16 to TextureFormatInfo("RGHalf", bytesPerPixel = 4),
        17 to TextureFormatInfo("RGBAHalf", bytesPerPixel = 8),
        18 to TextureFormatInfo("RFloat", bytesPerPixel = 4),
        19 to TextureFormatInfo("RGFloat", bytesPerPixel = 8),
        20 to TextureFormatInfo("RGBAFloat", bytesPerPixel = 16),
        21 to TextureFormatInfo("YUY2", bytesPerPixel = 2),
        22 to TextureFormatInfo("RGB9e5", bytesPerPixel = 4),
        24 to TextureFormatInfo("BC6H", blockBytes = 16),
        25 to TextureFormatInfo("BC7", blockBytes = 16),
        26 to TextureFormatInfo("BC4", blockBytes = 8),
        27 to TextureFormatInfo("BC5", blockBytes = 16),
        28 to TextureFormatInfo("DXT1Crunched / BC1", blockBytes = 8),
        29 to TextureFormatInfo("DXT5Crunched / BC3", blockBytes = 16),
        30 to TextureFormatInfo("PVRTC RGB 2bpp", blockWidth = 8, blockHeight = 4, blockBytes = 8),
        31 to TextureFormatInfo("PVRTC RGBA 2bpp", blockWidth = 8, blockHeight = 4, blockBytes = 8),
        32 to TextureFormatInfo("PVRTC RGB 4bpp", blockBytes = 8),
        33 to TextureFormatInfo("PVRTC RGBA 4bpp", blockBytes = 8),
        34 to TextureFormatInfo("ETC RGB4", blockBytes = 8),
        35 to TextureFormatInfo("ATC RGB4", blockBytes = 8),
        36 to TextureFormatInfo("ATC RGBA8", blockBytes = 16),
        41 to TextureFormatInfo("EAC R", blockBytes = 8),
        42 to TextureFormatInfo("EAC R signed", blockBytes = 8),
        43 to TextureFormatInfo("EAC RG", blockBytes = 16),
        44 to TextureFormatInfo("EAC RG signed", blockBytes = 16),
        45 to TextureFormatInfo("ETC2 RGB", blockBytes = 8),
        46 to TextureFormatInfo("ETC2 RGBA1", blockBytes = 8),
        47 to TextureFormatInfo("ETC2 RGBA8", blockBytes = 16),
        48 to TextureFormatInfo("ASTC 4x4", blockBytes = 16),
        49 to TextureFormatInfo("ASTC 5x5", blockWidth = 5, blockHeight = 5, blockBytes = 16),
        50 to TextureFormatInfo("ASTC 6x6", blockWidth = 6, blockHeight = 6, blockBytes = 16),
        51 to TextureFormatInfo("ASTC 8x8", blockWidth = 8, blockHeight = 8, blockBytes = 16),
        52 to TextureFormatInfo("ASTC 10x10", blockWidth = 10, blockHeight = 10, blockBytes = 16),
        53 to TextureFormatInfo("ASTC 12x12", blockWidth = 12, blockHeight = 12, blockBytes = 16),
        63 to TextureFormatInfo("R8", bytesPerPixel = 1),
        66 to TextureFormatInfo("ASTC HDR 4x4", blockBytes = 16),
        67 to TextureFormatInfo("ASTC HDR 5x5", blockWidth = 5, blockHeight = 5, blockBytes = 16),
        68 to TextureFormatInfo("ASTC HDR 6x6", blockWidth = 6, blockHeight = 6, blockBytes = 16),
        69 to TextureFormatInfo("ASTC HDR 8x8", blockWidth = 8, blockHeight = 8, blockBytes = 16),
        70 to TextureFormatInfo("ASTC HDR 10x10", blockWidth = 10, blockHeight = 10, blockBytes = 16),
        71 to TextureFormatInfo("ASTC HDR 12x12", blockWidth = 12, blockHeight = 12, blockBytes = 16),
    )
}

/** Pure policy kept outside the parser so boundary and threshold behavior can be unit tested. */
object UnityTextureRiskPolicy {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    fun evaluate(
        projectedResidentBytes: Long,
        physicalRamBytes: Long?,
        textures: List<UnityTextureMemoryAnalyzer.Texture>,
    ): UnityTextureMemoryAnalyzer.Risk {
        val critical = projectedResidentBytes > 3L * GIB ||
            (physicalRamBytes != null && physicalRamBytes > 0 &&
                projectedResidentBytes.toDouble() / physicalRamBytes > 0.40)
        val high = projectedResidentBytes > 1_536L * MIB ||
            (physicalRamBytes != null && physicalRamBytes > 0 &&
                projectedResidentBytes.toDouble() / physicalRamBytes > 0.25)
        val flags = buildList {
            if (textures.any { it.isAtLeast8k }) add("At least one texture is 8K or larger.")
            val uncompressed1080p = textures.count { it.isUncompressed1080pOrLarger }
            if (uncompressed1080p > 100) add("$uncompressed1080p uncompressed textures are 1080p or larger.")
        }
        return UnityTextureMemoryAnalyzer.Risk(
            level = when {
                critical -> UnityTextureMemoryAnalyzer.RiskLevel.Critical
                high -> UnityTextureMemoryAnalyzer.RiskLevel.High
                else -> UnityTextureMemoryAnalyzer.RiskLevel.None
            },
            projectedResidentBytes = projectedResidentBytes,
            physicalRamBytes = physicalRamBytes,
            flags = flags,
        )
    }

    fun recommend(
        projections: List<UnityTextureMemoryAnalyzer.Projection>,
        physicalRamBytes: Long?,
    ): UnityTextureMemoryAnalyzer.Recommendation {
        if (physicalRamBytes == null || physicalRamBytes <= 0) {
            return UnityTextureMemoryAnalyzer.Recommendation(
                value = null,
                meetsTwentyPercentTarget = false,
                reason = "Physical RAM was unavailable, so AGM cannot make a safe recommendation.",
            )
        }
        if (projections.any { it.unknownFormatTextureCount > 0 }) {
            return UnityTextureMemoryAnalyzer.Recommendation(
                value = null,
                meetsTwentyPercentTarget = false,
                reason = "One or more texture formats cannot be projected safely.",
            )
        }
        val target = physicalRamBytes / 5
        val selected = projections.sortedBy { it.limit }.firstOrNull { it.estimatedResidentBytes <= target }
            ?: projections.maxByOrNull { it.limit }
        val value = when (selected?.limit) {
            0 -> "off"
            1, 2, 3 -> selected.limit.toString()
            else -> null
        }
        return UnityTextureMemoryAnalyzer.Recommendation(
            value = value,
            meetsTwentyPercentTarget = selected?.estimatedResidentBytes?.let { it <= target } == true,
            reason = if (selected?.estimatedResidentBytes ?: Long.MAX_VALUE <= target) {
                "Smallest projection at or below 20% of physical RAM."
            } else {
                "Even limit 3 remains above 20% of physical RAM; limit 3 is the capped recommendation."
            },
        )
    }
}
