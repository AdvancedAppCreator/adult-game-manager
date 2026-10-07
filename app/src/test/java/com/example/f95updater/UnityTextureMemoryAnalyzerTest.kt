package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class UnityTextureMemoryAnalyzerTest {
    @Test
    fun discoversOnlyMatchingWindowsDataLayoutUnderRoot() {
        withFixtureRoot { root ->
            val game = File(root, "My Game.exe").apply { writeBytes(byteArrayOf()) }
            val data = File(root, "My Game_Data").apply { mkdirs() }
            File(data, "resources.assets").writeBytes(byteArrayOf())
            File(data, "resources.assets.resS").writeBytes(byteArrayOf())
            File(data, "globalgamemanagers").writeBytes(byteArrayOf())
            File(root, "Unrelated_Data").mkdirs()

            val result = UnityTextureMemoryAnalyzer.discover(root)

            assertTrue(result is UnityTextureMemoryAnalyzer.Discovery.Found)
            val found = result as UnityTextureMemoryAnalyzer.Discovery.Found
            assertEquals(game.canonicalFile, found.files.executable)
            assertEquals(File(data, "resources.assets.resS").canonicalFile, found.files.resourcesAssetsResS)
            assertEquals(File(data, "globalgamemanagers").canonicalFile, found.files.globalGameManagers)
        }
    }

    @Test
    fun preferredExecutableWinsWhenGameFolderContainsMultipleUnityBuilds() {
        withFixtureRoot { root ->
            fun build(name: String): File {
                val executable = File(root, "$name.exe").apply { writeBytes(byteArrayOf()) }
                File(root, "${name}_Data").apply {
                    mkdirs()
                    File(this, "resources.assets").writeBytes(byteArrayOf())
                }
                return executable
            }
            build("Alpha")
            val preferred = build("Beta")

            val result = UnityTextureMemoryAnalyzer.discover(root, preferred)

            assertTrue(result is UnityTextureMemoryAnalyzer.Discovery.Found)
            assertEquals(
                preferred.canonicalFile,
                (result as UnityTextureMemoryAnalyzer.Discovery.Found).files.executable,
            )
        }
    }

    @Test
    fun parsesEmbeddedTypeTreeMetadataWithoutOpeningResSPayload() {
        withFixtureRoot { root ->
            val files = writeSupportedFixture(root)
            // A large opaque payload proves the parser only consumes SerializedFile metadata/object data.
            files.resourcesAssetsResS!!.writeBytes(ByteArray(32 * 1024) { 0x5a })

            val result = UnityTextureMemoryAnalyzer.analyze(files, physicalRamBytes = 16L * GIB)

            assertTrue(result.toString(), result is UnityTextureMemoryAnalyzer.Result.Success)
            val report = (result as UnityTextureMemoryAnalyzer.Result.Success).report
            assertEquals(listOf("2021.3.0f1"), report.unityVersions.toList())
            assertEquals(1, report.textures.size)
            assertEquals(4_096L, report.textures.single().streamedPayloadBytes)
            assertEquals(5_505_024L, report.projections.first { it.limit == 0 }.estimatedResidentBytes)
            assertEquals(1_310_720L, report.projections.first { it.limit == 1 }.estimatedResidentBytes)
            assertEquals(0, report.currentQuality!!.textureQuality)
            assertEquals(true, report.currentQuality.streamingMipmapsActive)
            assertEquals(16.0, report.currentQuality.streamingMipmapsBudgetMiB!!, 0.0)
            assertEquals("off", report.recommendation.value)
        }
    }

    @Test
    fun parsesLegacy2019LtsSerializedFileLayout() {
        withFixtureRoot { root ->
            val files = writeSupportedFixture(root, unityVersion = "2019.4.40f1", formatVersion = 17)

            val result = UnityTextureMemoryAnalyzer.analyze(files, physicalRamBytes = 8L * GIB)

            assertTrue(result.toString(), result is UnityTextureMemoryAnalyzer.Result.Success)
            val report = (result as UnityTextureMemoryAnalyzer.Result.Success).report
            assertEquals(setOf("2019.4.40f1"), report.unityVersions)
            assertEquals(1, report.textures.size)
        }
    }

    @Test
    fun parsesUnity2022GlobalTextureMipmapLimit() {
        withFixtureRoot { root ->
            val files = writeSupportedFixture(
                root,
                unityVersion = "2022.3.20f1",
                qualityFieldName = "globalTextureMipmapLimit",
            )

            val result = UnityTextureMemoryAnalyzer.analyze(files, physicalRamBytes = 8L * GIB)

            assertTrue(result is UnityTextureMemoryAnalyzer.Result.Success)
            assertEquals(
                0,
                (result as UnityTextureMemoryAnalyzer.Result.Success).report.currentQuality?.textureQuality,
            )
        }
    }

    @Test
    fun reportsUnsupportedVersionAndTruncatedObjectAsUnsupported() {
        withFixtureRoot { root ->
            val future = writeSupportedFixture(root, unityVersion = "2023.1.0f1")
            assertTrue(
                UnityTextureMemoryAnalyzer.analyze(future, physicalRamBytes = GIB)
                    is UnityTextureMemoryAnalyzer.Result.Unsupported,
            )

            val noTree = writeSupportedFixture(File(root, "no-tree"))
            val noTreeBytes = noTree.resourcesAssets.readBytes()
            val typeTreeFlag = 48 + "2021.3.0f1".toByteArray(StandardCharsets.UTF_8).size + 1 + 4
            noTreeBytes[typeTreeFlag] = 0
            noTree.resourcesAssets.writeBytes(noTreeBytes)
            assertTrue(
                UnityTextureMemoryAnalyzer.analyze(noTree, physicalRamBytes = GIB)
                    is UnityTextureMemoryAnalyzer.Result.Unsupported,
            )

            val truncated = writeSupportedFixture(File(root, "truncated"))
            truncated.resourcesAssets.writeBytes(truncated.resourcesAssets.readBytes().dropLast(4).toByteArray())
            assertTrue(
                UnityTextureMemoryAnalyzer.analyze(truncated, physicalRamBytes = GIB)
                    is UnityTextureMemoryAnalyzer.Result.Unsupported,
            )
        }
    }

    @Test
    fun appliesRiskThresholdsFlagsAndCappedRecommendation() {
        val uncompressed1080p = List(101) {
            UnityTextureMemoryAnalyzer.Texture(
                width = 1_920,
                height = 1_080,
                textureFormat = 4,
                textureFormatName = "RGBA32",
                mipCount = 1,
                completeImageSize = 0,
                streamedPayloadBytes = 0,
                estimatedResidentBytes = 1,
            )
        } + UnityTextureMemoryAnalyzer.Texture(
            width = 8_192,
            height = 1,
            textureFormat = 10,
            textureFormatName = "DXT1 / BC1",
            mipCount = 1,
            completeImageSize = 0,
            streamedPayloadBytes = 0,
            estimatedResidentBytes = 1,
        )
        val atHighBoundary = UnityTextureRiskPolicy.evaluate(
            projectedResidentBytes = 1_536L * MIB,
            physicalRamBytes = 16L * GIB,
            textures = uncompressed1080p,
        )
        val critical = UnityTextureRiskPolicy.evaluate(
            projectedResidentBytes = 3L * GIB + 1,
            physicalRamBytes = 16L * GIB,
            textures = uncompressed1080p,
        )
        val high = UnityTextureRiskPolicy.evaluate(
            projectedResidentBytes = 1_536L * MIB + 1,
            physicalRamBytes = 16L * GIB,
            textures = emptyList(),
        )
        val percentageHigh = UnityTextureRiskPolicy.evaluate(
            projectedResidentBytes = 1_200L * MIB,
            physicalRamBytes = 4L * GIB,
            textures = emptyList(),
        )
        val percentageCritical = UnityTextureRiskPolicy.evaluate(
            projectedResidentBytes = 1_700L * MIB,
            physicalRamBytes = 4L * GIB,
            textures = emptyList(),
        )

        assertEquals(UnityTextureMemoryAnalyzer.RiskLevel.None, atHighBoundary.level)
        assertEquals(UnityTextureMemoryAnalyzer.RiskLevel.High, high.level)
        assertEquals(UnityTextureMemoryAnalyzer.RiskLevel.High, percentageHigh.level)
        assertEquals(UnityTextureMemoryAnalyzer.RiskLevel.Critical, percentageCritical.level)
        assertEquals(UnityTextureMemoryAnalyzer.RiskLevel.Critical, critical.level)
        assertTrue(critical.flags.any { it.contains("8K") })
        assertTrue(critical.flags.any { it.contains("101 uncompressed") })

        val recommendation = UnityTextureRiskPolicy.recommend(
            listOf(
                projection(0, 9L * GIB),
                projection(1, 7L * GIB),
                projection(2, 5L * GIB),
                projection(3, 4L * GIB),
            ),
            physicalRamBytes = 16L * GIB,
        )
        assertEquals("3", recommendation.value)
        assertFalse(recommendation.meetsTwentyPercentTarget)

        val smallest = UnityTextureRiskPolicy.recommend(
            listOf(
                projection(0, 3L * GIB),
                projection(1, GIB),
                projection(2, GIB / 2),
                projection(3, GIB / 4),
            ),
            physicalRamBytes = 8L * GIB,
        )
        assertEquals("1", smallest.value)
        assertTrue(smallest.meetsTwentyPercentTarget)
    }

    private fun projection(limit: Int, bytes: Long) =
        UnityTextureMemoryAnalyzer.Projection(limit, bytes, unknownFormatTextureCount = 0)

    private fun writeSupportedFixture(
        root: File,
        unityVersion: String = "2021.3.0f1",
        formatVersion: Int = 22,
        qualityFieldName: String = "textureQuality",
    ): UnityTextureMemoryAnalyzer.UnityGameFiles {
        root.mkdirs()
        val executable = File(root, "Fixture.exe").apply { writeBytes(byteArrayOf()) }
        val data = File(root, "Fixture_Data").apply { mkdirs() }
        val texture = textureObject()
        val quality = qualityObject()
        val resources = File(data, "resources.assets").apply {
            writeBytes(serializedFile(unityVersion, formatVersion, 28, textureTree(), texture))
        }
        val global = File(data, "globalgamemanagers").apply {
            writeBytes(
                serializedFile(
                    unityVersion,
                    formatVersion,
                    47,
                    qualityTree(qualityFieldName),
                    quality,
                )
            )
        }
        val resS = File(data, "resources.assets.resS").apply { writeBytes(byteArrayOf()) }
        return UnityTextureMemoryAnalyzer.UnityGameFiles(executable, data, resources, resS, global)
    }

    private fun textureObject(): ByteArray = Writer().apply {
        int(4_096)
        int(2_048)
        int(12_345)
        int(10) // TextureFormat.DXT1 / BC1
        int(3)
        long(0)
        int(4_096)
        string("resources.assets.resS")
    }.bytes()

    private fun qualityObject(): ByteArray = Writer().apply {
        int(0) // m_CurrentQuality
        int(1) // m_QualitySettings.Array.size
        int(0) // textureQuality
        byte(1) // streamingMipmapsActive
        align(4)
        float(16f)
    }.bytes()

    private fun textureTree(): List<Node> = listOf(
        Node("Texture2D", "Base", 0),
        Node("SInt32", "m_Width", 1, 4),
        Node("SInt32", "m_Height", 1, 4),
        Node("SInt32", "m_CompleteImageSize", 1, 4),
        Node("SInt32", "m_TextureFormat", 1, 4),
        Node("SInt32", "m_MipCount", 1, 4),
        Node("StreamingInfo", "m_StreamData", 1),
        Node("UInt64", "offset", 2, 8),
        Node("UInt32", "size", 2, 4),
        Node("string", "path", 2),
    )

    private fun qualityTree(textureLimitFieldName: String): List<Node> = listOf(
        Node("QualitySettings", "Base", 0),
        Node("SInt32", "m_CurrentQuality", 1, 4),
        Node("vector", "m_QualitySettings", 1),
        Node("Array", "Array", 2),
        Node("SInt32", "size", 3, 4),
        Node("QualityLevel", "data", 3),
        Node("SInt32", textureLimitFieldName, 4, 4),
        Node("bool", "streamingMipmapsActive", 4, 1, ALIGN),
        Node("float", "streamingMipmapsMemoryBudget", 4, 4),
    )

    private fun serializedFile(
        unityVersion: String,
        formatVersion: Int,
        classId: Int,
        tree: List<Node>,
        objectData: ByteArray,
    ): ByteArray {
        val metadata = Writer().apply {
            repeat(if (formatVersion >= 22) 48 else 20) { byte(0) }
            cString(unityVersion)
            int(5) // StandaloneWindows
            byte(1) // embedded type tree
            int(1)
            int(classId)
            byte(0) // stripped
            short(0)
            repeat(16) { byte(0) } // old type hash
            typeTree(tree, formatVersion)
            if (formatVersion >= 21) int(0) // non-reference type dependency count
            int(1) // object count
            align(4)
            long(1) // path ID
            if (formatVersion >= 22) long(0) else int(0) // relative data start
            int(objectData.size)
            int(0) // type index
        }
        val dataOffset = metadata.size
        val file = metadata.bytes() + objectData
        val headerSize = if (formatVersion >= 22) 48 else 20
        patchInt(file, 0, dataOffset - headerSize)
        patchInt(file, 4, file.size)
        patchInt(file, 8, formatVersion)
        patchInt(file, 12, dataOffset)
        file[16] = 0
        if (formatVersion >= 22) {
            patchInt(file, 20, dataOffset - headerSize)
            patchLong(file, 24, file.size.toLong())
            patchLong(file, 32, dataOffset.toLong())
        }
        return file
    }

    private data class Node(
        val type: String,
        val name: String,
        val level: Int,
        val byteSize: Int = -1,
        val flags: Int = 0,
    )

    private class Writer {
        private val output = ByteArrayOutputStream()
        val size: Int get() = output.size()
        fun bytes(): ByteArray = output.toByteArray()
        fun byte(value: Int) = output.write(value)
        fun short(value: Int) {
            byte(value)
            byte(value ushr 8)
        }
        fun int(value: Int) {
            byte(value)
            byte(value ushr 8)
            byte(value ushr 16)
            byte(value ushr 24)
        }
        fun long(value: Long) {
            int(value.toInt())
            int((value ushr 32).toInt())
        }
        fun float(value: Float) = int(value.toRawBits())
        fun align(bytes: Int) {
            while (size % bytes != 0) byte(0)
        }
        fun cString(value: String) {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            byte(0)
        }
        fun string(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            int(bytes.size)
            output.write(bytes)
            align(4)
        }
        fun typeTree(nodes: List<Node>, formatVersion: Int) {
            val strings = ByteArrayOutputStream()
            val offsets = linkedMapOf<String, Int>()
            fun offset(value: String): Int = offsets.getOrPut(value) {
                val result = strings.size()
                strings.write(value.toByteArray(StandardCharsets.UTF_8))
                strings.write(0)
                result
            }
            nodes.forEach { offset(it.type); offset(it.name) }
            val buffer = strings.toByteArray()
            int(nodes.size)
            int(buffer.size)
            nodes.forEach { node ->
                short(0)
                byte(node.level)
                byte(0)
                int(offset(node.type))
                int(offset(node.name))
                int(node.byteSize)
                int(0)
                int(node.flags)
                if (formatVersion >= 19) long(0) // ref type hash
            }
            output.write(buffer)
        }
    }

    private fun patchInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun patchLong(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private inline fun withFixtureRoot(block: (File) -> Unit) {
        val root = File("build/unity-texture-tests/${System.nanoTime()}")
        try {
            root.mkdirs()
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
        const val ALIGN = 0x4000
    }
}
