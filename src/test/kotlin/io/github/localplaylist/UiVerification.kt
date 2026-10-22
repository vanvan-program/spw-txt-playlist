package io.github.localplaylist

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*
import kotlinx.coroutines.delay

/** 直接验证自己的 Swing 窗口，无需操作用户桌面或正式 SPW 界面。 */
object UiVerification {
    @JvmStatic fun main(args: Array<String>) {
        val output = Path.of(args.single()).toAbsolutePath()
        Files.createDirectories(output)
        val source = output.resolve("界面测试.txt")
        val songs = listOf("第三首 & 中文.mp3", "やさしい雨.flac", "第一首.wav").map { Files.write(output.resolve(it), byteArrayOf(1)).toString() }
        Files.writeString(source, (songs + songs[0] + output.resolve("缺失文件.mp3").toString()).joinToString("\r\n"))
        val host = PreviewHost(songs)
        var window: ImportWindow? = null
        fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        fun field(key: String): Any? = ImportWindow::class.java.getDeclaredField(key).apply { isAccessible = true }.get(window)
        fun waitIdle() {
            repeat(200) {
                var idle = false
                edt { idle = !(field("busy") as Boolean) }
                if (idle) return
                Thread.sleep(50)
            }
            error("窗口任务超过 10 秒")
        }
        try {
            edt { window = ImportWindow(host).also { it.addNotify(); it.validate() } }
            waitIdle()
            edt {
                check(field("healthy") == true)
                ImportWindow::class.java.getDeclaredField("selected").apply { isAccessible = true }.set(window, source)
                (field("fileLabel") as JLabel).text = source.toString()
                (field("name") as JTextField).text = "Loved"
                ImportWindow::class.java.getDeclaredMethod("loadPreview").apply { isAccessible = true }.invoke(window)
            }
            waitIdle()
            edt {
                val rows = field("rows") as List<*>
                check(rows.size == 5)
                check((field("importButton") as JButton).isEnabled)
                capture(window!!, output.resolve("导入窗口预览.png"))
                (field("mode") as JComboBox<*>).selectedIndex = 1
                (field("importButton") as JButton).doClick()
            }
            waitIdle()
            edt {
                val report = field("report") as ImportReport
                check(report.added == 3 && report.failures.size == 1)
                check((field("export") as JButton).isEnabled)
                capture(window!!, output.resolve("导入结果预览.png"))
                window!!.shutdown()
                check(!window!!.isDisplayable)
            }
            println("PASS: preview, append controls, import results, export availability and closing window")

            val slowHost = PreviewHost(songs).apply { slow = true }
            edt { window = ImportWindow(slowHost).also { it.addNotify(); it.validate() } }
            waitIdle()
            edt {
                ImportWindow::class.java.getDeclaredField("selected").apply { isAccessible = true }.set(window, source)
                (field("name") as JTextField).text = "cancel"
                ImportWindow::class.java.getDeclaredMethod("loadPreview").apply { isAccessible = true }.invoke(window)
            }
            waitIdle()
            edt { (field("importButton") as JButton).doClick() }
            Thread.sleep(150)
            edt { window!!.shutdown() }
            Thread.sleep(300)
            check(slowHost.commits == 0)
            println("PASS: stopping window cancels pending import before commit")
        } finally { edt { window?.shutdown() } }
    }
    private fun capture(window: ImportWindow, path: Path) {
        val image = BufferedImage(window.contentPane.width, window.contentPane.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { window.contentPane.printAll(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", path.toFile())
    }
    private class PreviewHost(private val paths: List<String>) : HostPort {
        @Volatile var commits = 0
        var slow = false
        override fun checkAccess() = Unit
        override suspend fun diagnose() = "SPW 1.19.0 兼容检查通过（界面验证使用模拟数据）"
        override suspend fun playlists() = listOf(PlaylistRef("existing", "我的歌单"))
        override suspend fun tracks() = listOf(TrackRef("known", paths[0], "第三首"))
        override suspend fun collect(path: String): TrackRef {
            if (slow) delay(5000)
            return TrackRef(path, path, Path.of(path).fileName.toString())
        }
        override suspend fun commit(target: PlaylistRef?, title: String, tracks: List<TrackRef>): CommitResult {
            commits++
            return CommitResult(target ?: PlaylistRef("new", title), tracks.map { it.id }.toSet(), emptySet())
        }
    }
}
