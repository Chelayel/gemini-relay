package com.chelayel.geminirelay.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.EditorNotificationPanel

/**
 * Gemini Relay is deprecated: AI Relay does everything it did — the same three
 * connection modes, Apigee included — and also serves that Gemini to GitHub Copilot.
 * Shown above the chat so nobody misses it, while the panel keeps working for anyone
 * not ready to move.
 */
object DeprecationBanner {
    const val AI_RELAY_URL = "https://plugins.jetbrains.com/plugin/34342-ai-relay"
    const val AIR_URL = "https://www.jetbrains.com/help/air/quick-start-with-air.html"

    fun create(): EditorNotificationPanel =
        EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text = "Gemini Relay is deprecated and will get no further updates. " +
                "AI Relay takes the same Apigee, Vertex or API-key setup and adds it to GitHub Copilot."
            createActionLabel("Get AI Relay") { BrowserUtil.browse(AI_RELAY_URL) }
            createActionLabel("JetBrains Air") { BrowserUtil.browse(AIR_URL) }
        }
}
