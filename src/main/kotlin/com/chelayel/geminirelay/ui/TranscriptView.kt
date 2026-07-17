package com.chelayel.geminirelay.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.Timer

/**
 * Theme-aware HTML transcript used when JCEF isn't available. Messages are
 * appended as styled blocks; a single [JEditorPane] handles wrapping,
 * scrolling and text selection.
 */
class TranscriptView : ChatView {

    private val pane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        // HiDPI-aware kit so CSS px sizes render at the correct scale (the plain
        // HTMLEditorKit inflates them against 96 DPI) while keeping the stylesheet
        // — bubbles, spacing, colors — intact.
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder().build()
        border = JBUI.Borders.empty(4, 8)
        background = UIUtil.getTextFieldBackground()
    }

    val scrollPane = JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
    }

    override val component: JComponent get() = scrollPane

    private val body = StringBuilder()
    private var streaming = false

    override fun clear() {
        streamFlush.stop()
        body.setLength(0)
        streaming = false
        streamRaw.setLength(0)
        render()
    }

    override fun addUser(text: String) = block("user", "You", MdLite.render(text))

    override fun assistantChunk(text: String) {
        // The fallback view can't easily mutate the last block, so coalesce a
        // streamed reply into a single growing assistant block.
        if (!streaming) {
            body.append("<div class='msg assistant'><div class='role'>Gemini</div><div class='content'>")
            streaming = true
            streamRaw.setLength(0)
        }
        streamRaw.append(text)
        // Re-rendering the whole document per token floods the EDT on long
        // replies; coalesce to ~25fps via a one-shot timer instead.
        if (!streamFlush.isRunning) streamFlush.restart()
    }

    private val streamRaw = StringBuilder()
    private val streamFlush = Timer(STREAM_FLUSH_MS) { if (streaming) renderStreaming() }
        .apply { isRepeats = false }

    override fun endAssistant() {
        if (streaming) {
            streamFlush.stop()
            // Persist the streamed text into the transcript body before closing
            // the block, so the finished reply stays visible.
            body.append(MdLite.render(streamRaw.toString())).append("</div></div>")
            streaming = false
            streamRaw.setLength(0)
            render()
        }
    }

    override fun setBusy(busy: Boolean) {}

    override fun addThinking(text: String) {
        endAssistant()
        block("thinking", "Thinking", MdLite.render(text))
    }

    override fun addToolUse(name: String, summary: String) {
        endAssistant()
        val detail = if (summary.isBlank()) "" else " <span class='dim'>" + MdLite.escape(summary) + "</span>"
        block("tool", "&#128295; $name", detail, withRole = false)
    }

    override fun addToolResult(text: String, isError: Boolean) {
        val cls = if (isError) "toolresult error" else "toolresult"
        val shown = text.take(2000).let { if (text.length > 2000) "$it\n… (truncated)" else it }
        block(cls, "", "<pre class='code'>" + MdLite.escape(shown) + "</pre>", withRole = false)
    }

    override fun addSystem(text: String) =
        block("system", "", "<span class='dim'>" + MdLite.escape(text) + "</span>", withRole = false)

    override fun addError(text: String) {
        endAssistant()
        block("error", "Error", MdLite.escape(text))
    }

    private fun block(cls: String, role: String, html: String, withRole: Boolean = true) {
        body.append("<div class='msg $cls'>")
        if (withRole && role.isNotEmpty()) body.append("<div class='role'>$role</div>")
        body.append("<div class='content'>").append(html).append("</div></div>")
        render()
    }

    private fun renderStreaming() {
        pane.text = document(extra = MdLite.render(streamRaw.toString()) + "</div></div>")
        pane.caretPosition = pane.document.length
    }

    private fun render() {
        pane.text = document()
        pane.caretPosition = pane.document.length
    }

    private fun document(extra: String = ""): String {
        val link = ColorUtil.toHex(UIUtil.getLabelForeground())
        val dim = ColorUtil.toHex(muted())
        val codeBg = ColorUtil.toHex(codeBackground())
        val accent = ColorUtil.toHex(ACCENT)
        val userBg = ColorUtil.toHex(blend(UIUtil.getTextFieldBackground(), UIUtil.getLabelForeground(), 0.06f))
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        return """
            <html><head><style>
              body { font-family: '${UIUtil.getLabelFont().family}'; font-size: ${UIUtil.getLabelFont().size}px;
                     color: $link; margin: 0; }
              .msg { margin: 0 0 10px 0; padding: 2px 0; }
              .role { font-weight: bold; margin-bottom: 2px; }
              .msg.user .role { color: $accent; }
              .msg.user .content { background: #$userBg; padding: 4px 6px; }
              .msg.assistant .role { color: $link; }
              .msg.thinking { color: $dim; font-style: italic; }
              .msg.tool .content { color: $dim; font-family: '$editorFont'; }
              .msg.error .role, .msg.toolresult.error { color: #C75450; }
              .dim { color: $dim; }
              pre.code { background: #$codeBg; padding: 6px 8px; margin: 4px 0;
                         font-family: '$editorFont'; white-space: pre-wrap; word-wrap: break-word; }
              code { background: #$codeBg; font-family: '$editorFont'; padding: 0 2px; }
              .msg.toolresult .content { color: $dim; }
            </style></head><body>$body$extra</body></html>
        """.trimIndent()
    }

    private fun muted(): Color = blend(UIUtil.getLabelForeground(), UIUtil.getTextFieldBackground(), 0.4f)

    private fun codeBackground(): Color =
        blend(UIUtil.getTextFieldBackground(), UIUtil.getLabelForeground(), if (isDark()) 0.10f else 0.06f)

    private fun isDark(): Boolean = ColorUtil.isDark(UIUtil.getPanelBackground())

    private fun blend(a: Color, b: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        return Color(
            (a.red * (1 - r) + b.red * r).toInt(),
            (a.green * (1 - r) + b.green * r).toInt(),
            (a.blue * (1 - r) + b.blue * r).toInt(),
        )
    }

    companion object {
        // Coalesce streamed tokens to ~25fps so long replies don't flood the EDT.
        private const val STREAM_FLUSH_MS = 40

        private val ACCENT = Color(0x42, 0x85, 0xF4)
    }
}
