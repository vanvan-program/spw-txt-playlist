package io.github.localplaylist

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import javax.swing.SwingUtilities

class TxtPlaylistPlugin(context: PluginContext) : SpwPlugin(context) {
    @Volatile private var enabled = false
    private var window: ImportWindow? = null

    override fun start() { enabled = true; current = this }

    private fun open() {
        SwingUtilities.invokeLater {
            if (!enabled) return@invokeLater
            if (window == null || !window!!.isDisplayable) window = ImportWindow(Spw119Bridge({ enabled }))
            window!!.isVisible = true
            window!!.toFront()
        }
    }

    override fun stop() {
        enabled = false
        if (current === this) current = null
        SwingUtilities.invokeLater { window?.shutdown(); window = null }
    }

    companion object {
        @Volatile private var current: TxtPlaylistPlugin? = null
        @JvmStatic fun openImporter() {
            val plugin = current
            if (plugin != null) plugin.open()
            else WorkshopApi.ui.toast("请先启用按文件导入歌单插件", WorkshopApi.Ui.ToastType.Warning)
        }
    }
}
