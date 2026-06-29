package com.charbel.geminirelay.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
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
        renderer = SimpleListCellRenderer.create("") { "${it.label} — ${it.blurb}" }
    }
    private val modelCombo = JComboBox<String>(comboModel(GeminiSettings.MODEL_CHOICES)).apply { isEditable = true }

    private val apiKeyField = JBPasswordField()

    private val projectField = JBTextField()
    private val locationField = JBTextField()
    private val endpointField = JBTextField()
    private val gcloudField = JBTextField()

    private val tokenUrlField = JBTextField()
    private val clientIdField = JBTextField()
    private val clientSecretField = JBPasswordField()
    private val apigeeAgentsArea = JBTextArea(4, 40)

    private val systemPromptArea = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val maxIterationsSpinner = JSpinner(SpinnerNumberModel(15, 1, 100, 1))
    private val commandTimeoutSpinner = JSpinner(SpinnerNumberModel(60, 5, 600, 1))

    private val loadMemoryCheck = JBCheckBox("Load project memory (GEMINI.md / AGENTS.md / CLAUDE.md) as context")

    private val personaModel = CollectionListModel<Persona>()
    private val personaList = JBList(personaModel).apply {
        cellRenderer = SimpleListCellRenderer.create("") { it.name.ifBlank { "(unnamed)" } }
    }
    private val mcpModel = CollectionListModel<McpServerConfig>()
    private val mcpList = JBList(mcpModel).apply {
        cellRenderer = SimpleListCellRenderer.create("") {
            (if (it.enabled) it.name else "${it.name} (disabled)") + "  —  ${it.command}"
        }
    }

    override fun getDisplayName(): String = "Gemini Relay"

    override fun createComponent(): JComponent {
        modeCombo.addActionListener { updateEnablement() }

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
            .addLabeledComponent("Accessible agents:", JScrollPane(apigeeAgentsArea).apply {
                preferredSize = Dimension(JBUI.scale(360), JBUI.scale(80))
            })
            .addComponent(hint("Apigee mode only — one agent (model id) per line. These become the model picker's choices."))
            .addSeparator()
            .addComponent(sectionLabel("Agent"))
            .addLabeledComponent("System prompt:", promptScroll)
            .addLabeledComponent("Max steps per turn:", maxIterationsSpinner)
            .addLabeledComponent("Command timeout (s):", commandTimeoutSpinner)
            .addComponent(loadMemoryCheck)
            .addSeparator()
            .addComponent(sectionLabel("Personas (agents)"))
            .addComponent(hint("Named system-prompt presets — pick one from the composer's “+” menu to run a turn as that agent."))
            .addComponent(listPanel(personaList, { editDialog(PersonaDialog(null)) }, { editDialog(PersonaDialog(it)) }))
            .addSeparator()
            .addComponent(sectionLabel("MCP tool servers"))
            .addComponent(hint("External Model Context Protocol servers (stdio). Their tools are added to Agent mode."))
            .addComponent(listPanel(mcpList, { editDialog(McpDialog(null)) }, { editDialog(McpDialog(it)) }))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return form
    }

    /** A list with an add/edit/remove toolbar. */
    private fun <T> listPanel(list: JBList<T>, onAdd: () -> T?, onEdit: (T) -> T?): JComponent {
        @Suppress("UNCHECKED_CAST")
        val model = list.model as CollectionListModel<T>
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { onAdd()?.let { model.add(it); list.selectedIndex = model.size - 1 } }
            .setEditAction {
                val idx = list.selectedIndex
                if (idx >= 0) onEdit(model.getElementAt(idx))?.let { model.setElementAt(it, idx) }
            }
            .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let { model.remove(it) } }
            .createPanel()
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(110))
            add(decorated, BorderLayout.CENTER)
        }
    }

    private fun <T> editDialog(dialog: ItemDialog<T>): T? =
        if (dialog.showAndGet()) dialog.result() else null

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
            (maxIterationsSpinner.value as Int) != settings.maxIterations ||
            (commandTimeoutSpinner.value as Int) != settings.commandTimeoutSeconds ||
            loadMemoryCheck.isSelected != settings.loadProjectMemory ||
            !personasEqual(items(personaModel), settings.personas) ||
            !mcpEqual(items(mcpModel), settings.mcpServers)

    override fun apply() {
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
        settings.maxIterations = maxIterationsSpinner.value as Int
        settings.commandTimeoutSeconds = commandTimeoutSpinner.value as Int
        settings.loadProjectMemory = loadMemoryCheck.isSelected
        settings.personas.apply { clear(); addAll(items(personaModel)) }
        settings.mcpServers.apply { clear(); addAll(items(mcpModel)) }
        // A credential change may invalidate a cached bearer token.
        com.charbel.geminirelay.api.AuthProvider.invalidate()
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
        maxIterationsSpinner.value = settings.maxIterations
        commandTimeoutSpinner.value = settings.commandTimeoutSeconds
        loadMemoryCheck.isSelected = settings.loadProjectMemory
        personaModel.replaceAll(settings.personas.map { Persona(it.name, it.prompt) })
        mcpModel.replaceAll(settings.mcpServers.map { McpServerConfig(it.name, it.command, it.args, it.env, it.enabled) })
        updateEnablement()
    }

    private fun modelText(): String = (modelCombo.editor.item?.toString() ?: "").trim()

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
