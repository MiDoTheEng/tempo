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

    companion object {
        @Volatile var instance: WaListener? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Run a clean-up of leftover WhatsApp notifications a few times over the next half minute. */
        fun sweepSoon() {
            listOf(1_500L, 4_000L, 10_000L, 30_000L).forEach { d ->
                handler.postDelayed({ instance?.sweep() }, d)
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        sweep()
        Engine.settle(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    /** Removes any WhatsApp notification still showing for someone you watch. */
    fun sweep() {
        val active = try {
            activeNotifications?.filter { it.packageName in WA_PACKAGES } ?: return
        } catch (_: Exception) {
            return
        }
        var removed = 0
        for (sbn in active) {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) continue
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val title = (style?.conversationTitle ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: continue
            val idNumber = listOfNotNull(
                if (android.os.Build.VERSION.SDK_INT >= 29) n.shortcutId else null,
                style?.messages?.lastOrNull { it.person != null }?.person?.key,
            ).firstNotNullOfOrNull { numberFromWhatsAppId(it) }
            val c = Store.resolve(this, cleanName(title), idNumber) ?: continue
            hide(sbn, c.name)
            lastHiddenAt = System.currentTimeMillis()
            removed++
            if (Engine.isSilent(c)) Notifier.cancel(this, c.name)
        }
        if (removed > 0) {
            Store.log(this, "Cleared $removed leftover WhatsApp notification(s)")
            cancelLonelySummary(null)
        }
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
        try {
            val photo = n.getLargeIcon()?.loadDrawable(this)
                ?: style?.messages?.lastOrNull { it.person?.icon != null }?.person?.icon?.loadDrawable(this)
            Avatars.saveFromWhatsApp(this, name, photo)
        } catch (_: Exception) {
        }
        // WhatsApp tags its notifications with the chat id, which holds the phone number.
        val idNumber = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= 29) n.shortcutId else null,
            style?.messages?.lastOrNull { it.person != null }?.person?.key,
            style?.messages?.lastOrNull { it.person != null }?.person?.uri,
        ).firstNotNullOfOrNull { numberFromWhatsAppId(it) }
        val contact = Store.resolve(this, name, idNumber) ?: return
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
        hide(sbn, contact.name)
        cancelLonelySummary(sbn.key)

        if (fresh.isEmpty()) return
        Store.updateContact(this, contact.name) { it.copy(lastSeenMsgTime = fresh.maxOf { f -> f.msg.time }) }

        for (f in fresh) {
            if (f.fromMe) Engine.onMySend(this, contact.name, f.msg.time, "quick reply")
            else Engine.onTheirMessage(this, contact.name, f.msg)
        }
        Engine.settle(this)
    }

    private fun stillShowing(key: String) = try {
        activeNotifications?.any { it.key == key } == true
    } catch (_: Exception) {
        false
    }

    /**
     * Removes a WhatsApp notification and checks that it's really gone. If WhatsApp keeps it on
     * screen, cancel again, and as a last resort snooze it for a minute (Tempo re-checks it then).
     */
    private fun hide(sbn: StatusBarNotification, who: String) {
        val key = sbn.key
        cancelNotification(key)
        handler.postDelayed({
            if (stillShowing(key)) {
                cancelNotification(key)
                handler.postDelayed({
                    if (stillShowing(key)) {
                        val f = sbn.notification.flags
                        try {
                            snoozeNotification(key, 60_000)
                        } catch (_: Exception) {
                        }
                        Store.log(
                            this,
                            "WhatsApp kept $who's notification on screen (flags 0x${Integer.toHexString(f)}) — snoozed it" +
                                if (stillShowing(key)) ", but it's STILL showing" else ""
                        )
                    }
                }, 800)
            }
        }, 700)
    }

    /** If the only WhatsApp notification left is the group summary, remove it too. */
    private fun cancelLonelySummary(justCancelledKey: String?) {
        val wa = try {
            activeNotifications?.filter { it.packageName in WA_PACKAGES && it.key != justCancelledKey } ?: return
        } catch (_: Exception) {
            return
        }
        val summaries = wa.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        for (sum in summaries) {
            // A summary is only "lonely" if no real message notification is left in its group.
            val group = sum.notification.group
            val children = wa.filter {
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    it.notification.group == group &&
                    it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0
            }
            if (children.isEmpty()) cancelNotification(sum.key)
        }
    }
}
