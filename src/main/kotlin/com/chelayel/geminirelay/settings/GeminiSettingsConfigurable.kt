package com.chelayel.geminirelay.settings

import com.chelayel.geminirelay.agent.Web
import com.chelayel.geminirelay.mcp.McpClient
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.CollectionListModel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings → Tools → Gemini Relay. Lets the user pick a connection mode and
 * fill in the parameters that mode needs:
 *  - Gemini API  → API key.
 *  - Vertex AI   → project, location (+ optional gcloud path for the token).
 *  - Vertex+Apigee → project, location, gateway host, OAuth token URL + client.
 *
 * Irrelevant fields are disabled as the mode changes, so the form always shows
 * exactly what the active connection requires.
 */
class GeminiSettingsConfigurable : Configurable {

    private val settings = GeminiSettings.getInstance()

    private val modeCombo = JComboBox(DefaultComboBoxModel(ConnectionMode.entries.toTypedArray())).apply {
        renderer = textListCellRenderer<ConnectionMode?> { mode -> mode?.let { "${it.label} — ${it.blurb}" }.orEmpty() }
    }
    private val modelCombo = JComboBox<String>(
        comboModel(GeminiSettings.modelChoices(ConnectionMode.GEMINI_API)),
    ).apply { isEditable = true }

    private val apiKeyField = JBPasswordField()

    private val projectField = JBTextField()
    private val locationField = JBTextField()
    private val endpointField = JBTextField()
    private val gcloudField = JBTextField()

    private val tokenUrlField = JBTextField()
    private val clientIdField = JBTextField()
    private val clientSecretField = JBPasswordField()
    private val apigeeAgentsArea = JBTextArea(4, 40)
    private val apigeeAgentsWarning = JBLabel("⚠ Required in Apigee mode — add at least one model.").apply {
        foreground = JBColor.RED
        font = JBUI.Fonts.smallFont()
        isVisible = false
    }

    private val systemPromptArea = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val commandTimeoutSpinner = JSpinner(SpinnerNumberModel(300, 300, 3600, 1))
    private val maxRoundsSpinner = JSpinner(SpinnerNumberModel(300, 1, 5000, 10))
    private val historyWindowSpinner = JSpinner(SpinnerNumberModel(240, 20, 5000, 10))

    private val thinkingCombo = JComboBox(DefaultComboBoxModel(THINKING_LEVELS.toTypedArray())).apply {
        renderer = textListCellRenderer<String?> { level -> level?.ifBlank { "Default (send nothing)" }.orEmpty() }
    }
    private val thinkingBudgetSpinner = JSpinner(SpinnerNumberModel(-1, -1, 32_768, 128))

    private val webEnabledCheck = JBCheckBox("Let the agent search and read the web")
    private val searchProviderCombo = JComboBox(DefaultComboBoxModel(Web.Provider.entries.toTypedArray()))
    private val searchKeyField = JBPasswordField()
    private val searchCxField = JBTextField()

    /** Calls each web tool for real, using what's typed in the form right now. */
    private val webTestButton = JButton("Test web access")
    private val webTestResult = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        isVisible = false
    }

    /** Unlike the Apigee agent list this only degrades a feature rather than
     *  breaking the connection, so it warns instead of refusing to save. */
    private val searchWarning = JBLabel().apply {
        foreground = JBColor.RED
        font = JBUI.Fonts.smallFont()
        isVisible = false
    }

    private val loadMemoryCheck = JBCheckBox("Load project memory (GEMINI.md / AGENTS.md / CLAUDE.md) as context")

    private val personaModel = CollectionListModel<Persona>()
    private val personaList = JBList(personaModel).apply {
        cellRenderer = textListCellRenderer<Persona?> { persona -> persona?.name?.ifBlank { "(unnamed)" }.orEmpty() }
    }
    private val mcpModel = CollectionListModel<McpServerConfig>()
    private val mcpList = JBList(mcpModel).apply {
        cellRenderer = textListCellRenderer<McpServerConfig?> { config ->
            config?.let {
                (if (it.enabled) it.name else "${it.name} (disabled)") + "  —  ${it.command}"
            }.orEmpty()
        }
    }

    override fun getDisplayName(): String = "Gemini Relay"

    override fun createComponent(): JComponent {
        modeCombo.addActionListener { updateEnablement() }
        apigeeAgentsArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateEnablement()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateEnablement()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateEnablement()
        })
        webEnabledCheck.addActionListener { updateWebEnablement() }
        searchProviderCombo.addActionListener { updateWebEnablement() }
        onEdit(searchKeyField) { updateWebEnablement() }
        onEdit(searchCxField) { updateWebEnablement() }
        webTestButton.addActionListener { testWebAccess() }

        val promptScroll = JScrollPane(systemPromptArea).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(120))
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(sectionLabel("Connection"))
            .addLabeledComponent("Mode:", modeCombo)
            .addLabeledComponent("Model:", modelCombo)
            .addSeparator()
            .addComponent(sectionLabel("Gemini API"))
            .addLabeledComponent("API key:", apiKeyField)
            .addComponent(hint("From Google AI Studio — used only in “Gemini API” mode."))
            .addSeparator()
            .addComponent(sectionLabel("Vertex AI / Apigee"))
            .addLabeledComponent("Project ID:", projectField)
            .addLabeledComponent("Location:", locationField)
            .addLabeledComponent("API endpoint host:", endpointField)
            .addComponent(hint("Apigee gateway host (e.g. my-gw.example.com). Leave blank for standard Vertex."))
            .addLabeledComponent("gcloud path:", gcloudField)
            .addComponent(hint("Standard Vertex obtains a token via `gcloud auth print-access-token`. Blank = use `gcloud` on PATH."))
            .addSeparator()
            .addComponent(sectionLabel("Apigee OAuth (client credentials)"))
            .addLabeledComponent("Token URL:", tokenUrlField)
            .addLabeledComponent("Client ID:", clientIdField)
            .addLabeledComponent("Client secret:", clientSecretField)
            .addLabeledComponent("Accessible models *:", JScrollPane(apigeeAgentsArea).apply {
                preferredSize = Dimension(JBUI.scale(360), JBUI.scale(80))
            })
            .addComponent(hint("Required in Apigee mode — one model id per line. These become the model picker's choices."))
            .addComponent(apigeeAgentsWarning)
            .addSeparator()
            .addComponent(sectionLabel("Agent"))
            .addLabeledComponent("System prompt:", promptScroll)
            .addLabeledComponent("Command timeout (s):", commandTimeoutSpinner)
            .addLabeledComponent("Max tool rounds per turn:", maxRoundsSpinner)
            .addLabeledComponent("History window (turns):", historyWindowSpinner)
            .addComponent(hint("A migration-sized task legitimately runs for hundreds of rounds; a question needs a handful."))
            .addComponent(loadMemoryCheck)
            .addSeparator()
            .addComponent(sectionLabel("Thinking"))
            .addLabeledComponent("Thinking level:", thinkingCombo)
            .addLabeledComponent("Thinking budget (tokens):", thinkingBudgetSpinner)
            .addComponent(hint("Gemini 3.x takes the level, the 2.5 family the budget — set one, not both. Budget -1 = send nothing, 0 = no thinking."))
            .addComponent(hint("Both are off by default: a gateway that validates the body rejects a field its schema doesn't know."))
            .addSeparator()
            .addComponent(sectionLabel("Web access"))
            .addComponent(webEnabledCheck)
            .addLabeledComponent("Search provider:", searchProviderCombo)
            .addLabeledComponent("Search API key:", searchKeyField)
            .addLabeledComponent("Google engine id (cx):", searchCxField)
            .addComponent(hint("Reading a URL and looking up Maven Central need no key. Search does — pick a provider and paste its key."))
            .addComponent(hint("Without one, webSearch is not offered to the model at all, rather than failing every call."))
            .addComponent(searchWarning)
            .addComponent(webTestButton)
            .addComponent(hint("Calls each web tool once, live, with the values above — no need to save first."))
            .addComponent(webTestResult)
            .addSeparator()
            .addComponent(sectionLabel("Personas"))
            .addComponent(hint("Named system-prompt presets — pick one from the composer's “+” menu (“Run as persona”)."))
            .addComponent(hint("Add them here, or as markdown files in the project: .gemini/personas/ , .gemini/agents/ , or .claude/agents/ (frontmatter name/description; body = prompt)."))
            .addComponent(listPanel(personaList, { editDialog(PersonaDialog(null)) }, { editDialog(PersonaDialog(it)) }))
            .addSeparator()
            .addComponent(sectionLabel("MCP tool servers"))
            .addComponent(hint("External Model Context Protocol servers (stdio). Their tools are added to Agent mode."))
            .addComponent(
                listPanel(mcpList, { editDialog(McpDialog(null)) }, { editDialog(McpDialog(it)) }, testMcpAction()),
            )
            .addComponent(hint("“Test” starts the selected server and lists the tools it advertises — the quickest way to tell a wrong command from a server that starts and offers nothing."))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return form
    }

    /** A list with an add/edit/remove toolbar, plus any [extra] toolbar actions. */
    private fun <T> listPanel(
        list: JBList<T>,
        onAdd: () -> T?,
        onEdit: (T) -> T?,
        vararg extra: AnAction,
    ): JComponent {
        @Suppress("UNCHECKED_CAST")
        val model = list.model as CollectionListModel<T>
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { onAdd()?.let { model.add(it); list.selectedIndex = model.size - 1 } }
            .setEditAction {
                val idx = list.selectedIndex
                if (idx >= 0) onEdit(model.getElementAt(idx))?.let { model.setElementAt(it, idx) }
            }
            .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let { model.remove(it) } }
            .also { decorator -> extra.forEach { decorator.addExtraAction(it) } }
            .createPanel()
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(110))
            add(decorated, BorderLayout.CENTER)
        }
    }

    private fun <T> editDialog(dialog: ItemDialog<T>): T? =
        if (dialog.showAndGet()) dialog.result() else null

    /** Run [block] on every change to a text field's content. */
    private fun onEdit(field: javax.swing.text.JTextComponent, block: () -> Unit) {
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = block()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = block()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = block()
        })
    }

    private fun sectionLabel(text: String): JComponent =
        JBLabel(text).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(8, 0, 2, 0)
        }

    private fun hint(text: String): JComponent =
        JBLabel(text).apply {
            foreground = com.intellij.ui.JBColor.GRAY
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.emptyBottom(4)
        }

    private fun updateEnablement() {
        val mode = modeCombo.selectedItem as ConnectionMode
        apiKeyField.isEnabled = mode == ConnectionMode.GEMINI_API

        val vertexish = mode == ConnectionMode.VERTEX || mode == ConnectionMode.VERTEX_APIGEE
        projectField.isEnabled = vertexish
        locationField.isEnabled = vertexish

        endpointField.isEnabled = mode == ConnectionMode.VERTEX_APIGEE
        gcloudField.isEnabled = mode == ConnectionMode.VERTEX

        val apigee = mode == ConnectionMode.VERTEX_APIGEE
        tokenUrlField.isEnabled = apigee
        clientIdField.isEnabled = apigee
        clientSecretField.isEnabled = apigee
        apigeeAgentsArea.isEnabled = apigee
        apigeeAgentsWarning.isVisible = apigee && parseAgents(apigeeAgentsArea.text).isEmpty()

        updateWebEnablement()
        syncModelChoices(mode)
    }

    /** Keep the suggestions in the (still free-form) model box matching the mode:
     *  Apigee offers only what the gateway lists, and the two Google surfaces name
     *  some models differently, so a selection with an equivalent id follows along.  */
    private fun syncModelChoices(mode: ConnectionMode) {
        val suggested =
            if (mode == ConnectionMode.VERTEX_APIGEE) parseAgents(apigeeAgentsArea.text)
            else GeminiSettings.modelChoices(mode)
        val current = GeminiSettings.canonicalModel(modelText(), mode)
        val choices = (suggested + current).filter { it.isNotBlank() }.distinct()
        // Rebuilt on every enablement pass, including each keystroke in the agents
        // area — skip the churn (and the editor reset) when nothing changed.
        val shown = (0 until modelCombo.model.size).map { modelCombo.model.getElementAt(it) }
        if (choices != shown) modelCombo.model = comboModel(choices)
        if (modelText() != current) modelCombo.selectedItem = current
    }

    /** Show only the search fields the chosen provider actually uses. */
    private fun updateWebEnablement() {
        val web = webEnabledCheck.isSelected
        val provider = searchProvider()
        searchProviderCombo.isEnabled = web
        searchKeyField.isEnabled = web && provider != Web.Provider.NONE
        searchCxField.isEnabled = web && provider == Web.Provider.GOOGLE

        val missing = when {
            !web || provider == Web.Provider.NONE -> null
            searchKeyField.password.isEmpty() -> "an API key"
            provider == Web.Provider.GOOGLE && searchCxField.text.isBlank() ->
                "a search engine id (cx)"
            else -> null
        }
        searchWarning.text = missing?.let { "⚠ ${provider.label} needs $it — until then the model is not offered webSearch." }.orEmpty()
        searchWarning.isVisible = missing != null
    }

    private fun parseAgents(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    // ---- live checks ---------------------------------------------------------
    //
    // Both of these run the real thing against the values currently in the form,
    // so a setting can be proved before it is saved and long before a turn is
    // spent on it. Everything they do is network or child-process work, so it
    // goes through a modal progress — the block runs on a pooled thread and the
    // EDT only paints.

    private fun testWebAccess() {
        val web = Web(formWebSettings())
        val report = runWithProgress("Testing Web Access") { web.diagnose() } ?: return
        webTestResult.text = asHtml(report)
        webTestResult.isVisible = true
    }

    /** What's typed in the form right now, not what was last saved. */
    private fun formWebSettings(): Web.Settings = object : Web.Settings {
        override val webEnabled = webEnabledCheck.isSelected
        override val searchProvider = searchProvider().id
        override val searchApiKey = String(searchKeyField.password)
        override val searchCx = searchCxField.text.trim()
    }

    private fun testMcpAction(): AnAction =
        object : AnAction("Test", "Start the selected server and list its tools", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = mcpList.selectedIndex >= 0
            }
            override fun actionPerformed(e: AnActionEvent) {
                val config = mcpList.selectedValue ?: return
                val probe = runWithProgress("Starting “${config.name}”") { probeMcp(config) } ?: return
                val title = "MCP Server: ${config.name}"
                if (probe.ok) Messages.showInfoMessage(probe.text, title)
                else Messages.showErrorDialog(probe.text, title)
            }
        }

    private class Probe(val ok: Boolean, val text: String)

    /** Start one server, list its tools, and always shut it down again. */
    private fun probeMcp(config: McpServerConfig): Probe {
        val client = McpClient(config)
        return try {
            val tools = client.listTools()
            Probe(
                ok = tools.isNotEmpty(),
                text = if (tools.isEmpty()) {
                    "The server started and handshook, but advertises no tools."
                } else {
                    "The server started and advertises ${tools.size} tool(s):\n\n" +
                        tools.joinToString("\n") { tool ->
                            val desc = tool.description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(90)
                            "• ${tool.name}" + if (desc.isBlank()) "" else " — $desc"
                        }
                },
            )
        } catch (e: Exception) {
            // A server that dies on startup explains itself only on stderr, and
            // the client keeps the tail for exactly this.
            val message = e.message.orEmpty().ifBlank { e::class.simpleName.orEmpty() }
            val tail = client.stderrTail().takeIf { it.isNotBlank() && it !in message }
            Probe(false, message + tail?.let { "\n\nServer stderr:\n$it" }.orEmpty())
        } finally {
            client.close()
        }
    }

    /**
     * Run [block] off the EDT behind a cancellable modal progress. Returns null
     * if the user cancelled, so callers can simply drop the result.
     */
    private fun <T> runWithProgress(title: String, block: () -> T): T? = try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, Exception>(
            block, "$title…", true, null,
        )
    } catch (_: ProcessCanceledException) {
        null
    } catch (e: Exception) {
        Messages.showErrorDialog(e.message ?: e::class.simpleName ?: "Failed.", title)
        null
    }

    /** JBLabel renders one line unless it's told otherwise. */
    private fun asHtml(text: String): String = text.trim().lines().joinToString(
        separator = "<br>",
        prefix = "<html>",
        postfix = "</html>",
    ) { line ->
        val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val indent = escaped.takeWhile { it == ' ' }.length
        "&nbsp;".repeat(indent) + escaped.drop(indent)
    }

    override fun isModified(): Boolean =
        (modeCombo.selectedItem as ConnectionMode) != settings.connectionMode ||
            modelText() != settings.model ||
            String(apiKeyField.password) != settings.geminiApiKey ||
            projectField.text.trim() != settings.vertexProjectId ||
            locationField.text.trim() != settings.vertexLocation ||
            endpointField.text.trim() != settings.vertexApiEndpoint ||
            gcloudField.text.trim() != settings.gcloudPath ||
            tokenUrlField.text.trim() != settings.apigeeTokenUrl ||
            clientIdField.text.trim() != settings.apigeeClientId ||
            String(clientSecretField.password) != settings.apigeeClientSecret ||
            apigeeAgentsArea.text != settings.apigeeAgents ||
            systemPromptArea.text != settings.systemPrompt ||
            (commandTimeoutSpinner.value as Int) != settings.commandTimeoutSeconds ||
            (maxRoundsSpinner.value as Int) != settings.maxToolRounds ||
            (historyWindowSpinner.value as Int) != settings.historyWindow ||
            thinkingLevelText() != settings.thinkingLevel ||
            (thinkingBudgetSpinner.value as Int) != settings.thinkingBudget ||
            webEnabledCheck.isSelected != settings.webEnabled ||
            searchProvider().id != settings.searchProvider ||
            String(searchKeyField.password) != settings.searchApiKey ||
            searchCxField.text.trim() != settings.searchCx ||
            loadMemoryCheck.isSelected != settings.loadProjectMemory ||
            !personasEqual(items(personaModel), settings.personas) ||
            !mcpEqual(items(mcpModel), settings.mcpServers)

    override fun apply() {
        val selectedMode = modeCombo.selectedItem as ConnectionMode
        if (selectedMode == ConnectionMode.VERTEX_APIGEE && parseAgents(apigeeAgentsArea.text).isEmpty()) {
            throw ConfigurationException("Apigee mode requires at least one accessible model. Add one model id per line under “Accessible models”.")
        }
        settings.connectionMode = modeCombo.selectedItem as ConnectionMode
        settings.model = modelText()
        settings.geminiApiKey = String(apiKeyField.password)
        settings.vertexProjectId = projectField.text
        settings.vertexLocation = locationField.text
        settings.vertexApiEndpoint = endpointField.text
        settings.gcloudPath = gcloudField.text
        settings.apigeeTokenUrl = tokenUrlField.text
        settings.apigeeClientId = clientIdField.text
        settings.apigeeClientSecret = String(clientSecretField.password)
        settings.apigeeAgents = apigeeAgentsArea.text
        settings.systemPrompt = systemPromptArea.text
        settings.commandTimeoutSeconds = commandTimeoutSpinner.value as Int
        settings.maxToolRounds = maxRoundsSpinner.value as Int
        settings.historyWindow = historyWindowSpinner.value as Int
        settings.thinkingLevel = thinkingLevelText()
        settings.thinkingBudget = thinkingBudgetSpinner.value as Int
        settings.webEnabled = webEnabledCheck.isSelected
        settings.searchProvider = searchProvider().id
        settings.searchApiKey = String(searchKeyField.password)
        settings.searchCx = searchCxField.text
        settings.loadProjectMemory = loadMemoryCheck.isSelected
        settings.personas.apply { clear(); addAll(items(personaModel)) }
        settings.mcpServers.apply { clear(); addAll(items(mcpModel)) }
        // A credential change may invalidate a cached bearer token.
        com.chelayel.geminirelay.api.AuthProvider.invalidate()
    }

    override fun reset() {
        modeCombo.selectedItem = settings.connectionMode
        modelCombo.selectedItem = settings.model
        apiKeyField.text = settings.geminiApiKey
        projectField.text = settings.vertexProjectId
        locationField.text = settings.vertexLocation
        endpointField.text = settings.vertexApiEndpoint
        gcloudField.text = settings.gcloudPath
        tokenUrlField.text = settings.apigeeTokenUrl
        clientIdField.text = settings.apigeeClientId
        clientSecretField.text = settings.apigeeClientSecret
        apigeeAgentsArea.text = settings.apigeeAgents
        apigeeAgentsArea.caretPosition = 0
        systemPromptArea.text = settings.systemPrompt
        systemPromptArea.caretPosition = 0
        commandTimeoutSpinner.value = settings.commandTimeoutSeconds
        maxRoundsSpinner.value = settings.maxToolRounds
        historyWindowSpinner.value = settings.historyWindow
        thinkingCombo.selectedItem = settings.thinkingLevel.takeIf { it in THINKING_LEVELS } ?: ""
        thinkingBudgetSpinner.value = settings.thinkingBudget
        webEnabledCheck.isSelected = settings.webEnabled
        searchProviderCombo.selectedItem = Web.Provider.from(settings.searchProvider)
        searchKeyField.text = settings.searchApiKey
        searchCxField.text = settings.searchCx
        loadMemoryCheck.isSelected = settings.loadProjectMemory
        personaModel.replaceAll(settings.personas.map { Persona(it.name, it.prompt) })
        mcpModel.replaceAll(settings.mcpServers.map { McpServerConfig(it.name, it.command, it.args, it.env, it.enabled) })
        updateEnablement()
    }

    private fun modelText(): String = (modelCombo.editor.item?.toString() ?: "").trim()

    private fun thinkingLevelText(): String = (thinkingCombo.selectedItem as? String).orEmpty()

    private fun searchProvider(): Web.Provider =
        searchProviderCombo.selectedItem as? Web.Provider
            ?: Web.Provider.NONE

    private fun comboModel(items: List<String>): ComboBoxModel<String> =
        DefaultComboBoxModel(items.toTypedArray())

    private fun <T> items(model: CollectionListModel<T>): List<T> =
        (0 until model.size).map { model.getElementAt(it) }

    private fun personasEqual(a: List<Persona>, b: List<Persona>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x.name == y.name && x.prompt == y.prompt }

    private fun mcpEqual(a: List<McpServerConfig>, b: List<McpServerConfig>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.name == y.name && x.command == y.command && x.args == y.args && x.env == y.env && x.enabled == y.enabled
        }

    private companion object {
        /** "" = send no `thinkingLevel` at all; the rest are Gemini 3.x's values. */
        val THINKING_LEVELS = listOf("", "low", "medium", "high")
    }

    // ---- item editor dialogs -------------------------------------------------

    private abstract class ItemDialog<T>(dialogTitle: String) : DialogWrapper(true) {
        init { title = dialogTitle }
        abstract fun result(): T
    }

    private class PersonaDialog(existing: Persona?) : ItemDialog<Persona>("Persona") {
        private val nameField = JBTextField(existing?.name ?: "")
        private val promptArea = JBTextArea(8, 48).apply {
            lineWrap = true; wrapStyleWord = true; text = existing?.prompt ?: ""
        }

        init { init() }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("System prompt:", JScrollPane(promptArea).apply {
                preferredSize = Dimension(JBUI.scale(460), JBUI.scale(180))
            })
            .panel

        override fun result() = Persona(nameField.text.trim(), promptArea.text)
    }

    private class McpDialog(existing: McpServerConfig?) : ItemDialog<McpServerConfig>("MCP Server") {
        private val nameField = JBTextField(existing?.name ?: "")
        private val commandField = JBTextField(existing?.command ?: "")
        private val argsField = JBTextField(existing?.args ?: "")
        private val envArea = JBTextArea(4, 40).apply { text = existing?.env ?: "" }
        private val enabledCheck = JBCheckBox("Enabled", existing?.enabled ?: true)

        init { init() }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Command:", commandField)
            .addLabeledComponent("Arguments:", argsField)
            .addLabeledComponent("Env (KEY=VALUE per line):", JScrollPane(envArea).apply {
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(90))
            })
            .addComponent(enabledCheck)
            .panel

        override fun result() =
            McpServerConfig(nameField.text.trim(), commandField.text.trim(), argsField.text.trim(), envArea.text, enabledCheck.isSelected)
    }
}
