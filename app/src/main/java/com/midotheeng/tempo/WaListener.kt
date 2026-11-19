package com.midotheeng.tempo

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * Sees every WhatsApp notification. For a contact you picked, it removes WhatsApp's notification
 * and hands the messages to [Engine], which decides when Tempo shows them.
 */
class WaListener : NotificationListenerService() {

    private var lastHiddenAt = 0L

    override fun onListenerConnected() {
        Engine.settle(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WA_PACKAGES) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Store.log(this, "Error reading a WhatsApp notification: ${e.javaClass.simpleName}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            // Only tidy up the summary if we just hid a watched contact's notification.
            if (System.currentTimeMillis() - lastHiddenAt < 5_000) cancelLonelySummary(null)
            return
        }
        val extras = n.extras
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style?.isGroupConversation == true) return

        val rawTitle = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: return
        val name = cleanName(rawTitle)
        if (name.isBlank() || name.equals("WhatsApp", ignoreCase = true) || name.equals("WhatsApp Business", ignoreCase = true)) return

        Store.addRecent(this, name)
        val contact = Store.find(this, name) ?: return
        val key = nameKey(contact.name)

        // Keep WhatsApp's own tap and reply actions so Tempo's notification can reuse them.
        n.contentIntent?.let { Notifier.openIntents[key] = it }
        Notifier.waPackage[key] = sbn.packageName
        for (i in 0 until NotificationCompat.getActionCount(n)) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            if (!a.remoteInputs.isNullOrEmpty()) {
                Notifier.replyActions[key] = a
                break
            }
        }

        // Pull out the messages, oldest first.
        data class In(val msg: Msg, val fromMe: Boolean)
        val all: List<In> = if (style != null && style.messages.isNotEmpty()) {
            val myName = style.user.name?.toString()
            style.messages.map { m ->
                val sender = m.person
                val fromMe = sender == null || (myName != null && sender.name?.toString() == myName)
                In(Msg(m.text?.toString() ?: "", m.timestamp), fromMe)
            }
        } else {
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            listOf(In(Msg(text.toString(), n.`when`.takeIf { it > 0 } ?: sbn.postTime), false))
        }

        val fresh = all.filter { it.msg.time > contact.lastSeenMsgTime }.sortedBy { it.msg.time }

        // Hide WhatsApp's notification for this contact.
        lastHiddenAt = System.currentTimeMillis()
        cancelNotification(sbn.key)
        cancelLonelySummary(sbn.key)

        if (fresh.isEmpty()) return
        Store.updateContact(this, contact.name) { it.copy(lastSeenMsgTime = fresh.maxOf { f -> f.msg.time }) }

        for (f in fresh) {
            if (f.fromMe) Engine.onMySend(this, contact.name, f.msg.time, "quick reply")
            else Engine.onTheirMessage(this, contact.name, f.msg)
        }
        Engine.settle(this)
    }

    /** If the only WhatsApp notification left is the group summary, remove it too. */
    private fun cancelLonelySummary(justCancelledKey: String?) {
        val wa = try {
            activeNotifications?.filter { it.packageName in WA_PACKAGES && it.key != justCancelledKey } ?: return
        } catch (_: Exception) {
            return
        }
        val children = wa.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        if (children.isEmpty()) {
            wa.forEach { cancelNotification(it.key) }
        }
    }
}
