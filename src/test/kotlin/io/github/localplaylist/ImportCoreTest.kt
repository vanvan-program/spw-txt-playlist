package io.github.localplaylist

import kotlinx.coroutines.runBlocking
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ImportCoreTest {
    private fun temp() = Files.createTempDirectory("spw-import-test-")
    private fun song(dir: Path, name: String) = Files.write(dir.resolve(name), byteArrayOf(1)).toString()

    @Test fun preservesLinesAndDeduplicatesWindowsPaths() {
        val dir = temp()
        try {
            val chinese = song(dir, "中文 & test.mp3")
            val japanese = song(dir, "やさしい雨.flac")
            val txt = dir.resolve("Loved.txt")
            Files.writeString(txt, "\uFEFF$chinese\r\n\r\n\"$japanese\"\r\n${chinese.uppercase()}\r\n${dir.resolve("missing.mp3")}\r\nrelative.mp3")
            val rows = TxtReader.read(txt)
            assertEquals(listOf(1, 3, 4, 5, 6), rows.map { it.line })
            assertEquals(listOf(RowState.NEEDS_SCAN, RowState.NEEDS_SCAN, RowState.DUPLICATE, RowState.MISSING, RowState.INVALID), rows.map { it.state })
            assertEquals(TxtReader.key(chinese), TxtReader.key(chinese.replace('\\', '/')))
            assertEquals(TxtReader.key(chinese), TxtReader.key("file:///" + chinese.replace('\\', '/')))
            val percent = song(dir, "literal %20 #歌.mp3")
            assertEquals(TxtReader.key(percent), TxtReader.key("file:///" + percent.replace('\\', '/')))
            assertTrue(TxtReader.index(listOf(TrackRef("remote", "https://example.test/a.mp3", "remote"))).isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun recognizesBomAndExplicitEncoding() {
        val dir = temp()
        try {
            val path = song(dir, "中文.mp3")
            val txt = dir.resolve("list.txt")
            for ((charset, bom) in listOf("UTF-8" to byteArrayOf(-17, -69, -65), "UTF-16LE" to byteArrayOf(-1, -2), "UTF-16BE" to byteArrayOf(-2, -1))) {
                Files.write(txt, bom + path.toByteArray(Charset.forName(charset)))
                assertEquals(path, TxtReader.read(txt).single().path)
            }
            Files.write(txt, path.toByteArray(Charset.forName("GB18030")))
            assertFailsWith<IllegalArgumentException> { TxtReader.read(txt) }
            assertEquals(path, TxtReader.read(txt, "GB18030").single().path)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun importsInOrderAndReportsCollectionFailure() = runBlocking {
        val dir = temp()
        try {
            val paths = (1..4).map { song(dir, "$it.mp3") }
            val known = TrackRef("one", paths[0], "one")
            val host = FakeHost(mutableListOf(known))
            host.bad = paths[2]
            val input = listOf(paths[3], paths[0], paths[2], paths[1]).mapIndexed { i, p -> ImportRow(i + 1, p, RowState.NEEDS_SCAN) }
            val report = ImportEngine(host).run(input, null, "Loved") { _, _, _ -> }
            assertEquals(listOf(paths[3], paths[0], paths[1]), host.committed.map { it.path })
            assertEquals(3, report.added)
            assertEquals(2, report.newlyIndexed)
            assertEquals(paths[2] + "\r\n", TxtReader.exportFailures(report))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun allInvalidDoesNotCreatePlaylist() = runBlocking {
        val host = FakeHost(mutableListOf())
        val result = ImportEngine(host).run(listOf(ImportRow(1, "missing", RowState.MISSING)), null, "Empty") { _, _, _ -> }
        assertEquals(0, host.commits)
        assertEquals(0, result.added)
        assertNull(result.playlist)
    }

    @Test fun failureAndPermissionDenialDoNotReportSuccess() = runBlocking {
        val dir = temp()
        try {
            val path = song(dir, "a.mp3")
            val host = FakeHost(mutableListOf(TrackRef("a", path, "a")))
            val input = listOf(ImportRow(1, path, RowState.READY))
            host.failCommit = true
            val failed = ImportEngine(host).run(input, null, "test") { _, _, _ -> }
            assertEquals(RowState.FAILED, failed.rows.single().state)
            assertEquals(0, failed.added)
            host.allowed = false
            assertFailsWith<SecurityException> { ImportEngine(host).run(input, null, "test") { _, _, _ -> } }
            Unit
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun deletedAfterPreviewIsReported() = runBlocking {
        val dir = temp()
        try {
            val path = song(dir, "a.mp3")
            Files.delete(Path.of(path))
            val host = FakeHost(mutableListOf(TrackRef("a", path, "a")))
            val result = ImportEngine(host).run(listOf(ImportRow(1, path, RowState.READY)), null, "test") { _, _, _ -> }
            assertEquals(RowState.FAILED, result.rows.single().state)
            assertEquals(0, host.commits)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun libraryAmbiguityIsNotGuessed() {
        val row = ImportRow(1, "C:\\a.mp3", RowState.NEEDS_SCAN)
        val result = TxtReader.markLibrary(listOf(row), listOf(TrackRef("1", row.path, "a"), TrackRef("2", row.path, "b")))
        assertEquals(RowState.INVALID, result.single().state)
    }
}

private class FakeHost(private val library: MutableList<TrackRef>) : HostPort {
    var bad: String? = null
    var allowed = true
    var failCommit = false
    var commits = 0
    var committed = emptyList<TrackRef>()
    override fun checkAccess() { if (!allowed) throw SecurityException("拒绝授权") }
    override suspend fun diagnose() = "test"
    override suspend fun tracks() = library.toList()
    override suspend fun playlists() = emptyList<PlaylistRef>()
    override suspend fun collect(path: String): TrackRef {
        if (path == bad) error("无法解析音频")
        return TrackRef("id-${library.size}", path, path).also { library += it }
    }
    override suspend fun commit(target: PlaylistRef?, title: String, tracks: List<TrackRef>): CommitResult {
        commits++
        if (failCommit) error("模拟事务失败")
        committed = tracks
        return CommitResult(PlaylistRef("test", title), tracks.map { it.id }.toSet(), emptySet())
    }
}
