package com.charbel.geminirelay.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
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

    private val systemPromptArea = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val maxIterationsSpinner = JSpinner(SpinnerNumberModel(15, 1, 100, 1))
    private val commandTimeoutSpinner = JSpinner(SpinnerNumberModel(60, 5, 600, 1))

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
            .addSeparator()
            .addComponent(sectionLabel("Agent"))
            .addLabeledComponent("System prompt:", promptScroll)
            .addLabeledComponent("Max steps per turn:", maxIterationsSpinner)
            .addLabeledComponent("Command timeout (s):", commandTimeoutSpinner)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return form
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
            systemPromptArea.text != settings.systemPrompt ||
            (maxIterationsSpinner.value as Int) != settings.maxIterations ||
            (commandTimeoutSpinner.value as Int) != settings.commandTimeoutSeconds

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
        settings.systemPrompt = systemPromptArea.text
        settings.maxIterations = maxIterationsSpinner.value as Int
        settings.commandTimeoutSeconds = commandTimeoutSpinner.value as Int
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
        systemPromptArea.text = settings.systemPrompt
        systemPromptArea.caretPosition = 0
        maxIterationsSpinner.value = settings.maxIterations
        commandTimeoutSpinner.value = settings.commandTimeoutSeconds
        updateEnablement()
    }

    private fun modelText(): String = (modelCombo.editor.item?.toString() ?: "").trim()

    private fun comboModel(items: List<String>): ComboBoxModel<String> =
        DefaultComboBoxModel(items.toTypedArray())
}
