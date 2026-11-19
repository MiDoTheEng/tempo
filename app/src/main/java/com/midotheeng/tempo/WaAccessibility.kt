package com.midotheeng.tempo

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlin.math.abs

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
    private var capturing = false
    private val handler = Handler(Looper.getMainLooper())

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

        // Tempo opened this chat just to grab the profile photo.
        val pend = Avatars.pendingCapture
        if (pend != null && !capturing && Build.VERSION.SDK_INT >= 30) {
            if (now > pend.second) {
                Avatars.pendingCapture = null
            } else {
                capturing = true
                handler.postDelayed({ capturePhoto(pkg, pend.first) }, 900)
            }
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

    @RequiresApi(30)
    private fun capturePhoto(pkg: String, name: String) {
        val rect = Rect()
        val node = findPhotoNode(pkg)
        node?.getBoundsInScreen(rect)
        if (node == null || rect.width() < 24 || rect.height() < 24) {
            finishCapture(false)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var ok = false
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (soft != null) {
                            val l = rect.left.coerceIn(0, soft.width - 1)
                            val t = rect.top.coerceIn(0, soft.height - 1)
                            val side = minOf(rect.width(), rect.height(), soft.width - l, soft.height - t)
                            if (side > 10) {
                                Avatars.saveBitmap(this@WaAccessibility, name, Bitmap.createBitmap(soft, l, t, side, side))
                                ok = true
                            }
                        }
                    } catch (_: Exception) {
                    }
                    finishCapture(ok)
                }

                override fun onFailure(errorCode: Int) = finishCapture(false)
            })
        } catch (_: Exception) {
            finishCapture(false)
        }
    }

    private fun finishCapture(ok: Boolean) {
        val name = Avatars.pendingCapture?.first
        Avatars.pendingCapture = null
        capturing = false
        if (name != null && !ok) Store.log(this, "Couldn't grab $name's WhatsApp photo")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {
        }
    }

    /** The round profile photo in an open chat's header. */
    private fun findPhotoNode(pkg: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_photo").firstOrNull()?.let { return it }

        // Fallback: the biggest square image to the left of the name in the header.
        val nameNode = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name").firstOrNull() ?: return null
        val nameRect = Rect().also { nameNode.getBoundsInScreen(it) }
        var header: AccessibilityNodeInfo = nameNode
        repeat(4) { header.parent?.let { header = it } }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(header) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            val cls = n.className?.toString() ?: continue
            if (!cls.contains("Image")) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            val w = r.width()
            val h = r.height()
            if (w < 40 || abs(w - h) > w / 4) continue
            if (r.right > nameRect.left + 8) continue
            if (r.bottom < nameRect.top - h || r.top > nameRect.bottom + h) continue
            if (w * h > bestArea) {
                bestArea = w * h
                best = n
            }
        }
        return best
    }
}
