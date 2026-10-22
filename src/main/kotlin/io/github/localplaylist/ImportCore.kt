package io.github.localplaylist

import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class RowState(val label: String) {
    READY("可导入"), NEEDS_SCAN("尚未收录"), MISSING("文件缺失"), DUPLICATE("清单重复"),
    INVALID("路径无效"), ALREADY_PRESENT("歌单已有"), ADDED("已加入歌单"), FAILED("导入失败")
}

data class ImportRow(val line: Int, val path: String, val state: RowState, val detail: String = "", val id: String? = null)
data class TrackRef(val id: String, val path: String, val title: String)
data class PlaylistRef(val id: String, val title: String) {
    override fun toString() = "$title  [${id.take(8)}]"
}
data class CommitResult(val playlist: PlaylistRef, val addedIds: Set<String>, val existingIds: Set<String>)
data class ImportReport(val rows: List<ImportRow>, val playlist: PlaylistRef?, val newlyIndexed: Int, val failure: String? = null) {
    val added get() = rows.count { it.state == RowState.ADDED }
    val failures get() = rows.filter { it.state in setOf(RowState.MISSING, RowState.INVALID, RowState.FAILED) }
    fun summary(): String = "加入 $added 首，跳过 ${rows.count { it.state in setOf(RowState.DUPLICATE, RowState.ALREADY_PRESENT) }} 首，失败 ${failures.size} 首；新收录 $newlyIndexed 首" + (failure?.let { "。$it" } ?: "")
}

interface HostPort {
    suspend fun diagnose(): String
    suspend fun tracks(): List<TrackRef>
    suspend fun playlists(): List<PlaylistRef>
    suspend fun collect(path: String): TrackRef
    suspend fun commit(target: PlaylistRef?, title: String, tracks: List<TrackRef>): CommitResult
    fun checkAccess()
}

object TxtReader {
    /** 只规范化完整路径，不根据歌曲标题或文件名猜测。 */
    fun key(value: String): String {
        // SPW 的 Vri 是未转义的 file:/// 路径，可含空格、中文、# 和字面量 %。
        // 不能套用 java.net.URI 的百分号解码，否则会把真实文件名改成另一条路径。
        val local = if (value.startsWith("file://", ignoreCase = true)) {
            val raw = value.substring(7)
            if (raw.trimStart('/').matches(Regex("^[A-Za-z]:[/\\\\].*"))) raw.trimStart('/')
            else "//" + raw.trimStart('/')
        } else value
        val path = Path.of(local)
        require(path.isAbsolute) { "不是完整本地路径" }
        return path.toAbsolutePath().normalize().toString().replace('/', '\\').lowercase(Locale.ROOT)
    }

    fun index(library: List<TrackRef>): Map<String, List<TrackRef>> = library.mapNotNull { track ->
        runCatching { key(track.path) to track }.getOrNull()
    }.groupBy({ it.first }, { it.second })

    fun read(file: Path, encoding: String = "自动（UTF-8 / BOM）"): List<ImportRow> {
        require(Files.size(file) <= 16 * 1024 * 1024) { "TXT 超过 16 MB，请拆分后导入" }
        val bytes = Files.readAllBytes(file)
        val bom = when {
            bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> "UTF-8" to 3
            bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> "UTF-16LE" to 2
            bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> "UTF-16BE" to 2
            else -> null
        }
        val charset = Charset.forName(bom?.first ?: if (encoding.startsWith("自动")) "UTF-8" else encoding)
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val start = bom?.second ?: 0
        val text = try { decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString() }
        catch (e: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("无法按 $charset 解码，请切换编码后重新预览", e) }
        val seen = HashSet<String>()
        return text.lineSequence().mapIndexedNotNull { index, raw ->
            var value = raw.trim()
            if (value.isBlank()) return@mapIndexedNotNull null
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.lastIndex)
            try {
                val path = Path.of(value)
                when {
                    !path.isAbsolute -> ImportRow(index + 1, value, RowState.INVALID, "请使用完整的本地文件路径")
                    !seen.add(key(value)) -> ImportRow(index + 1, value, RowState.DUPLICATE, "保留第一次出现的位置")
                    !Files.isRegularFile(path) -> ImportRow(index + 1, value, RowState.MISSING, "找不到该音频文件")
                    !Files.isReadable(path) -> ImportRow(index + 1, value, RowState.INVALID, "没有文件读取权限")
                    else -> ImportRow(index + 1, path.normalize().toString(), RowState.NEEDS_SCAN)
                }
            } catch (e: java.nio.file.InvalidPathException) {
                ImportRow(index + 1, value, RowState.INVALID, "路径包含无效字符")
            }
        }.toList()
    }

    fun markLibrary(rows: List<ImportRow>, library: List<TrackRef>): List<ImportRow> {
        val indexed = index(library)
        return rows.map { row ->
            if (row.state != RowState.NEEDS_SCAN) row else {
                val matches = indexed[key(row.path)].orEmpty().distinctBy { it.id }
                when (matches.size) {
                    0 -> row
                    1 -> row.copy(state = RowState.READY, id = matches.single().id, detail = matches.single().title)
                    else -> row.copy(state = RowState.INVALID, detail = "曲库中同一路径对应多个 ID，请先修复曲库")
                }
            }
        }
    }

    fun exportFailures(report: ImportReport): String = report.failures.joinToString("\r\n", postfix = if (report.failures.isEmpty()) "" else "\r\n") { it.path }
}

class ImportEngine(private val host: HostPort) {
    suspend fun run(rows: List<ImportRow>, target: PlaylistRef?, title: String, progress: (Int, Int, String) -> Unit): ImportReport {
        require(target != null || title.isNotBlank()) { "请输入歌单名称" }
        host.checkAccess()
        val library = TxtReader.index(host.tracks())
        val resolved = ArrayList<Pair<Int, TrackRef>>()
        val result = rows.toMutableList()
        val ids = HashSet<String>()
        var collected = 0
        for ((index, row) in rows.withIndex()) {
            host.checkAccess()
            progress(index, rows.size, "检查第 ${row.line} 行")
            if (row.state !in setOf(RowState.READY, RowState.NEEDS_SCAN)) continue
            try {
                check(Files.isRegularFile(Path.of(row.path))) { "文件已移动或删除" }
                val matches = library[TxtReader.key(row.path)].orEmpty()
                check(matches.size <= 1) { "曲库路径不唯一" }
                val track = matches.singleOrNull() ?: host.collect(row.path).also { collected++ }
                check(track.id.isNotEmpty() && TxtReader.key(track.path) == TxtReader.key(row.path)) { "宿主返回的歌曲与文件路径不一致" }
                if (ids.add(track.id)) resolved += index to track
                else result[index] = row.copy(state = RowState.DUPLICATE, detail = "与前面的歌曲 ID 相同")
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { throw e }
            catch (e: Exception) { result[index] = row.copy(state = RowState.FAILED, detail = errorText(e)) }
        }
        if (resolved.isEmpty()) return ImportReport(result, null, collected, "没有可加入的歌曲，未修改歌单")
        host.checkAccess()
        progress(rows.size, rows.size, "正在提交歌单事务，请稍候")
        return try {
            val committed = host.commit(target, title.trim(), resolved.map { it.second })
            for ((index, track) in resolved) result[index] = result[index].copy(
                state = if (track.id in committed.addedIds) RowState.ADDED else RowState.ALREADY_PRESENT,
                id = track.id, detail = if (track.id in committed.addedIds) "按 TXT 顺序加入" else "保留原有位置"
            )
            ImportReport(result, committed.playlist, collected)
        } catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { throw e }
        catch (e: Exception) {
            resolved.forEach { (i, _) -> result[i] = result[i].copy(state = RowState.FAILED, detail = "歌单事务未提交：${errorText(e)}") }
            ImportReport(result, null, collected, "歌单事务失败并回滚；已收录到曲库的歌曲保留")
        }
    }
}

fun errorText(error: Throwable): String {
    val cause = when (error) {
        is java.lang.reflect.InvocationTargetException, is java.util.concurrent.CompletionException, is ExceptionInInitializerError -> error.cause ?: error
        else -> error
    }
    return if (cause !== error) errorText(cause) else cause.message ?: cause.javaClass.simpleName
}
