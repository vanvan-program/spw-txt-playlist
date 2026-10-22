package io.github.localplaylist

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/** 使用真实 SPW 类、Room 和 SQLite；全部数据位于 build/host-test 下。 */
fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(System.err, true, Charsets.UTF_8))
    val dir = Path.of(args.single()).toAbsolutePath()
    Files.createDirectories(dir)
    val loader = Spw119Bridge::class.java.classLoader
    fun type(name: String) = Class.forName(name, true, loader)
    val dbType = type("com.xuncorp.voxzen.data.AppDatabase")
    val builderType = type("androidx.room.RoomDatabase\$Builder")
    val factory: () -> Any = { type("com.xuncorp.voxzen.data.AppDatabase_Impl").getConstructor().newInstance() }
    val builder = builderType.getConstructor(KClass::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java)
        .newInstance(dbType.kotlin, dir.resolve("isolated.db").toString(), factory)
    val driver = type("androidx.sqlite.driver.bundled.\u0528").getConstructor().newInstance()
    invoke(builderType.getMethod("setDriver", type("androidx.sqlite.SQLiteDriver")), builder, driver)
    invoke(builderType.getMethod("setQueryCoroutineContext", kotlin.coroutines.CoroutineContext::class.java), builder, Dispatchers.IO)
    val database = invoke(builderType.getMethod("build"), builder)!!
    // 替换前不读取原单例的值，避免创建或连接真实用户数据库。
    val provider = type("com.xuncorp.voxzen.data.\u0560")
    val lazyField = provider.declaredFields.single { it.type == Lazy::class.java }
    TestStatics.replace(lazyField, lazy { database })
    val native = NativeRoom(database, loader)
    var enabled = true
    var allowed = true
    var failAt = 0
    val calls = AtomicInteger()
    // 仅独立测试 JVM 注入 API 的权限提供者，生产代码没有跳过授权的开关。
    val apiType = type("com.xuncorp.spw.workshop.api.WorkshopApi")
    val managerType = type("com.xuncorp.spw.workshop.api.WorkshopApi\$Manager")
    val manager = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(managerType)) { _, method, values ->
        check(method.name == "isPermissionGranted") { "Unexpected manager call: ${method.name}" }
        val permission = values[0] as Enum<*>
        if (permission.name == "LIBRARY_READ" && failAt != 0 && calls.incrementAndGet() == failAt) allowed = false
        allowed
    }
    val api = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(apiType)) { _, method, _ ->
        check(method.name == "getManager") { "Unexpected API call: ${method.name}" }
        manager
    }
    val companion = apiType.getField("Companion").get(null)
    invoke(companion.javaClass.getMethod("setInstance", apiType), companion, api)
    val bridge = Spw119Bridge { enabled }
    var persistedId = ""
    var persistedSequence = emptyList<String>()
    try {
        println(bridge.diagnose())
        val tracks = (1..4).map { number ->
            val path = dir.resolve("song$number.wav")
            Files.write(path, waveBytes())
            TrackRef("native-test-$number", path.toString(), listOf("Zulu", "Alpha", "Mike", "Beta")[number - 1])
        }
        native.access(false, true) { sql ->
            val columns = sql.rows("PRAGMA table_info(Track)")
            for (track in tracks) {
                val values: List<Any> = columns.map { col ->
                    when (col[1]) {
                        "id" -> track.id
                        "title" -> track.title
                        "path" -> "file:///" + track.path.replace('\\', '/')
                        "readable" -> 1
                        else -> if (col[2]!!.contains("TEXT")) "" else 0
                    }
                }
                sql.exec("INSERT INTO Track(${columns.joinToString(",") { "`${it[1]}`" }}) VALUES(${columns.joinToString(",") { "?" }})", *values.toTypedArray())
            }
        }
        val first = bridge.commit(null, "顺序测试", listOf(tracks[2], tracks[0], tracks[1]))
        check(first.addedIds.size == 3)
        suspend fun sequence(id: String) = native.access(true, false) { sql ->
            sql.rows("SELECT trackId FROM PlaylistTrack WHERE playlistId=? ORDER BY `order`", id).map { it[0]!! }
        }
        check(sequence(first.playlist.id) == listOf(tracks[2].id, tracks[0].id, tracks[1].id))
        println("PASS: new playlist retains supplied sequence")
        val appended = bridge.commit(first.playlist, "", listOf(tracks[1], tracks[3], tracks[0]))
        check(appended.addedIds == setOf(tracks[3].id))
        check(sequence(first.playlist.id) == listOf(tracks[2].id, tracks[0].id, tracks[1].id, tracks[3].id))
        println("PASS: append skips duplicates and preserves existing sequence")
        check(bridge.commit(first.playlist, "", tracks).addedIds.isEmpty())
        println("PASS: repeated import is idempotent")

        // 模拟用户选择了歌名倒序，再追加一首，验证已有显示顺序不变。
        native.access(false, true) { sql -> sql.exec("UPDATE Playlist SET trackSort=1,trackSortDescending=1 WHERE id=?", first.playlist.id) }
        val repo = type("androidx.compose.ui.\u10e9")
        @Suppress("UNCHECKED_CAST")
        suspend fun displayed(): List<String> {
            val flow = invoke(repo.getMethod("\u0528", String::class.java), null, first.playlist.id) as Flow<Any?>
            val snapshot = withTimeout(10_000) { flow.first() }!!
            val pairs = invoke(snapshot.javaClass.getMethod("getTracks"), snapshot) as List<Any>
            return pairs.map { pair ->
                val t = invoke(pair.javaClass.getMethod("getTrack"), pair)!!
                invoke(t.javaClass.getMethod("getId"), t).toString()
            }
        }
        val displayed = displayed()
        val extra = tracks[0].copy(id = "native-extra", title = "New")
        native.access(false, true) { sql ->
            val columns = sql.rows("PRAGMA table_info(Track)")
            val selection = columns.joinToString(",") { if (it[1] == "id") "?" else "`${it[1]}`" }
            sql.exec("INSERT INTO Track SELECT $selection FROM Track WHERE id=?", extra.id, tracks[0].id)
        }
        bridge.commit(first.playlist, "", listOf(extra))
        check(sequence(first.playlist.id) == displayed + extra.id)
        println("PASS: append preserves host title-descending display order")
        persistedId = first.playlist.id
        persistedSequence = sequence(persistedId)

        val before = bridge.playlists().size
        failAt = 7; calls.set(0)
        check(runCatching { bridge.commit(null, "必须回滚", tracks.take(3)) }.isFailure)
        failAt = 0; allowed = true
        check(bridge.playlists().size == before)
        println("PASS: exception after partial inserts rolls back complete playlist")
        allowed = false
        check(runCatching { bridge.playlists() }.isFailure)
        allowed = true; enabled = false
        check(runCatching { bridge.playlists() }.isFailure)
        enabled = true
        println("PASS: permission denial and disabled plugin block access")

        System.setProperty("jpackage.app-version", "1.20.0")
        check(runCatching { bridge.diagnose() }.isFailure)
        check(runCatching { bridge.commit(null, "不得创建", tracks) }.isFailure)
        System.setProperty("jpackage.app-version", "1.19.0")
        bridge.diagnose()
        println("PASS: unsupported host disables writes")

        // 与现有种子歌曲不同的路径，真实执行宿主按文件收录。
        val unindexed = dir.resolve("新收录 & sample.wav")
        Files.write(unindexed, waveBytes())
        // 正式宿主启动时已加载 BASS；隔离 JVM 需要显式补齐这一步。
        val config = type("com.xuncorp.pisces.\u0783")
        listOf("\u037f" to "bass.dll", "\u0528" to "bass_fx.dll", "\u0529" to "bassmix.dll", "\u052a" to "basswasapi.dll", "\u052b" to "bassasio.dll", "\u052c" to "basscd.dll").forEach { (setter, file) ->
            invoke(config.getMethod(setter, String::class.java), null, Path.of(System.getProperty("test.bass.path")).parent.resolve(file).toString())
        }
        val bass = type("com.moriafly.bass.BASS")
        val loadBass = bass.methods.single { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
        invoke(loadBass, bass.fields.single { it.type == bass }.get(null), System.getProperty("test.bass.path"))
        val bassCompanion = type("com.moriafly.bass.BASSLibrary$\u037f")
        val bassLibrary = invoke(bassCompanion.getMethod("\u037f"), null)!!
        val bassInterface = type("com.moriafly.bass.BASSLibrary")
        val initialize = bassInterface.methods.single { it.name == "BASS_Init" && it.parameterCount == 5 }
        check(invoke(initialize, bassLibrary, 0, 48000, 0, null, null) == true) { "测试用无声 BASS 设备初始化失败" }
        val collected = bridge.collect(unindexed.toString())
        check(TxtReader.key(collected.path) == TxtReader.key(unindexed.toString()))
        check(native.access(true, false) { it.rows("SELECT id FROM Track WHERE id=?", collected.id).isNotEmpty() })
        println("PASS: native RepoService collects an unindexed audio file")
    } finally { invoke(type("androidx.room.RoomDatabase").getMethod("close"), database) }
    val reopenedBuilder = builderType.getConstructor(KClass::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java)
        .newInstance(dbType.kotlin, dir.resolve("isolated.db").toString(), factory)
    invoke(builderType.getMethod("setDriver", type("androidx.sqlite.SQLiteDriver")), reopenedBuilder, driver)
    invoke(builderType.getMethod("setQueryCoroutineContext", kotlin.coroutines.CoroutineContext::class.java), reopenedBuilder, Dispatchers.IO)
    val reopened = invoke(builderType.getMethod("build"), reopenedBuilder)!!
    try {
        val sequence = NativeRoom(reopened, loader).access(true, false) { sql ->
            sql.rows("SELECT trackId FROM PlaylistTrack WHERE playlistId=? ORDER BY `order`", persistedId).map { it[0]!! }
        }
        check(sequence == persistedSequence)
        println("PASS: playlist order persists after closing and reopening database")
    } finally { invoke(type("androidx.room.RoomDatabase").getMethod("close"), reopened) }
    println("HOST INTEGRATION PASS. All writes isolated to $dir")
}

private fun waveBytes(): ByteArray {
    val data = ByteArray(16000)
    val buffer = java.nio.ByteBuffer.allocate(44 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(data.size).put(data)
    return buffer.array()
}
