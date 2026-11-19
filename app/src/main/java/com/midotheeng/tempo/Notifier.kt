package com.midotheeng.tempo

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

object Notifier {
    private const val CHANNEL = "tempo_messages"

    /** WhatsApp's own "open this chat" and "reply" actions, captured from its notification. */
    val openIntents = ConcurrentHashMap<String, PendingIntent>()
    val replyActions = ConcurrentHashMap<String, NotificationCompat.Action>()
    val waPackage = ConcurrentHashMap<String, String>()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Delayed WhatsApp messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages Tempo held back and is now showing"
                }
            )
        }
    }

    private fun id(name: String) = 1000 + (nameKey(name).hashCode() and 0x0FFFFFFF)

    fun post(ctx: Context, c: Contact) {
        if (c.shown.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val key = nameKey(c.name)

        val me = Person.Builder().setName("You").build()
        val them = Person.Builder().setName(c.name).build()
        val style = NotificationCompat.MessagingStyle(me)
        c.shown.forEach { style.addMessage(it.text, it.time, them) }

        val open = openIntents[key] ?: run {
            val pkg = waPackage[key] ?: "com.whatsapp"
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
            launch?.let {
                PendingIntent.getActivity(ctx, id(c.name), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }

        val dismiss = PendingIntent.getBroadcast(
            ctx, id(c.name),
            Intent(ctx, TempoReceiver::class.java).setAction(TempoReceiver.ACTION_DISMISSED).putExtra("name", c.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(c.shown.last().time)
            .setShowWhen(true)
            .setDeleteIntent(dismiss)
        open?.let { b.setContentIntent(it) }
        replyActions[key]?.let { b.addAction(it) }

        try {
            NotificationManagerCompat.from(ctx).notify(id(c.name), b.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context, name: String) {
        NotificationManagerCompat.from(ctx).cancel(id(name))
    }
}

object Alarms {
    fun scheduleNext(ctx: Context) {
        val next = Store.get(ctx).contacts.filter { it.held.isNotEmpty() }.minOfOrNull { it.releaseAt }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, TempoReceiver::class.java).setAction(TempoReceiver.ACTION_RELEASE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (next == null) {
            am.cancel(pi)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }
}

class TempoReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RELEASE = "com.midotheeng.tempo.RELEASE"
        const val ACTION_DISMISSED = "com.midotheeng.tempo.DISMISSED"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RELEASE -> Engine.settle(ctx)
            ACTION_DISMISSED -> intent.getStringExtra("name")?.let { Engine.clearShown(ctx, it) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Engine.settle(ctx)
    }
}
