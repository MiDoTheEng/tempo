package com.midotheeng.tempo

import android.content.Context

/**
 * The rules.
 *
 * Match mode: when you send them a message, Tempo notes the time. Their FIRST message after that
 * starts the hold. wait = (their first reply) - (your last message). Everything they send until
 * (first reply + wait) is held and shown together at that moment. If they message you without you
 * having written first, it shows straight away.
 *
 * Fixed mode: the first message of a run starts a hold of N minutes; everything in that window is
 * shown together at the end.
 *
 * Opening their chat in WhatsApp, or sending them a message, drops anything held (you've seen it).
 */
private data class Due(
    val name: String,
    val timedRelease: Boolean,
    val autoReplyOn: Boolean,
    val autoReply: String,
    val contact: Contact,
)

object Engine {

    fun onTheirMessage(ctx: Context, name: String, m: Msg) {
        val now = System.currentTimeMillis()
        var logLine: String? = null
        var quiet = false
        Store.updateContact(ctx, name) { c ->
            when {
                c.mode == MODE_MIRROR && c.lastMySend > 0 && m.time < c.lastMySend -> {
                    // Written before your (auto-)reply went out — part of the same burst, not an answer.
                    quiet = true
                    c.copy(shown = (c.shown + m).takeLast(25))
                }
                c.mode == MODE_MIRROR && c.lastMySend > 0 -> {
                    val wait = (m.time - c.lastMySend).coerceAtLeast(0)
                    val delay = wait.coerceAtMost(c.capMinutes * 60_000L)
                    val at = m.time + delay
                    logLine = "${c.name} replied after ${fmtDuration(wait)} — holding until ${fmtTime(at)}"
                    c.copy(lastMySend = 0, lastWaitMs = wait, releaseAt = at, held = c.held + m)
                }
                c.held.isNotEmpty() && c.releaseAt > now ->
                    c.copy(held = c.held + m)
                c.mode == MODE_FIXED -> {
                    val at = m.time + c.fixedMinutes * 60_000L
                    logLine = "${c.name} messaged — holding until ${fmtTime(at)}"
                    c.copy(releaseAt = at, held = c.held + m)
                }
                c.mode == MODE_MIRROR && c.lastMySend == 0L && c.held.isEmpty() -> {
                    // They messaged you first: show now, and auto-reply now.
                    logLine = "${c.name} messaged first — showing now"
                    c.copy(releaseAt = 0, held = c.held + m, wentFirst = true)
                }
                else ->
                    // More messages after the held window ended: show now.
                    c.copy(releaseAt = 0, held = c.held + m)
            }
        }
        logLine?.let { Store.log(ctx, it) }
        if (quiet) {
            val c = Store.find(ctx, name) ?: return
            if (isSilent(c)) Store.updateContact(ctx, name) { it.copy(shown = emptyList()) }
            else Notifier.post(ctx, c, alert = false)
        }
    }

    /** Auto-reply with Silent on: never show anything for this person. */
    fun isSilent(c: Contact) = c.autoReplyOn && c.autoReplySilent && c.autoReply.isNotBlank()

    fun onMySend(ctx: Context, name: String, time: Long, source: String) {
        val c = Store.find(ctx, name) ?: return
        if (time <= c.lastMySend) return
        // Tempo's own auto-reply showing up in WhatsApp: restart the timer, but you haven't read anything.
        if (c.lastAutoReplyAt > 0 && kotlin.math.abs(time - c.lastAutoReplyAt) < 60_000) {
            Store.updateContact(ctx, name) { it.copy(lastMySend = maxOf(it.lastMySend, time)) }
            return
        }
        val dropped = c.held.size
        Store.updateContact(ctx, name) {
            it.copy(lastMySend = time, held = emptyList(), shown = emptyList(), releaseAt = 0)
        }
        Notifier.cancel(ctx, c.name)
        // Avoid flooding the log when you send several messages in a row.
        if (c.lastMySend == 0L || dropped > 0) {
            Store.log(ctx, "You messaged ${c.name} ($source) — timer started" +
                if (dropped > 0) ", $dropped held message(s) dropped" else "")
        }
        settle(ctx)
    }

    /** You opened their chat in WhatsApp: whatever was held, you've now seen. */
    fun onChatOpened(ctx: Context, name: String) {
        val c = Store.find(ctx, name) ?: return
        if (c.held.isEmpty() && c.shown.isEmpty()) return
        val dropped = c.held.size
        Store.updateContact(ctx, name) { it.copy(held = emptyList(), shown = emptyList(), releaseAt = 0) }
        Notifier.cancel(ctx, c.name)
        if (dropped > 0) Store.log(ctx, "You opened ${c.name}'s chat — $dropped held message(s) dropped")
        settle(ctx)
    }

    /** They replied while you had the chat open, so there is nothing left to time. */
    fun onReplySeenInChat(ctx: Context, name: String) {
        val c = Store.find(ctx, name) ?: return
        if (c.lastMySend == 0L) return
        if (System.currentTimeMillis() - c.lastMySend < 4_000) return
        Store.updateContact(ctx, name) { it.copy(lastMySend = 0) }
        Store.log(ctx, "${c.name} replied while the chat was open — timer cleared")
    }

    fun releaseNow(ctx: Context, name: String) {
        Store.updateContact(ctx, name) { it.copy(releaseAt = 0, wentFirst = false) }
        settle(ctx)
    }

    fun clearShown(ctx: Context, name: String) {
        Store.updateContact(ctx, name) { it.copy(shown = emptyList()) }
    }

    /** Shows everything that is due and sets the alarm for the next one. */
    fun settle(ctx: Context) {
        val now = System.currentTimeMillis()
        val due = mutableListOf<Due>()
        Store.update(ctx) { st ->
            st.copy(contacts = st.contacts.map { c ->
                if (c.held.isNotEmpty() && c.releaseAt <= now) {
                    val n = c.copy(shown = (c.shown + c.held).takeLast(25), held = emptyList(), wentFirst = false)
                    due += Due(n.name, c.releaseAt > 0 || c.wentFirst, n.autoReplyOn, n.autoReply, n)
                    n
                } else c
            })
        }
        val replying = due.filter {
            it.autoReplyOn && it.autoReply.isNotBlank() &&
                (it.timedRelease || it.contact.autoReplyRepeat) &&
                // only guards against the same release firing twice
                now - it.contact.lastAutoReplyAt > 10_000
        }
        val silent = due.filter { isSilent(it.contact) }.map { nameKey(it.name) }.toSet()
        due.forEach { d ->
            if (nameKey(d.name) in silent) {
                Store.updateContact(ctx, d.name) { it.copy(shown = emptyList()) }
                Store.log(ctx, "${d.contact.shown.size} message(s) from ${d.name} — silent, no notification")
            } else {
                Notifier.post(ctx, d.contact)
            }
        }
        replying.forEach { AutoReply.send(ctx, it.name, it.autoReply) }
        Alarms.scheduleNext(ctx)
    }
}
