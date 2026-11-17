package walkure.ui

import walkure.image.AbmpDecoder
import walkure.image.ArgbDecoder
import walkure.pack.QlieVirtualFileSystem
import walkure.script.AssetAliasResolver
import walkure.script.ScenarioParser
import walkure.script.ScenarioVm
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Pure Kotlin / Java2D Cross-Platform Visual Novel Runtime for *Walkure Romanze*.
 * Streams backgrounds, `.argb` composite sprites, `.b` UI layers, and `.s` scenario scripts
 * directly from `GameData/data*.pack` with zero disk extraction required.
 */
class WalkureDesktopApp(
    private val vfs: QlieVirtualFileSystem,
    initialScriptQuery: String = "sue_001_00.s"
) {
    private val aliasResolver = AssetAliasResolver(vfs)
    private val imageCache = object : LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean = size > 24
    }

    private val scenarioEntries = vfs.listEntries(".s")
        .filter { it.path.contains("シナリオ") || it.path.endsWith(".s", ignoreCase = true) }
        .sortedBy { it.path }

    private var vm: ScenarioVm
    private var currentBackgroundImg: BufferedImage? = null
    private var currentSpriteImages: List<BufferedImage> = emptyList()
    private val dialogueFont: Font
    private val speakerFont: Font

    private val frame = JFrame("Walkure Romanze — Kotlin Cross-Platform Engine")
    private val canvas = StageCanvas()
    private val statusLabel = JLabel("Ready")
    private var autoTimer: Timer? = null

    init {
        val customFont = loadCustomFont(vfs.gameDir)
        dialogueFont = customFont?.deriveFont(Font.PLAIN, 22f) ?: Font("SansSerif", Font.PLAIN, 22)
        speakerFont = customFont?.deriveFont(Font.BOLD, 24f) ?: Font("SansSerif", Font.BOLD, 24)

        val startEntry = scenarioEntries.firstOrNull {
            it.path.contains(initialScriptQuery, ignoreCase = true)
        } ?: scenarioEntries.first()

        val initialScript = ScenarioParser.parse(startEntry.path, vfs.readTextShiftJis(startEntry.path))
        vm = ScenarioVm(initialScript, aliasResolver)
        advanceStep()
    }

    fun showWindow() {
        SwingUtilities.invokeLater {
            frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            frame.layout = BorderLayout()

            val topBar = buildControlBar()
            frame.add(topBar, BorderLayout.NORTH)
            frame.add(canvas, BorderLayout.CENTER)
            frame.add(statusLabel, BorderLayout.SOUTH)

            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            canvas.requestFocusInWindow()
        }
    }

    private fun buildControlBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        bar.background = Color(24, 26, 36)

        val scriptSelector = JComboBox(scenarioEntries.map { it.path }.toTypedArray())
        scriptSelector.selectedItem = vm.currentScript.path
        scriptSelector.addActionListener {
            val selectedPath = scriptSelector.selectedItem as? String ?: return@addActionListener
            loadScenario(selectedPath)
            canvas.requestFocusInWindow()
        }

        val nextBtn = JButton("Next [Space/Enter]").apply {
            addActionListener {
                advanceStep()
                canvas.requestFocusInWindow()
            }
        }

        val autoBtn = JButton("Auto: OFF").apply {
            addActionListener {
                toggleAutoMode(this)
                canvas.requestFocusInWindow()
            }
        }

        val backlogBtn = JButton("Backlog [L]").apply {
            addActionListener {
                showBacklogDialog()
                canvas.requestFocusInWindow()
            }
        }

        val qSaveBtn = JButton("Q.Save [F5]").apply {
            addActionListener {
                quickSave()
                canvas.requestFocusInWindow()
            }
        }

        val qLoadBtn = JButton("Q.Load [F9]").apply {
            addActionListener {
                quickLoad()
                canvas.requestFocusInWindow()
            }
        }

        bar.add(JLabel("Scenario:").apply { foreground = Color.WHITE })
        bar.add(scriptSelector)
        bar.add(nextBtn)
        bar.add(autoBtn)
        bar.add(backlogBtn)
        bar.add(qSaveBtn)
        bar.add(qLoadBtn)
        return bar
    }

    private fun loadScenario(vfsPath: String) {
        val text = vfs.readTextShiftJis(vfsPath)
        val parsed = ScenarioParser.parse(vfsPath, text)
        vm = ScenarioVm(parsed, aliasResolver)
        advanceStep()
    }

    private fun advanceStep() {
        val snap = vm.stepToNextStop()
        syncVisualAssets(snap)
        statusLabel.text = " Script: ${snap.scriptPath} | Node: ${snap.instructionPointer}/${vm.currentScript.nodes.size} | BG: ${snap.backgroundSymbol ?: "none"} | BGM: ${snap.currentBgmSymbol ?: "none"}"
        canvas.repaint()
    }

    private fun syncVisualAssets(snap: ScenarioVm.VmSnapshot) {
        currentBackgroundImg = snap.backgroundVfsPath?.let { loadImageFromVfs(it) }
        currentSpriteImages = snap.activeSprites.values.mapNotNull { loadImageFromVfs(it.vfsPath) }
    }

    private fun loadImageFromVfs(pathOrQuery: String): BufferedImage? {
        imageCache[pathOrQuery]?.let { return it }
        val resolved = vfs.resolveEntry(pathOrQuery) ?: return null
        val bytes = runCatching { resolved.first.readEntry(resolved.second) }.getOrNull() ?: return null

        val decoded = runCatching {
            when {
                ArgbDecoder.isArgbData(bytes) -> ArgbDecoder.decode(bytes)
                AbmpDecoder.isAbmpData(bytes) -> AbmpDecoder.parse(bytes).images.firstNotNullOfOrNull { it.decodeBufferedImage() }
                else -> ImageIO.read(ByteArrayInputStream(bytes))
            }
        }.getOrNull()

        if (decoded != null) {
            imageCache[pathOrQuery] = decoded
        }
        return decoded
    }

    private fun toggleAutoMode(btn: JButton) {
        if (autoTimer != null) {
            autoTimer?.stop()
            autoTimer = null
            btn.text = "Auto: OFF"
        } else {
            btn.text = "Auto: ON"
            autoTimer = Timer(2200) {
                if (!vm.isFinished() && vm.pendingChoices().isEmpty()) {
                    advanceStep()
                }
            }.also { it.start() }
        }
    }

    private fun showBacklogDialog() {
        val dialog = JDialog(frame, "Dialogue Backlog", true)
        val area = JTextArea(24, 72).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = dialogueFont.deriveFont(16f)
            background = Color(20, 22, 30)
            foreground = Color(235, 238, 245)
            text = vm.backlog.joinToString("\n\n") { entry ->
                val speakerPrefix = entry.speaker?.let { "【$it】\n" } ?: ""
                val voiceSuffix = entry.voiceId?.let { " ($it)" } ?: ""
                "[Line ${entry.lineNumber}$voiceSuffix]\n$speakerPrefix${entry.text}"
            }
        }
        dialog.add(JScrollPane(area), BorderLayout.CENTER)
        dialog.pack()
        dialog.setLocationRelativeTo(frame)
        dialog.isVisible = true
    }

    private fun quickSave() {
        val snap = vm.snapshot()
        val props = Properties().apply {
            setProperty("scriptPath", snap.scriptPath)
            setProperty("ip", snap.instructionPointer.toString())
            setProperty("bgSymbol", snap.backgroundSymbol.orEmpty())
            setProperty("bgPath", snap.backgroundVfsPath.orEmpty())
            setProperty("bgm", snap.currentBgmSymbol.orEmpty())
            setProperty("speaker", snap.currentSpeaker.orEmpty())
            setProperty("text", snap.currentText)
        }
        val saveFile = File(vfs.gameDir, "SaveData/kotlin_quicksave.properties")
        saveFile.parentFile?.mkdirs()
        saveFile.outputStream().use { props.store(it, "Walkure Romanze Kotlin QuickSave") }
        statusLabel.text = "QuickSaved to ${saveFile.name} (line/node ${snap.instructionPointer})"
    }

    private fun quickLoad() {
        val saveFile = File(vfs.gameDir, "SaveData/kotlin_quicksave.properties")
        if (!saveFile.exists()) {
            JOptionPane.showMessageDialog(frame, "No QuickSave found at ${saveFile.absolutePath}")
            return
        }
        val props = Properties()
        saveFile.inputStream().use { props.load(it) }
        val scriptPath = props.getProperty("scriptPath") ?: return
        val ip = (props.getProperty("ip")?.toIntOrNull() ?: 1) - 1
        val bgSym = props.getProperty("bgSymbol")?.takeIf { it.isNotEmpty() }
        val bgPath = props.getProperty("bgPath")?.takeIf { it.isNotEmpty() }
        val bgm = props.getProperty("bgm")?.takeIf { it.isNotEmpty() }

        val parsed = ScenarioParser.parse(scriptPath, vfs.readTextShiftJis(scriptPath))
        vm.loadSnapshot(parsed, ip.coerceAtLeast(0), bgSym, bgPath, emptyMap(), bgm, null)
        advanceStep()
    }

    private fun loadCustomFont(gameDir: File): Font? {
        val ttf = File(gameDir, "WalkureCustomV4.ttf")
        if (!ttf.exists()) return null
        return runCatching { Font.createFont(Font.TRUETYPE_FONT, ttf) }.getOrNull()
    }

    private inner class StageCanvas : JPanel() {
        init {
            preferredSize = Dimension(1024, 768)
            background = Color(14, 16, 24)
            isFocusable = true

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                    val choices = vm.pendingChoices()
                    if (choices.isNotEmpty()) {
                        val clickedIdx = findClickedChoiceIndex(e.x, e.y, choices.size)
                        if (clickedIdx >= 0) {
                            val snap = vm.selectChoice(clickedIdx)
                            syncVisualAssets(snap)
                            repaint()
                        }
                    } else if (e.button == MouseEvent.BUTTON1) {
                        advanceStep()
                    }
                }
            })

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> advanceStep()
                        KeyEvent.VK_L -> showBacklogDialog()
                        KeyEvent.VK_F5 -> quickSave()
                        KeyEvent.VK_F9 -> quickLoad()
                    }
                }
            })
        }

        private fun findClickedChoiceIndex(mx: Int, my: Int, count: Int): Int {
            val boxW = 640
            val boxH = 48
            val startX = (width - boxW) / 2
            val startY = 180
            for (i in 0 until count) {
                val by = startY + i * (boxH + 16)
                if (mx in startX..(startX + boxW) && my in by..(by + boxH)) {
                    return i
                }
            }
            return -1
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            // 1. Background layer
            val bg = currentBackgroundImg
            if (bg != null) {
                g2.drawImage(bg, 0, 0, width, height, null)
            } else {
                g2.paint = GradientPaint(0f, 0f, Color(25, 32, 56), 0f, height.toFloat(), Color(12, 14, 24))
                g2.fillRect(0, 0, width, height)
            }

            // 2. Character composite sprite layers (.argb)
            if (currentSpriteImages.isNotEmpty()) {
                val count = currentSpriteImages.size
                currentSpriteImages.forEachIndexed { idx, sprite ->
                    val slotCenterX = (width * (idx + 1)) / (count + 1)
                    val drawX = slotCenterX - sprite.width / 2
                    val drawY = height - sprite.height
                    g2.drawImage(sprite, drawX, drawY, null)
                }
            }

            // 3. Dialogue window
            val msgBoxX = 36
            val msgBoxY = height - 220
            val msgBoxW = width - 72
            val msgBoxH = 190

            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.84f)
            g2.color = Color(16, 20, 34)
            g2.fillRoundRect(msgBoxX, msgBoxY, msgBoxW, msgBoxH, 18, 18)

            g2.composite = AlphaComposite.SrcOver
            g2.color = Color(196, 164, 104)
            g2.drawRoundRect(msgBoxX, msgBoxY, msgBoxW, msgBoxH, 18, 18)

            // Speaker badge
            val speaker = vm.currentSpeaker
            if (!speaker.isNullOrBlank()) {
                g2.font = speakerFont
                g2.color = Color(245, 215, 130)
                g2.drawString(speaker, msgBoxX + 26, msgBoxY + 36)
            }

            // Wrapped dialogue text
            g2.font = dialogueFont
            g2.color = Color(242, 244, 250)
            drawWrappedText(
                g2 = g2,
                text = vm.currentText,
                x = msgBoxX + 28,
                y = msgBoxY + 72,
                maxWidth = msgBoxW - 56,
                lineHeight = 30
            )

            // 4. Interactive choice buttons (`^select`)
            val choices = vm.pendingChoices()
            if (choices.isNotEmpty()) {
                val boxW = 640
                val boxH = 48
                val startX = (width - boxW) / 2
                val startY = 180
                choices.forEachIndexed { i, choice ->
                    val by = startY + i * (boxH + 16)
                    g2.color = Color(28, 36, 62, 230)
                    g2.fillRoundRect(startX, by, boxW, boxH, 14, 14)
                    g2.color = Color(220, 188, 118)
                    g2.drawRoundRect(startX, by, boxW, boxH, 14, 14)
                    g2.font = dialogueFont
                    g2.color = Color.WHITE
                    val fm = g2.fontMetrics
                    val tx = startX + (boxW - fm.stringWidth(choice.text)) / 2
                    val ty = by + ((boxH - fm.height) / 2) + fm.ascent
                    g2.drawString(choice.text, tx, ty)
                }
            }
        }

        private fun drawWrappedText(g2: Graphics2D, text: String, x: Int, y: Int, maxWidth: Int, lineHeight: Int) {
            val fm = g2.fontMetrics
            var curY = y
            for (paragraph in text.split('\n')) {
                val words = paragraph.split(' ')
                val lineBuilder = StringBuilder()
                for (word in words) {
                    val candidate = if (lineBuilder.isEmpty()) word else "$lineBuilder $word"
                    if (fm.stringWidth(candidate) > maxWidth && lineBuilder.isNotEmpty()) {
                        g2.drawString(lineBuilder.toString(), x, curY)
                        curY += lineHeight
                        lineBuilder.clear()
                        lineBuilder.append(word)
                    } else {
                        if (lineBuilder.isNotEmpty()) lineBuilder.append(' ')
                        lineBuilder.append(word)
                    }
                }
                if (lineBuilder.isNotEmpty()) {
                    g2.drawString(lineBuilder.toString(), x, curY)
                    curY += lineHeight
                }
            }
        }
    }
}
