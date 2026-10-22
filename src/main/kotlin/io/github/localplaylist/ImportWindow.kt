package io.github.localplaylist

import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/** 窗口只负责收集选择与显示状态，所有 IO 都在后台协程中执行。 */
class ImportWindow(private val host: HostPort) : JFrame("按文件导入歌单 · SPW") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var selected: Path? = null
    private var rows = emptyList<ImportRow>()
    private var report: ImportReport? = null
    private var healthy = false
    private var busy = false
    private var target: PlaylistRef? = null
    private val fileLabel = JLabel("尚未选择文件")
    private val encoding = JComboBox(arrayOf("自动（UTF-8 / BOM）", "GB18030", "UTF-16LE", "UTF-16BE"))
    private val choose = JButton("选择 TXT")
    private val preview = JButton("重新预览")
    private val refresh = JButton("重新检查")
    private val mode = JComboBox(arrayOf("新建歌单", "追加到已有歌单"))
    private val name = JTextField(18)
    private val playlists = JComboBox<PlaylistRef>()
    private val importButton = JButton("导入歌单")
    private val export = JButton("导出失败路径")
    private val diagnostic = JLabel("正在检查 SPW 兼容性…")
    private val status = JTextArea("选择 TXT 后预览，只有点击“导入歌单”才会写入。")
    private val progress = JProgressBar()
    private val model = RowModel()
    private val table = JTable(model)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(840, 580)
        size = Dimension(1080, 720)
        setLocationRelativeTo(null)
        font = Font("Microsoft YaHei UI", Font.PLAIN, 13)
        val root = JPanel(BorderLayout(0, 14)).apply { border = EmptyBorder(22, 24, 20, 24); background = Color(248, 250, 252) }
        contentPane = root
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        top.add(JLabel("按文件导入歌单").apply { font = Font("Microsoft YaHei UI", Font.BOLD, 25) })
        top.add(Box.createVerticalStrut(8))
        top.add(JLabel("保留 TXT 行顺序 · 本地歌曲 · 新建或追加"))
        top.add(Box.createVerticalStrut(14))
        top.add(line(choose, encoding, preview, refresh))
        top.add(line(fileLabel))
        top.add(line(JLabel("导入方式"), mode, JLabel("歌单名称"), name, playlists))
        diagnostic.foreground = Color(79, 94, 111)
        top.add(line(diagnostic))
        root.add(top, BorderLayout.NORTH)
        table.rowHeight = 28
        table.autoCreateRowSorter = false
        table.tableHeader.reorderingAllowed = false
        table.fillsViewportHeight = true
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.columnModel.getColumn(0).preferredWidth = 55
        table.columnModel.getColumn(1).preferredWidth = 115
        table.columnModel.getColumn(2).preferredWidth = 560
        table.columnModel.getColumn(3).preferredWidth = 220
        root.add(JScrollPane(table), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout(0, 9)).apply { isOpaque = false }
        status.isEditable = false
        status.isOpaque = false
        status.lineWrap = true
        status.wrapStyleWord = true
        status.rows = 3
        bottom.add(status, BorderLayout.NORTH)
        progress.isStringPainted = true
        progress.string = "等待预览"
        bottom.add(progress, BorderLayout.CENTER)
        bottom.add(line(export, importButton), BorderLayout.SOUTH)
        root.add(bottom, BorderLayout.SOUTH)
        applyFont(root, Font("Microsoft YaHei UI", Font.PLAIN, 13))
        top.getComponent(0).font = Font("Microsoft YaHei UI", Font.BOLD, 25)
        choose.addActionListener { selectFile() }
        preview.addActionListener { loadPreview() }
        refresh.addActionListener { checkHost() }
        encoding.addActionListener { clearPreview("编码已改变，请重新预览") }
        mode.addActionListener { updateControls() }
        playlists.addActionListener { target = playlists.selectedItem as? PlaylistRef; updateControls() }
        importButton.addActionListener { startImport() }
        export.addActionListener { exportFailures() }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                if (busy) {
                    val answer = JOptionPane.showConfirmDialog(this@ImportWindow, "关闭将取消尚未提交的导入。已经收录到曲库的歌曲会保留。", "关闭导入窗口", JOptionPane.OK_CANCEL_OPTION)
                    if (answer != JOptionPane.OK_OPTION) return
                }
                shutdown()
            }
        })
        updateControls()
        checkHost()
    }

    private fun line(vararg components: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 5)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        components.forEach { add(it) }
    }
    private fun applyFont(component: Component, font: Font) {
        component.font = font
        if (component is Container) component.components.forEach { applyFont(it, font) }
    }
    private fun ui(block: () -> Unit) { SwingUtilities.invokeLater { if (isDisplayable) block() } }
    private fun updateControls() {
        choose.isEnabled = !busy
        encoding.isEnabled = !busy
        mode.isEnabled = !busy
        name.isEnabled = !busy && mode.selectedIndex == 0
        playlists.isVisible = mode.selectedIndex == 1
        playlists.isEnabled = !busy && healthy
        preview.isEnabled = !busy && selected != null && healthy
        refresh.isEnabled = !busy
        importButton.isEnabled = !busy && healthy && rows.any { it.state in setOf(RowState.READY, RowState.NEEDS_SCAN) } && (mode.selectedIndex == 0 || target != null)
        export.isEnabled = !busy && report?.failures?.isNotEmpty() == true
    }
    private fun clearPreview(message: String) { rows = emptyList(); report = null; model.setRows(rows); status.text = message; updateControls() }

    private fun checkHost() {
        if (busy) return
        busy = true; healthy = false; updateControls()
        job = scope.launch {
            try {
                val diagnosis = host.diagnose()
                val available = host.playlists()
                ui {
                    diagnostic.text = diagnosis; healthy = true
                    val old = target?.id
                    playlists.model = DefaultComboBoxModel(available.toTypedArray())
                    available.firstOrNull { it.id == old }?.let { playlists.selectedItem = it }
                    target = playlists.selectedItem as? PlaylistRef
                    busy = false; updateControls()
                }
            } catch (e: Throwable) { showFailure(e, "兼容检查失败", true) }
        }
    }
    private fun selectFile() {
        val picker = JFileChooser().apply { fileFilter = FileNameExtensionFilter("歌曲路径清单 (*.txt)", "txt") }
        selected?.parent?.toFile()?.let { picker.currentDirectory = it }
        if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        selected = picker.selectedFile.toPath()
        fileLabel.text = selected.toString()
        fileLabel.toolTipText = selected.toString()
        name.text = picker.selectedFile.nameWithoutExtension
        clearPreview("正在读取清单…")
        if (healthy) loadPreview()
    }
    private fun loadPreview() {
        if (busy) return
        val file = selected ?: return
        val charset = encoding.selectedItem.toString()
        busy = true; report = null; rows = emptyList(); model.setRows(rows); updateControls()
        job = scope.launch {
            try {
                host.checkAccess()
                val loaded = TxtReader.markLibrary(TxtReader.read(file, charset), host.tracks())
                ui {
                    rows = loaded; model.setRows(rows)
                    status.text = "${rows.size} 条记录：" + rows.groupingBy { it.state }.eachCount().entries.joinToString("，") { "${it.key.label} ${it.value}" } + "。缺失项将跳过；其余按原始行顺序导入。"
                    progress.value = 0; progress.string = "预览完成，等待导入"
                    busy = false; updateControls()
                }
            } catch (e: Throwable) { showFailure(e, "预览失败") }
        }
    }
    private fun startImport() {
        val destination = if (mode.selectedIndex == 0) null else target ?: return
        val title = name.text.trim()
        if (destination == null && title.isBlank()) { status.text = "请输入新歌单的名称"; return }
        val snapshot = rows.toList()
        busy = true; updateControls()
        job = scope.launch {
            try {
                val result = ImportEngine(host).run(snapshot, destination, title) { done, total, text ->
                    ui { progress.maximum = total.coerceAtLeast(1); progress.value = done; progress.string = text }
                }
                val available = runCatching { host.playlists() }.getOrNull()
                ui {
                    report = result; rows = result.rows; model.setRows(rows)
                    status.text = result.summary() + (result.playlist?.let { "。目标歌单：${it.title}" } ?: "")
                    progress.string = "处理完成"; busy = false
                    if (available != null) {
                        playlists.model = DefaultComboBoxModel(available.toTypedArray())
                        result.playlist?.let { p -> playlists.selectedItem = available.firstOrNull { it.id == p.id } }
                    }
                    target = playlists.selectedItem as? PlaylistRef
                    updateControls()
                }
            } catch (e: CancellationException) { /* 关闭窗口或停用时不再回写 UI。 */ }
            catch (e: Throwable) { showFailure(e, "操作停止，已收录歌曲保留；未完成的歌单事务回滚") }
        }
    }
    private fun exportFailures() {
        val text = report?.let(TxtReader::exportFailures) ?: return
        val picker = JFileChooser().apply { selectedFile = java.io.File("导入失败路径.txt") }
        if (picker.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = picker.selectedFile.toPath()
        if (Files.exists(file) && JOptionPane.showConfirmDialog(this, "文件已存在，是否覆盖？", "导出失败路径", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        busy = true; updateControls()
        job = scope.launch {
            try { Files.writeString(file, text, StandardCharsets.UTF_8); ui { busy = false; status.text = "失败路径已导出到 $file"; updateControls() } }
            catch (e: Exception) { ui { busy = false; status.text = "导出失败：${errorText(e)}"; updateControls() } }
        }
    }
    fun shutdown() { job?.cancel(); scope.cancel(); dispose() }

    private fun showFailure(error: Throwable, prefix: String, isDiagnosis: Boolean = false) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        val message = "$prefix：${errorText(error)}"
        ui {
            status.text = message
            if (isDiagnosis) diagnostic.text = message
            busy = false; healthy = false; updateControls()
        }
    }

    private class RowModel : AbstractTableModel() {
        private var rows = emptyList<ImportRow>()
        private val columns = arrayOf("原始行号", "状态", "歌曲文件路径", "说明")
        fun setRows(value: List<ImportRow>) { rows = value; fireTableDataChanged() }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) { 0 -> it.line; 1 -> it.state.label; 2 -> it.path; else -> it.detail }
        }
    }
}
