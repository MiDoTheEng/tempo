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
        /** WhatsApp chat id (notification tag) -> watched contact name. */
        val chatByTag = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /** Run a clean-up of leftover WhatsApp notifications a few times over the next half minute. */
        fun sweepSoon() {
            listOf(1_500L, 4_000L, 10_000L, 30_000L).forEach { d ->
                handler.postDelayed({ instance?.sweep() }, d)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (Store.get(this@WaListener).contacts.any { Engine.isSilent(it) }) sweep()
            handler.postDelayed(this, 10_000)
        }
    }

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            AutoReply.onUnlocked(this@WaListener)
        }
    }
    private var unlockRegistered = false

    override fun onListenerConnected() {
        instance = this
        if (!unlockRegistered) {
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    this, unlockReceiver,
                    android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT),
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                )
                unlockRegistered = true
            } catch (_: Exception) {
            }
        }
        Store.debug(this, "listener connected")
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 10_000)
        sweep()
        Engine.settle(this)
    }

    override fun onDestroy() {
        if (unlockRegistered) try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        unlockRegistered = false
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        Store.log(this, "Notification access disconnected — Tempo can't see WhatsApp until Android reconnects it")
        handler.removeCallbacks(ticker)
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
        Store.debug(this, "sweep: ${active.size} WhatsApp notification(s) showing")
        for (sbn in active) {
            Store.debug(this, "  sweep sees ${describe(sbn)}")
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) continue
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val c = whoIs(sbn, style).first ?: continue
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


    /** Which watched contact a WhatsApp notification belongs to (or null), plus the chat name. */
    private fun whoIs(sbn: StatusBarNotification, style: NotificationCompat.MessagingStyle?): Pair<Contact?, String?> {
        val n = sbn.notification
        // 1. Same WhatsApp chat id (tag) we've already matched before.
        sbn.tag?.let { tag ->
            chatByTag[tag]?.let { known -> Store.find(this, known)?.let { return it to it.name } }
            Store.get(this).contacts.firstOrNull { tag in it.waTags }?.let { return it to it.name }
        }

        // 2. Chat name — skipping "You", which WhatsApp shows when your reply is the last message.
        val me = style?.user?.name?.toString()?.trim()
        fun isMe(x: String?) = x != null && (x.trim().equals("You", true) || x.trim() == "أنت" || x.trim() == me)
        val name = listOfNotNull(
            style?.conversationTitle?.toString(),
            style?.messages?.reversed()?.mapNotNull { it.person?.name?.toString() }?.firstOrNull { !isMe(it) },
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        ).map { cleanName(it) }.firstOrNull { it.isNotBlank() && !isMe(it) && !it.equals("WhatsApp", true) && !it.equals("WhatsApp Business", true) }

        val idNumber = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= 29) n.shortcutId else null,
            style?.messages?.lastOrNull { it.person != null && !isMe(it.person?.name?.toString()) }?.person?.key,
            style?.messages?.lastOrNull { it.person != null && !isMe(it.person?.name?.toString()) }?.person?.uri,
        ).firstNotNullOfOrNull { numberFromWhatsAppId(it) }

        var c = if (name != null) Store.resolve(this, name, idNumber) else null
        if (c == null && idNumber != null) c = Store.get(this).contacts.firstOrNull { sameNumber(it.number, idNumber) }
        // 3. Only your own message in it and nothing else to go on: the contact Tempo just auto-replied to.
        if (c == null && name == null) {
            val now = System.currentTimeMillis()
            c = Store.get(this).contacts.filter { now - it.lastAutoReplyAt < 3 * 60_000 }.singleOrNull()
        }
        if (c != null) sbn.tag?.takeIf { it.isNotBlank() }?.let { tag ->
            chatByTag[tag] = c.name
            if (tag !in c.waTags) Store.updateContact(this, c.name) { it.copy(waTags = (it.waTags + tag).takeLast(5)) }
        }
        return c to name
    }

    private fun describe(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val t = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        val f = n.flags
        return "\"$t\" id=${sbn.id} tag=${sbn.tag} flags=0x${Integer.toHexString(f)}" +
            (if (f and Notification.FLAG_GROUP_SUMMARY != 0) " SUMMARY" else "") +
            (if (f and Notification.FLAG_ONGOING_EVENT != 0) " ONGOING" else "") +
            (if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) " GROUPCHAT" else "")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in WA_PACKAGES) Store.debug(this, "WA removed: ${describe(sbn)}")
    }

    private fun handle(sbn: StatusBarNotification) {
        Store.debug(this, "WA posted: ${describe(sbn)}")
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

        val (found, chatName) = whoIs(sbn, style)
        val name = chatName ?: found?.name
        if (name != null) {
            Store.addRecent(this, name)
            try {
                val photo = n.getLargeIcon()?.loadDrawable(this)
                    ?: style?.messages?.lastOrNull { it.person?.icon != null }?.person?.icon?.loadDrawable(this)
                if (found == null || chatName != null) Avatars.saveFromWhatsApp(this, name, photo)
            } catch (_: Exception) {
            }
        }
        // Keep the Reply button for any 1-to-1 chat, so scheduled messages can go out in the background.
        if (name != null) {
            for (i in 0 until NotificationCompat.getActionCount(n)) {
                val a = NotificationCompat.getAction(n, i) ?: continue
                if (!a.remoteInputs.isNullOrEmpty()) {
                    Notifier.replyActions[nameKey(name)] = a
                    break
                }
            }
        }
        val contact = found
        if (contact == null) {
            Store.debug(this, "  not watched: \"${name ?: "?"}\"")
            return
        }
        Store.debug(this, "  belongs to ${contact.name}")
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
                val senderName = sender?.name?.toString()?.trim()
                val text = m.text?.toString()?.trim() ?: ""
                val user = style.user
                val fromMe = sender == null ||
                    (myName != null && senderName == myName) ||
                    (user.key != null && sender.key == user.key) ||
                    senderName.equals("You", ignoreCase = true) || senderName == "أنت" ||
                    // Tempo's own auto-reply showing up in WhatsApp's re-posted notification
                    (contact.autoReply.isNotBlank() && text == contact.autoReply.trim() &&
                        kotlin.math.abs(m.timestamp - contact.lastAutoReplyAt) < 5 * 60_000) ||
                    // anything stamped right after Tempo's auto-reply that matches its text
                    (senderName != null && senderName != cleanName(contact.name) && senderName != cleanName(chatName ?: contact.name) &&
                        contact.lastAutoReplyAt > 0 && kotlin.math.abs(m.timestamp - contact.lastAutoReplyAt) < 15_000)
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
        Store.debug(this, "  hid $who's WhatsApp notification")
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
