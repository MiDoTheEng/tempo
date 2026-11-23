package com.midotheeng.tempo

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Sends your saved reply when a hold ends. Tries, in order:
 *  1. WhatsApp's own "Reply" button from its notification (works in the background, even locked).
 *  2. If the phone is unlocked: opens the chat with the text typed in and taps Send.
 *  3. Otherwise: a notification "Tap to send" with the text ready in WhatsApp.
 */
object AutoReply {

    /** (name, text, deadline) while Tempo waits for the chat to open so it can tap Send. */
    @Volatile var pending: Triple<String, String, Long>? = null

    fun send(ctx: Context, name: String, text: String) {
        val now = System.currentTimeMillis()
        // "Just once": switch off after this one.
        val c = Store.find(ctx, name)
        Store.updateContact(ctx, name) { it.copy(lastAutoReplyAt = now, autoReplyOn = it.autoReplyOn && it.autoReplyRepeat) }
        if (c != null && !c.autoReplyRepeat) Store.log(ctx, "Auto-reply to $name was set to Just once — now switched off")

        Notifier.replyActions[nameKey(name)]?.let { action ->
            if (sendViaAction(ctx, action, text)) {
                done(ctx, name, "from the notification")
                return
            }
        }

        val number = Store.find(ctx, name)?.number?.takeIf { it.isNotBlank() } ?: Avatars.whatsAppNumber(ctx, name)
        if (number != null && unlocked(ctx) && accessibilityOn(ctx)) {
            pending = Triple(name, text, now + 20_000)
            try {
                ctx.startActivity(chatIntent(ctx, number, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                pending = null
            }
        }
        askToSend(ctx, name, text, number)
    }

    fun done(ctx: Context, name: String, how: String) {
        val now = System.currentTimeMillis()
        Store.updateContact(ctx, name) { it.copy(lastMySend = maxOf(it.lastMySend, now), lastAutoReplyAt = now) }
        Store.log(ctx, "Auto-replied to $name ($how) — timing their answer")
        // WhatsApp often re-posts its notification after a reply; clear it again.
        WaListener.sweepSoon()
    }

    fun askToSend(ctx: Context, name: String, text: String, number: String? = Store.find(ctx, name)?.number?.takeIf { it.isNotBlank() } ?: Avatars.whatsAppNumber(ctx, name)) {
        Notifier.ensureChannel(ctx)
        val open = if (number != null) chatIntent(ctx, number, text)
        else ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
        val pi = open?.let {
            PendingIntent.getActivity(ctx, ("ask" + nameKey(name)).hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(ctx, "tempo_messages")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Auto-reply to $name is ready")
            .setContentText("Tap to send: $text")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to send: $text"))
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(("ask" + nameKey(name)).hashCode(), n)
        } catch (_: SecurityException) {
        }
        Store.log(ctx, "Couldn't send the auto-reply to $name by itself — tap the notification to send it")
    }

    private fun sendViaAction(ctx: Context, action: NotificationCompat.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val pi = action.actionIntent ?: return false
        if (inputs.isEmpty()) return false
        return try {
            val fill = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, fill, results)
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
            pi.send(ctx, 0, fill)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun chatIntent(ctx: Context, number: String, text: String): Intent {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            ctx.packageManager.getLaunchIntentForPackage(it) != null
        } ?: "com.whatsapp"
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}"))
            .setPackage(pkg)
    }

    private fun unlocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun accessibilityOn(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.startsWith(ctx.packageName + "/") }
    }
}
