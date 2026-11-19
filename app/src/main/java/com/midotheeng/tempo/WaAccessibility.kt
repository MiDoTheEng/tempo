package com.midotheeng.tempo

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches WhatsApp's screen (WhatsApp only) for three things in a chat with a picked contact:
 *  1. you tapping Send  -> starts the reply timer
 *  2. you opening the chat -> held messages are dropped (you've seen them)
 *  3. a new incoming message at the bottom while the chat is open -> timer cleared
 *
 * It relies on WhatsApp's internal view ids, which a WhatsApp update can rename.
 */
class WaAccessibility : AccessibilityService() {

    private var lastScan = 0L
    private var lastOpenChat: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WA_PACKAGES) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event, pkg)
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scan(pkg, force = true)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scan(pkg, force = false)
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    private fun onClick(event: AccessibilityEvent, pkg: String) {
        val src = event.source
        val id = src?.viewIdResourceName ?: ""
        val desc = (event.contentDescription ?: src?.contentDescription)?.toString() ?: ""
        val isSend = id.endsWith(":id/send") || desc.equals("Send", ignoreCase = true) || desc == "إرسال"
        if (!isSend) return
        val chat = currentChatName(pkg) ?: return
        Engine.onMySend(this, chat, System.currentTimeMillis(), "in WhatsApp")
    }

    private fun scan(pkg: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 1_000) return
        lastScan = now

        val chat = currentChatName(pkg)
        if (chat == null) {
            lastOpenChat = null
            return
        }
        if (lastOpenChat == null || nameKey(lastOpenChat!!) != nameKey(chat)) {
            // Any chat you open becomes a one-tap suggestion in Tempo's "Add a contact".
            Store.addRecent(this, chat)
        }
        val contact = Store.find(this, chat)
        if (contact == null) {
            lastOpenChat = chat
            return
        }

        if (lastOpenChat == null || nameKey(lastOpenChat!!) != nameKey(chat)) {
            lastOpenChat = chat
            Engine.onChatOpened(this, contact.name)
        }

        if (contact.lastMySend > 0 && bottomMessageIsIncoming(pkg)) {
            Engine.onReplySeenInChat(this, contact.name)
        }
    }

    /** Name in the header of an open 1-to-1 chat, or null if no chat is open. */
    private fun currentChatName(pkg: String): String? {
        val root = rootInActiveWindow ?: return null
        val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name").firstOrNull()
            ?: return null
        val text = node.text?.toString() ?: return null
        return cleanName(text).takeIf { it.isNotBlank() }
    }

    /**
     * Outgoing messages carry a tick icon (view id "status"); incoming ones don't.
     * Looks at the last message on screen.
     */
    private fun bottomMessageIsIncoming(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val texts = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")
        val last = texts.lastOrNull() ?: return false
        var row: AccessibilityNodeInfo? = last
        repeat(3) {
            val parent = row?.parent ?: return@repeat
            row = parent
        }
        val r = row ?: return false
        val ticks = r.findAccessibilityNodeInfosByViewId("$pkg:id/status")
        return ticks.isEmpty()
    }
}
