package com.charbel.geminirelay.ui

import com.charbel.geminirelay.agent.AgentSession
import com.charbel.geminirelay.agent.PermissionDecision
import com.charbel.geminirelay.agent.PermissionMode
import com.charbel.geminirelay.agent.ProjectAssets
import com.charbel.geminirelay.agent.ProjectMemory
import com.charbel.geminirelay.api.GeminiClient
import com.charbel.geminirelay.api.Part
import com.charbel.geminirelay.api.Usage
import com.charbel.geminirelay.mcp.McpManager
import com.charbel.geminirelay.settings.ConnectionMode
import com.charbel.geminirelay.settings.GeminiSettings
import com.charbel.geminirelay.settings.GeminiSettingsConfigurable
import com.charbel.geminirelay.settings.Persona
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.text.DefaultEditorKit

/**
 * The Gemini Relay chat tool window: a rich transcript, a prompt composer with
 * mode/model pickers and attachable context (editor selection, files, images),
 * and a footer showing token usage. Each send drives one [AgentSession] turn
 * (streaming + tool calls) against the configured backend.
 */
class GeminiChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = GeminiSettings.getInstance()
    private val workingDir = project.basePath ?: System.getProperty("user.dir")

    private val chat: ChatView = if (JBCefApp.isSupported()) ChatWebView(this) else TranscriptView()
    private val mcp = McpManager(settings)
    private var session = AgentSession(workingDir, settings, mcp)

    // The persona this session runs as (its prompt replaces the base system
    // prompt in Agent mode), and any auto-loaded project memory.
    private var activePersona: Persona? = null
    private var projectMemory: String? = null

    // Live model list (Gemini API mode) and project-discovered agents/skills.
    private var liveModels: List<String> = emptyList()
    private var assets = ProjectAssets.Snapshot(emptyList(), emptyList())
    // Debounced background rescans, so discovery never blocks the UI.
    private val assetAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(7, 9)
        emptyText.text = "Ask Gemini…"
        toolTipText = "Enter to send · Shift+Enter for a newline"
    }
    private val sendButton = glyphButton("↑", ACCENT, "Send  ·  Enter")
    private val stopButton = glyphButton("■", STOP_BG, "Stop").apply { isVisible = false }
    private val modeChip = ChipSelector(Mode.entries.toList(), Mode.AGENT) { it.label }
    private val modelChip = ChipSelector(modelChoices(), currentModel()) { it }
    private val permissionChip = ChipSelector(PermissionMode.entries.toList(), PermissionMode.ACCEPT_EDITS) { it.label }
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
    private val usageLabel = JBLabel("").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
    private val busyIcon = AsyncProcessIcon("gemini-busy").apply { isVisible = false }
    private val titleLabel = JBLabel("New chat").apply {
        font = JBUI.Fonts.label().asBold()
        foreground = JBColor.GRAY
    }

    // ---- prompt context: images, files, current editor selection -------------
    private val contextChips = mutableListOf<ContextChip>()
    private val contextPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(7, 10, 1, 10)
        isVisible = false
    }
    private val contextButton = JButton(AllIcons.General.Add).apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Add context — current file / selection, files, images"
        margin = JBUI.emptyInsets()
        addActionListener { showContextMenu() }
    }
    private val tempDir by lazy {
        File(System.getProperty("java.io.tmpdir"), "gemini-relay-${System.currentTimeMillis()}").apply { mkdirs() }
    }
    private var tmpCounter = 0

    private var autoAttachSelection = true
    private var autoChip: ContextChip? = null

    /** One piece of attached context, carrying the Gemini [part] it contributes. */
    private class ContextChip(
        val label: String,
        val icon: Icon?,
        val part: Part,
        val displayMark: String,
        val tooltip: String?,
    )

    private var running = false
    private var hasTitle = false
    private var lastUsage: Usage? = null

    init {
        add(buildHeader(), BorderLayout.NORTH)
        add(chat.component, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        stopButton.addActionListener { stop() }
        modelChip.itemsProvider = { modelChoices() }
        modelChip.onChange = { settings.model = modelChip.selected }
        modeChip.toolTipText = "Agent: reads, edits & runs commands  ·  Ask: read-only answers"
        permissionChip.toolTipText = "How freely Agent mode runs tools — Ask confirms each, Accept edits auto-applies file writes, Bypass runs all"
        permissionChip.isVisible = modeChip.selected == Mode.AGENT
        modeChip.onChange = {
            permissionChip.isVisible = modeChip.selected == Mode.AGENT
            revalidate(); repaint()
        }
        installEnterToSend()
        installImagePaste()
        installSelectionTracking()
        updateControls()
        showConfigHintIfNeeded()
        loadProjectMemory()
        scanAssets()
        fetchModels()
        installAssetWatcher()
    }

    /** Watch the project's persona/skill folders and rescan (debounced, off-EDT)
     *  whenever they change — the same "stay fresh without blocking" approach
     *  editor AI assistants use, instead of re-reading disk on every menu open. */
    private fun installAssetWatcher() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isAssetPath(it.path) }) scheduleAssetScan()
            }
        })
    }

    private fun isAssetPath(path: String): Boolean =
        path.startsWith(workingDir) && ASSET_DIR_MARKERS.any { path.contains(it) }

    private fun scheduleAssetScan() {
        assetAlarm.cancelAllRequests()
        assetAlarm.addRequest({
            val snap = ProjectAssets.scan(workingDir)
            ApplicationManager.getApplication().invokeLater { assets = snap }
        }, 400)
    }

    /** Fetch the live model list (Gemini API mode) off the EDT. */
    private fun fetchModels() {
        if (settings.connectionMode != ConnectionMode.GEMINI_API || !settings.isConfigured()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = runCatching { GeminiClient(settings).listModels() }.getOrDefault(emptyList())
            if (models.isNotEmpty()) ApplicationManager.getApplication().invokeLater {
                liveModels = models
                refreshModelChip()
            }
        }
    }

    /** Discover project agents & skills off the EDT. */
    private fun scanAssets() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val snap = ProjectAssets.scan(workingDir)
            ApplicationManager.getApplication().invokeLater {
                val first = assets.isEmpty
                assets = snap
                if (first && !snap.isEmpty) {
                    chat.addSystem("Discovered ${snap.agents.size} persona(s) and ${snap.skills.size} skill(s) in the project.")
                }
            }
        }
    }

    /** Pick up GEMINI.md / AGENTS.md / CLAUDE.md off the EDT and note it. */
    private fun loadProjectMemory() {
        if (!settings.loadProjectMemory) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val memory = ProjectMemory.read(workingDir) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                projectMemory = memory.text
                chat.addSystem("Loaded project memory from ${memory.file.name}.")
            }
        }
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10),
        )
        add(titleLabel, BorderLayout.CENTER)
    }

    private fun buildSouth(): JPanel {
        val composerWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(buildComposer(), BorderLayout.CENTER)
        }
        val infoRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(busyIcon)
            add(statusLabel)
        }
        val usageRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(usageLabel, BorderLayout.WEST)
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 10, 6, 10)
            add(infoRow, BorderLayout.NORTH)
            add(usageRow, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
            add(composerWrap, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    /** A single rounded composer surface: prompt on top, inline toolbar below. */
    private fun buildComposer(): JComponent {
        val arc = JBUI.scale(14)
        val fill = UIUtil.getTextFieldBackground()

        val composer = object : JPanel(BorderLayout()) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fill
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }
        }

        input.isOpaque = false
        input.border = JBUI.Borders.empty(10, 12, 2, 12)
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, JBUI.scale(64))
        }

        val pickers = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3))).apply {
            isOpaque = false
            add(contextButton)
            add(modeChip)
            add(modelChip)
            add(permissionChip)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = revalidate()
            })
        }
        val action = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(stopButton)
            add(sendButton)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 8, 7, 6)
            add(pickers, BorderLayout.CENTER)
            add(action, BorderLayout.EAST)
        }

        composer.add(contextPanel, BorderLayout.NORTH)
        composer.add(inputScroll, BorderLayout.CENTER)
        composer.add(toolbar, BorderLayout.SOUTH)
        return composer
    }

    fun titleActions(): List<AnAction> = listOf(
        object : DumbAwareAction("New Chat", "Start a new conversation", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = newSession()
        },
        object : DumbAwareAction("Refresh", "Re-read available models and project agents/skills", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                fetchModels()
                scanAssets()
            }
        },
        object : DumbAwareAction("Settings", "Configure the Gemini / Vertex connection", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) = openSettings()
        },
    )

    private fun installEnterToSend() {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK)
        val map = input.inputMap
        val actions = input.actionMap

        map.put(enter, "gemini.send")
        actions.put("gemini.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })

        // Keep the textarea default behavior for Shift+Enter explicit and stable.
        map.put(shiftEnter, DefaultEditorKit.insertBreakAction)
    }

    private fun showConfigHintIfNeeded() {
        if (settings.isConfigured()) return
        chat.addSystem(notConfiguredMessage())
    }

    /** A message that names exactly what's missing for the current mode. */
    private fun notConfiguredMessage(): String =
        if (settings.connectionMode == ConnectionMode.VERTEX_APIGEE && settings.apigeeAgentList().isEmpty()) {
            "Apigee mode needs at least one accessible model. Open Settings (⚙) and fill in “Accessible models”."
        } else {
            "Gemini Relay isn't connected yet. Open Settings (⚙) to set up ${settings.connectionMode.label} and add credentials."
        }

    // ---- editor selection auto-attach ----------------------------------------

    private fun installSelectionTracking() {
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = refreshAutoContext()
        }, this)
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                refreshAutoContext()
                refreshModelChip()
            }
        })
        refreshAutoContext()
    }

    private fun refreshAutoContext() {
        autoChip = if (autoAttachSelection) selectionChip() else null
        rebuildContext()
    }

    /** Snapshot the active editor's selection as a code-snippet text part. */
    private fun selectionChip(): ContextChip? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val sel = editor.selectionModel
        val snippet = sel.selectedText?.takeIf { it.isNotBlank() } ?: return null
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val name = vf?.name ?: "selection"
        val path = vf?.path ?: name
        val start = editor.document.getLineNumber(sel.selectionStart) + 1
        val end = editor.document.getLineNumber(sel.selectionEnd) + 1
        val loc = if (start == end) "$name:$start" else "$name:$start-$end"
        return ContextChip(
            label = loc,
            icon = AllIcons.Actions.MenuPaste,
            part = Part.Text("From $path (lines $start-$end):\n```\n$snippet\n```"),
            displayMark = "📄 $loc",
            tooltip = "Selected lines $start–$end of $name",
        )
    }

    // ---- "+" context menu ----------------------------------------------------

    private fun showContextMenu() {
        // Reads the cached snapshot — kept fresh in the background by the file
        // watcher, so opening the menu never blocks on disk I/O.
        val group = DefaultActionGroup()
        group.add(object : ToggleAction("Auto-attach editor selection") {
            override fun isSelected(e: AnActionEvent) = autoAttachSelection
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                autoAttachSelection = state
                refreshAutoContext()
            }
        })
        group.addSeparator()
        currentEditorFile()?.let { vf ->
            group.add(action("Current file · ${vf.name}", AllIcons.FileTypes.Any_type) { addChip(fileChip(File(vf.path))) })
        }
        group.add(action("Add file…", AllIcons.FileTypes.Any_type) { chooseFiles(imagesOnly = false) })
        group.add(action("Add image…", AllIcons.FileTypes.Image) { chooseFiles(imagesOnly = true) })

        // Personas from Settings + personas discovered in the project code.
        // Always shown, so the "where to add them" hint is discoverable.
        val personas = settings.personas.filter { it.name.isNotBlank() } +
            assets.agents.map { Persona(it.name, it.prompt) }
        group.addSeparator()
        val personaSub = DefaultActionGroup("Run as persona (${personas.size})", true)
        if (personas.isEmpty()) {
            personaSub.add(disabledInfo("Add in Settings → Personas, or as .md files in .gemini/personas/"))
        } else {
            personas.forEach { persona ->
                personaSub.add(object : ToggleAction(persona.name, persona.prompt.lineSequence().firstOrNull(), null) {
                    override fun isSelected(e: AnActionEvent) = activePersona?.name == persona.name
                    override fun setSelected(e: AnActionEvent, state: Boolean) =
                        setActivePersona(if (state) persona else null)
                })
            }
        }
        group.add(personaSub)

        // Skills discovered in the project — attach one as context for this turn.
        val skillSub = DefaultActionGroup("Skills (${assets.skills.size})", true)
        if (assets.skills.isEmpty()) {
            skillSub.add(disabledInfo("Add as .gemini/skills/<name>/SKILL.md in your project"))
        } else {
            assets.skills.forEach { skill ->
                skillSub.add(action(skill.name) { addChip(skillChip(skill)) })
            }
        }
        group.add(skillSub)

        JBPopupFactory.getInstance().createActionGroupPopup(
            "Add context", group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        ).showUnderneathOf(contextButton)
    }

    /** A persona is single-select: re-picking the active one clears it. */
    private fun setActivePersona(persona: Persona?) {
        activePersona = persona
        rebuildContext()
    }

    /** A project skill attaches as a context part instructing the model to use it. */
    private fun skillChip(skill: ProjectAssets.Skill): ContextChip {
        val body = skill.instructions.let { if (it.length > MAX_FILE_CHARS) it.take(MAX_FILE_CHARS) + "\n… (truncated)" else it }
        return ContextChip(
            label = "skill: ${skill.name}",
            icon = null,
            part = Part.Text("Use the \"${skill.name}\" skill for this request. Its instructions:\n\n$body"),
            displayMark = "↳ ${skill.name} skill",
            tooltip = skill.description ?: skill.name,
        )
    }

    private fun action(text: String, icon: Icon? = null, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    /** A greyed-out, non-clickable menu entry used to show a hint. */
    private fun disabledInfo(text: String): AnAction =
        object : DumbAwareAction(text) {
            override fun actionPerformed(e: AnActionEvent) {}
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
        }

    private fun chooseFiles(imagesOnly: Boolean) {
        var descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle(if (imagesOnly) "Select Image" else "Select File")
        if (imagesOnly) descriptor = descriptor.withFileFilter { it.extension?.lowercase() in IMAGE_EXTS }
        FileChooser.chooseFiles(descriptor, project, null).forEach { vf ->
            val file = File(vf.path)
            addChip(if (vf.extension?.lowercase() in IMAGE_EXTS) imageChip(file) else fileChip(file))
        }
    }

    private fun currentEditorFile() = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    // ---- chip factories ------------------------------------------------------

    /** Text files embed their (truncated) content; image files go inline. */
    private fun fileChip(file: File): ContextChip {
        if (file.extension?.lowercase() in IMAGE_EXTS) return imageChip(file)
        val content = runCatching { file.readText() }.getOrElse { "(could not read ${file.name})" }
        val clipped = if (content.length > MAX_FILE_CHARS) content.take(MAX_FILE_CHARS) + "\n… (truncated)" else content
        return ContextChip(
            label = file.name,
            icon = AllIcons.FileTypes.Any_type,
            part = Part.Text("File ${file.name}:\n```\n$clipped\n```"),
            displayMark = "📄 ${file.name}",
            tooltip = file.absolutePath,
        )
    }

    private fun imageChip(file: File): ContextChip {
        val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        val mime = mimeForExtension(file.extension)
        return ContextChip(
            label = file.name,
            icon = thumbnail(file),
            part = Part.InlineData(mime, Base64.getEncoder().encodeToString(bytes)),
            displayMark = "📎 ${file.name}",
            tooltip = file.absolutePath,
        )
    }

    // ---- clipboard image paste ----------------------------------------------

    private fun installImagePaste() {
        val fallback = input.actionMap.get("paste-from-clipboard")
        input.actionMap.put("paste-from-clipboard", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (!pasteImageFromClipboard()) fallback?.actionPerformed(e)
            }
        })
    }

    private fun pasteImageFromClipboard(): Boolean {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return false
        val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return false
        val buffered = toBufferedImage(image)
        val bytes = ByteArrayOutputStream().use { ImageIO.write(buffered, "png", it); it.toByteArray() }
        val file = runCatching {
            File(tempDir, "paste-${tmpCounter++}.png").also { it.writeBytes(bytes) }
        }.getOrNull()
        addChip(
            ContextChip(
                label = "pasted image",
                icon = scaledIcon(buffered),
                part = Part.InlineData("image/png", Base64.getEncoder().encodeToString(bytes)),
                displayMark = "📎 pasted image",
                tooltip = file?.absolutePath ?: "clipboard image",
            ),
        )
        return true
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return buffered
    }

    // ---- chip list management ------------------------------------------------

    private fun addChip(chip: ContextChip) {
        contextChips.add(chip)
        rebuildContext()
    }

    private fun clearContext() {
        contextChips.clear()
        rebuildContext()
    }

    private fun rebuildContext() {
        contextPanel.removeAll()
        // Active persona shows first, as an accent pill.
        activePersona?.let { p ->
            contextPanel.add(pill("▸ ${p.name}", null, "Running as the \"${p.name}\" persona", accent = true, removeTip = "Clear persona") {
                setActivePersona(null)
            })
        }
        contextChips.forEach { chip ->
            contextPanel.add(pill(chip.label, chip.icon, chip.tooltip, accent = false, removeTip = "Remove") {
                contextChips.remove(chip)
                rebuildContext()
            })
        }
        val auto = autoChip.takeIf { autoAttachSelection }
        auto?.let {
            contextPanel.add(pill("✦ ${it.label}", it.icon, "Auto-attached selection — click ✕ to turn off", accent = true, removeTip = "Turn off auto-attach") {
                autoAttachSelection = false
                refreshAutoContext()
            })
        }
        contextPanel.isVisible = activePersona != null || contextChips.isNotEmpty() || auto != null
        contextPanel.revalidate()
        contextPanel.repaint()
        revalidate()
        repaint()
    }

    /** A small rounded context pill with a remove (✕) button. */
    private fun pill(label: String, icon: Icon?, tooltip: String?, accent: Boolean, removeTip: String, onRemove: () -> Unit): JComponent {
        val borderColor: Color = if (accent) ACCENT else JBColor.border()
        val comp = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val fg = JBColor.foreground()
                g2.color = Color(fg.red, fg.green, fg.blue, 16)
                val arc = JBUI.scale(10)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        comp.toolTipText = tooltip
        comp.add(JBLabel(truncate(label, 28)).apply {
            this.icon = icon
            font = JBUI.Fonts.smallFont()
        })
        comp.add(JButton(AllIcons.Actions.Close).apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = removeTip
            addActionListener { onRemove() }
        })
        return comp
    }

    private fun thumbnail(file: File): Icon? = runCatching { scaledIcon(ImageIO.read(file) ?: return null) }.getOrNull()

    private fun scaledIcon(img: BufferedImage): Icon {
        val h = JBUI.scale(26)
        val w = (img.width.toDouble() / img.height * h).toInt().coerceIn(JBUI.scale(10), JBUI.scale(72))
        return ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH))
    }

    // ---- send loop -----------------------------------------------------------

    private fun send() {
        if (running) return
        val text = input.text.trim()
        val context = contextChips + listOfNotNull(autoChip.takeIf { autoAttachSelection })
        if (text.isEmpty() && context.isEmpty()) return
        if (!settings.isConfigured()) {
            chat.addError(notConfiguredMessage())
            openSettings()
            return
        }

        input.text = ""
        clearContext()
        if (!hasTitle) setTitle(text.ifBlank { context.firstOrNull()?.label ?: "Context" })
        val persona = activePersona
        val display = buildDisplay(text, context).let {
            if (persona != null) "$it\n\n▸ running as \"${persona.name}\"" else it
        }
        chat.addUser(display)
        chat.setBusy(true)
        running = true
        statusLabel.text = "Working…"
        busyIcon.isVisible = true
        busyIcon.resume()
        updateControls()

        val askMode = modeChip.selected == Mode.ASK
        session.send(buildParts(text, context), askMode, composeSystemPrompt(askMode, persona), permissionChip.selected, ::askPermission, object : AgentSession.Listener {
            override fun onAssistantText(text: String) = chat.assistantChunk(text)
            override fun onToolUse(name: String, summary: String) {
                chat.endAssistant()
                chat.addToolUse(name, summary)
            }
            override fun onToolResult(text: String, isError: Boolean) = chat.addToolResult(text, isError)
            override fun onUsage(usage: Usage) {
                lastUsage = usage
                updateUsageLabel()
            }
            override fun onError(message: String) = chat.addError(message)
            override fun onComplete() = finishTurn()
        })
    }

    /** Compose the turn's system prompt: persona (or base/ask) + project memory. */
    private fun composeSystemPrompt(askMode: Boolean, persona: Persona?): String {
        val base = when {
            askMode -> ASK_SYSTEM_PROMPT
            persona != null && persona.prompt.isNotBlank() -> persona.prompt
            else -> settings.systemPrompt
        }
        val memory = projectMemory?.takeIf { it.isNotBlank() }
        return if (memory == null) base else "$base\n\n# Project memory\n$memory"
    }

    /** Assemble the Gemini user parts: the typed text, then each attachment. */
    private fun buildParts(text: String, context: List<ContextChip>): List<Part> {
        val parts = mutableListOf<Part>()
        val intro = text.ifBlank { if (context.isNotEmpty()) "Please use the attached context." else "" }
        if (intro.isNotBlank()) parts.add(Part.Text(intro))
        context.forEach { parts.add(it.part) }
        return parts
    }

    private fun buildDisplay(text: String, context: List<ContextChip>): String {
        if (context.isEmpty()) return text
        val marks = context.joinToString("\n") { it.displayMark }
        return if (text.isBlank()) marks else "$text\n\n$marks"
    }

    /** Blocking permission prompt (called on the agent thread). */
    private fun askPermission(toolName: String, summary: String): PermissionDecision {
        var decision = PermissionDecision.DENY
        ApplicationManager.getApplication().invokeAndWait {
            val detail = if (summary.isBlank()) "" else "\n\n$summary"
            val choice = Messages.showDialog(
                project,
                "Allow Gemini to run \"$toolName\"?$detail",
                "Gemini Relay — Permission",
                arrayOf("Allow", "Allow for This Chat", "Deny"),
                0,
                Messages.getQuestionIcon(),
            )
            decision = when (choice) {
                0 -> PermissionDecision.ALLOW_ONCE
                1 -> PermissionDecision.ALLOW_ALWAYS
                else -> PermissionDecision.DENY
            }
        }
        return decision
    }

    private fun stop() = session.cancel()

    private fun newSession() {
        if (running) stop()
        session.reset()
        session = AgentSession(workingDir, settings, mcp)
        lastUsage = null
        hasTitle = false
        clearContext()
        setTitle(null)
        chat.clear()
        chat.addSystem("New conversation started.")
        statusLabel.text = ""
        updateUsageLabel()
    }

    private fun finishTurn() {
        running = false
        chat.endAssistant()
        chat.setBusy(false)
        busyIcon.isVisible = false
        busyIcon.suspend()
        statusLabel.text = ""
        updateControls()
        input.requestFocusInWindow()
    }

    private fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, GeminiSettingsConfigurable::class.java)

    private fun setTitle(title: String?) {
        val shown = title?.takeIf { it.isNotBlank() }?.let { truncate(it.replace(Regex("\\s+"), " ").trim(), 70) }
        hasTitle = shown != null
        titleLabel.text = shown ?: "New chat"
        titleLabel.foreground = if (shown == null) JBColor.GRAY else JBColor.foreground()
    }

    private fun updateUsageLabel() {
        val u = lastUsage
        usageLabel.text = if (u == null) "" else
            "%,d in · %,d out · %,d total".format(u.promptTokens, u.candidateTokens, u.totalTokens)
        usageLabel.toolTipText = "Model: ${settings.model}  ·  ${settings.connectionMode.label}"
    }

    private fun updateControls() {
        sendButton.isVisible = !running
        sendButton.isEnabled = !running
        stopButton.isVisible = running
        input.isEnabled = !running
    }

    /** Picker choices: in Apigee mode, the accessible models; otherwise the
     *  suggested Gemini models plus whatever is currently set. */
    private fun modelChoices(): List<String> = when (settings.connectionMode) {
        ConnectionMode.VERTEX_APIGEE ->
            settings.apigeeAgentList().ifEmpty { listOf(settings.model).filter { it.isNotBlank() } }
        ConnectionMode.GEMINI_API -> {
            val base = liveModels.ifEmpty { GeminiSettings.MODEL_CHOICES }
            (base + settings.model).filter { it.isNotBlank() }.distinct()
        }
        else -> (GeminiSettings.MODEL_CHOICES + settings.model).filter { it.isNotBlank() }.distinct()
    }

    private fun currentModel(): String {
        val choices = modelChoices()
        return if (settings.model in choices) settings.model else choices.firstOrNull() ?: settings.model
    }

    /** Keep the picker's selection valid for the current mode/agent list. */
    private fun refreshModelChip() {
        val choices = modelChoices()
        if (modelChip.selected !in choices) {
            val pick = choices.firstOrNull() ?: settings.model
            modelChip.selected = pick
            settings.model = pick
        }
    }

    private fun mimeForExtension(ext: String?): String = when (ext?.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    override fun dispose() {
        session.cancel()
        mcp.dispose()
        runCatching { tempDir.deleteRecursively() }
    }

    // ---- small widgets -------------------------------------------------------

    private fun <T> showChooser(anchor: java.awt.Component?, items: List<T>, render: (T) -> String, onPick: (T) -> Unit) {
        if (items.isEmpty()) return
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setRenderer(SimpleListCellRenderer.create<T>("") { render(it) })
            .setItemChosenCallback { onPick(it) }
            .createPopup()
        if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showInFocusCenter()
    }

    private inner class ChipSelector<T>(
        items: List<T>,
        initial: T,
        private val render: (T) -> String,
    ) : JButton() {
        private var items: List<T> = items
        var onChange: (() -> Unit)? = null
        /** When set, the chooser recomputes its items on each open. */
        var itemsProvider: (() -> List<T>)? = null
        var selected: T = initial
            set(value) { field = value; updateText() }

        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2, 7)
            foreground = JBColor.foreground()
            addActionListener {
                showChooser(this, itemsProvider?.invoke() ?: items, render) { sel ->
                    selected = sel
                    onChange?.invoke()
                }
            }
            updateText()
        }

        private fun updateText() { text = "${render(selected)}  ▾" }

        override fun paintComponent(g: Graphics) {
            if (model.isRollover || model.isPressed) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val fg = UIUtil.getLabelForeground()
                g2.color = Color(fg.red, fg.green, fg.blue, 28)
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private fun glyphButton(glyph: String, bg: Color, tip: String): JButton = object : JButton(glyph) {
        init {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, (font.size + JBUI.scale(3)).toFloat())
            preferredSize = Dimension(JBUI.scale(38), JBUI.scale(28))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tip
            margin = JBUI.emptyInsets()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (model.isRollover && isEnabled) bg.brighter() else bg
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(9), JBUI.scale(9))
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private fun truncate(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s

    private enum class Mode(val label: String) {
        AGENT("Agent"),
        ASK("Ask");

        override fun toString() = label
    }

    companion object {
        private val ACCENT = Color(0x42, 0x85, 0xF4)
        private val STOP_BG = Color(0x8A, 0x46, 0x42)
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private const val MAX_FILE_CHARS = 40_000
        private val ASSET_DIR_MARKERS = listOf(
            "/.gemini/personas/", "/.gemini/agents/", "/.claude/agents/",
            "/.gemini/skills/", "/.claude/skills/",
        )
        private val ASK_SYSTEM_PROMPT = """
            You are Gemini Relay in read-only "ask" mode: answer questions about the user's code.
            You cannot modify files or run commands — explain, review, and suggest code in your reply instead.
            If the user's message includes attached context, use it to ground your answer.
        """.trimIndent()
    }
}
