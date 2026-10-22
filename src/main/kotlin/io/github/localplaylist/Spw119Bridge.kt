package io.github.localplaylist

import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 所有内部名称均限制在经指纹校验的 1.19.0 Windows 构建中。 */
class Spw119Bridge(private val active: () -> Boolean) : HostPort {
    private val loader = WorkshopApi::class.java.classLoader
    private val provider by lazy { type("com.xuncorp.voxzen.data.\u0560") }
    private val daoType by lazy { type("androidx.compose.ui.\u0aa4") }
    private val playlistRepo by lazy { type("androidx.compose.ui.\u10e9") }
    private val native by lazy {
        val db = invoke(provider.getMethod("\u037f"), null)!!
        NativeRoom(db, loader)
    }
    private var compatible = false

    override fun checkAccess() {
        check(active()) { "插件已停用" }
        for (permission in listOf(PluginPermission.LIBRARY_READ, PluginPermission.CLASS_TRANSFORM)) {
            if (!WorkshopApi.manager.isPermissionGranted(permission)) throw SecurityException("请在启用插件时授予曲库读取和宿主修改权限")
        }
    }

    private fun type(name: String) = Class.forName(name, false, loader)

    override suspend fun diagnose(): String {
        compatible = false
        checkAccess()
        check(System.getProperty("os.name").startsWith("Windows")) { "第一版仅支持 Windows" }
        check(System.getProperty("jpackage.app-version") == "1.19.0") { "仅适配 SPW 1.19.0，请勿在其他版本强行导入" }
        val hashes = Properties().apply {
            Spw119Bridge::class.java.getResourceAsStream("/host-119.properties").use { stream ->
                checkNotNull(stream) { "插件缺少兼容性指纹" }; load(stream)
            }
        }
        for ((rawPath, expected) in hashes) {
            val path = rawPath.toString()
            val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() } ?: error("宿主缺少 $path")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "宿主内部实现已变化：$path；已禁用歌单写入" }
        }
        val databaseClass = type("com.xuncorp.voxzen.data.AppDatabase")
        check(provider.getMethod("\u037f").returnType == databaseClass)
        check(databaseClass.getMethod("\u052f").returnType == daoType)
        val sorts = type("androidx.compose.ui.\u0d12").enumConstants.map { it as Enum<*> }
        check(sorts.single { it.name == "Custom" }.ordinal == 0) { "自定义排序值不匹配" }
        // 先验证所有依赖的入口，不以“找到了相近方法”作为写入依据。
        playlistRepo.getMethod("\u0528", String::class.java)
        type("com.xuncorp.voxzen.service.RepoService").getMethod("addOrUpdateTracks-gIAlu-s", List::class.java, Continuation::class.java)
        type("com.xuncorp.spc.v.Vri$\u037f").getMethod("\u0528", String::class.java)
        native.access(true, false) { sql ->
            sql.checkSchema()
            check(sql.rows("PRAGMA foreign_keys").single().single() == "1") { "宿主未启用外键约束" }
        }
        compatible = true
        return "SPW 1.19.0 兼容检查通过：接口指纹、Custom 排序、Room 事务和歌单结构正常"
    }

    private fun ready() { checkAccess(); check(compatible) { "请先通过兼容性检查" } }

    override suspend fun tracks(): List<TrackRef> {
        checkAccess()
        val media = suspendCancellableCoroutine { cont ->
            WorkshopApi.library.getAllTracks().whenComplete { tracks, failure ->
                if (cont.isActive) {
                    if (failure != null) cont.resumeWithException(failure) else cont.resume(tracks)
                }
            }
        }
        checkAccess()
        return media.map { TrackRef(it.id, it.path, it.title) }
    }

    override suspend fun playlists(): List<PlaylistRef> {
        ready()
        return native.access(true, false) { sql ->
            sql.rows("SELECT id,title FROM Playlist ORDER BY `order`,id").map { PlaylistRef(it[0]!!, it[1]!!) }
        }
    }

    override suspend fun collect(path: String): TrackRef {
        ready()
        coroutineContext.ensureActive()
        val vri = invoke(type("com.xuncorp.spc.v.Vri$\u037f").getMethod("\u0528", String::class.java), null, path)
        val serviceClass = type("com.xuncorp.voxzen.service.RepoService")
        val service = serviceClass.getField("INSTANCE").get(null)
        val method = serviceClass.getMethod("addOrUpdateTracks-gIAlu-s", List::class.java, Continuation::class.java)
        var response = callSuspend(method, service, listOf(vri))
        // Kotlin suspend Result 的同步返回可为未装箱值，异步返回也可能是 Result 包装。
        if (response?.javaClass?.name == "kotlin.Result") response = invoke(response.javaClass.getMethod("unbox-impl"), response)
        if (response?.javaClass?.name == "kotlin.Result\$Failure") {
            throw (response.javaClass.getField("exception").get(response) as Throwable)
        }
        val items = response as? List<*> ?: error("宿主没有返回已收录歌曲")
        checkAccess()
        val matches = items.filterNotNull().map { item ->
            fun value(getter: String) = invoke(item.javaClass.getMethod(getter), item).toString()
            TrackRef(value("getId"), value("getPath"), value("getTitle"))
        }.filter { TxtReader.key(it.path) == TxtReader.key(path) }
        return matches.singleOrNull() ?: error("宿主未能收录此文件，或返回路径不唯一")
    }

    override suspend fun commit(target: PlaylistRef?, title: String, tracks: List<TrackRef>): CommitResult {
        ready()
        val context = coroutineContext
        // 用宿主排序流取得当前显示顺序，避免自行复制其自然排序、语言排序规则。
        val ordered = if (target == null) null else {
            @Suppress("UNCHECKED_CAST")
            val flow = invoke(playlistRepo.getMethod("\u0528", String::class.java), null, target.id) as Flow<Any?>
            val snapshot = kotlinx.coroutines.withTimeout(15_000) { flow.first() } ?: error("目标歌单已被删除")
            @Suppress("UNCHECKED_CAST")
            val pairs = invoke(snapshot.javaClass.getMethod("getTracks"), snapshot) as List<Any>
            val playlist = invoke(snapshot.javaClass.getMethod("getPlaylist"), snapshot)!!
            val sort = invoke(playlist.javaClass.getMethod("getTrackSort"), playlist).toString()
            val descending = invoke(playlist.javaClass.getMethod("getTrackSortDescending"), playlist) as Boolean
            val records = pairs.map { pair ->
                val pt = invoke(pair.javaClass.getMethod("getPlaylistTrack"), pair)!!
                listOf("getTrackId", "getAddedTime", "getOrder").map { invoke(pt.javaClass.getMethod(it), pt).toString() }
            }
            PlaylistSnapshot(sort, descending, records)
        }
        return native.access(false, true) { sql ->
            fun guard() { context.ensureActive(); ready() }
            guard()
            val unique = tracks.distinctBy { it.id }
            // 防止预览后曲库删除重建，不能把旧 ID 对应到另一首歌。
            unique.forEach { track ->
                guard()
                val current = sql.rows("SELECT path FROM Track WHERE id=?", track.id).singleOrNull()?.single()
                check(current != null && TxtReader.key(current) == TxtReader.key(track.path)) { "曲库在导入期间发生变化，请重新预览" }
            }
            val now = System.currentTimeMillis()
            val playlist = target ?: PlaylistRef(UUID.randomUUID().toString(), title)
            val existing = if (target == null) emptySet() else {
                val settings = sql.rows("SELECT trackSort,trackSortDescending FROM Playlist WHERE id=?", target.id).singleOrNull()
                    ?: error("目标歌单已删除")
                val current = sql.rows("SELECT trackId,addedTime,`order` FROM PlaylistTrack WHERE playlistId=?", target.id)
                check(settings[0] == ordered!!.sort && settings[1] == if (ordered.descending) "1" else "0") { "目标歌单排序设置已变化，请重试" }
                check(current.sortedBy { it[0] } == ordered.records.sortedBy { it[0] }) { "目标歌单内容已变化，请重试" }
                current.map { it[0]!! }.toSet()
            }
            val adding = unique.filter { it.id !in existing }
            // 无新增歌曲时不改排序设置和已有顺序。
            if (adding.isEmpty()) return@access CommitResult(playlist, emptySet(), existing)
            if (target == null) {
                val max = sql.rows("SELECT COALESCE(MAX(`order`),-1) FROM Playlist").single().single()!!.toLong()
                check(max < Int.MAX_VALUE) { "歌单排序已达到上限" }
                sql.exec("INSERT INTO Playlist(id,title,coverModifiedTime,isUserEditedCover,description,createdTime,`order`,trackSort,trackSortDescending) VALUES(?,?,0,0,'',?,?,0,0)", playlist.id, title, now, max + 1)
            } else {
                ordered!!.records.forEachIndexed { index, record ->
                    guard(); sql.exec("UPDATE PlaylistTrack SET `order`=? WHERE playlistId=? AND trackId=?", index, playlist.id, record[0])
                }
                sql.exec("UPDATE Playlist SET trackSort=0,trackSortDescending=0 WHERE id=?", playlist.id)
            }
            adding.forEachIndexed { index, track ->
                guard()
                sql.exec("INSERT INTO PlaylistTrack(playlistId,trackId,addedTime,`order`) VALUES(?,?,?,?)", playlist.id, track.id, now, existing.size + index)
            }
            val actual = sql.rows("SELECT trackId FROM PlaylistTrack WHERE playlistId=? ORDER BY `order`", playlist.id).map { it[0]!! }
            val expected = ordered?.records.orEmpty().map { it[0] } + adding.map { it.id }
            check(actual == expected) { "写入后的歌单顺序校验失败" }
            guard()
            CommitResult(playlist, adding.map { it.id }.toSet(), existing)
        }
    }

    private data class PlaylistSnapshot(val sort: String, val descending: Boolean, val records: List<List<String>>)
}

/** 借用宿主持有的数据库连接和事务，不打开、关闭或直接写入用户的数据库文件。 */
class NativeRoom(private val database: Any, private val loader: ClassLoader) {
    private val operation = Class.forName("androidx.room.util.DBUtil", false, loader).methods.single {
        it.name == "performSuspending" && Modifier.isStatic(it.modifiers) && it.parameterCount == 5
    }
    suspend fun <T> access(readOnly: Boolean, transaction: Boolean, block: (NativeSql) -> T): T {
        val callback: (Any) -> T = { connection -> block(NativeSql(connection, loader)) }
        @Suppress("UNCHECKED_CAST")
        return callSuspend(operation, null, database, readOnly, transaction, callback) as T
    }
}

class NativeSql(private val connection: Any, loader: ClassLoader) {
    private val connectionType = Class.forName("androidx.sqlite.SQLiteConnection", false, loader)
    private val statementType = Class.forName("androidx.sqlite.SQLiteStatement", false, loader)
    fun rows(sql: String, vararg args: Any?): List<List<String?>> = statement(sql, args) { stmt ->
        val rows = ArrayList<List<String?>>()
        val columns = invoke(statementType.getMethod("getColumnCount"), stmt) as Int
        while (invoke(statementType.getMethod("step"), stmt) as Boolean) {
            rows += (0 until columns).map { index ->
                if (invoke(statementType.getMethod("isNull", Int::class.javaPrimitiveType), stmt, index) as Boolean) null
                else when (invoke(statementType.getMethod("getColumnType", Int::class.javaPrimitiveType), stmt, index) as Int) {
                    1 -> invoke(statementType.getMethod("getLong", Int::class.javaPrimitiveType), stmt, index).toString()
                    2 -> invoke(statementType.getMethod("getDouble", Int::class.javaPrimitiveType), stmt, index).toString()
                    else -> invoke(statementType.getMethod("getText", Int::class.javaPrimitiveType), stmt, index).toString()
                }
            }
        }
        rows
    }
    fun exec(sql: String, vararg args: Any?) = statement(sql, args) { stmt ->
        check(!(invoke(statementType.getMethod("step"), stmt) as Boolean)) { "写入语句意外返回数据" }
    }
    private fun <T> statement(sql: String, args: Array<out Any?>, block: (Any) -> T): T {
        val stmt = invoke(connectionType.getMethod("prepare", String::class.java), connection, sql)!!
        try {
            args.forEachIndexed { index, value ->
                when (value) {
                    null -> invoke(statementType.getMethod("bindNull", Int::class.javaPrimitiveType), stmt, index + 1)
                    is Number -> invoke(statementType.getMethod("bindLong", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType), stmt, index + 1, value.toLong())
                    else -> invoke(statementType.getMethod("bindText", Int::class.javaPrimitiveType, String::class.java), stmt, index + 1, value.toString())
                }
            }
            return block(stmt)
        } finally { invoke(statementType.getMethod("close"), stmt) }
    }
    fun checkSchema() {
        val expected = mapOf(
            "Playlist" to setOf("id", "title", "coverModifiedTime", "isUserEditedCover", "description", "createdTime", "order", "trackSort", "trackSortDescending"),
            "PlaylistTrack" to setOf("playlistId", "trackId", "addedTime", "order")
        )
        expected.forEach { (table, columns) ->
            check(rows("PRAGMA table_info($table)").map { it[1] }.toSet() == columns) { "$table 结构不兼容" }
        }
    }
}

internal fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try { method.invoke(receiver, *args) }
catch (e: InvocationTargetException) { throw e.targetException }

internal suspend fun callSuspend(method: Method, receiver: Any?, vararg args: Any?): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation -> invoke(method, receiver, *args, continuation) }
