package com.midotheeng.tempo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val MODE_MIRROR = "mirror"
const val MODE_FIXED = "fixed"

data class Msg(val text: String, val time: Long)

data class Contact(
    val name: String,
    /** WhatsApp phone number, digits only — used to recognise them if their name changes. */
    val number: String = "",
    val mode: String = MODE_MIRROR,
    val fixedMinutes: Int = 60,
    val capMinutes: Int = 12 * 60,
    /** When you last sent them a message that they haven't answered yet (0 = nothing pending). */
    val lastMySend: Long = 0L,
    /** When the held messages get shown. */
    val releaseAt: Long = 0L,
    /** Messages waiting to be shown. */
    val held: List<Msg> = emptyList(),
    /** Messages already released and currently in Tempo's notification. */
    val shown: List<Msg> = emptyList(),
    /** Newest WhatsApp message time already handled (to skip repeats when WhatsApp re-posts). */
    val lastSeenMsgTime: Long = 0L,
    /** How long they took on their last reply. */
    val lastWaitMs: Long = 0L,
    /** Message Tempo sends for you when a hold ends. */
    val autoReplyOn: Boolean = false,
    val autoReply: String = "",
    /** true = send every time their messages are shown; false = send once, then switch off. */
    val autoReplyRepeat: Boolean = false,
    /** When Tempo last sent the auto-reply (so it isn't mistaken for you reading the chat). */
    val lastAutoReplyAt: Long = 0L,
)

data class AppState(
    val contacts: List<Contact> = emptyList(),
    val recent: List<String> = emptyList(),
    val log: List<String> = emptyList(),
)

fun nameKey(name: String): String = cleanName(name).lowercase(Locale.ROOT)

/** Strips invisible direction marks and WhatsApp's "(3 messages)" suffix. */
fun cleanName(raw: String): String =
    raw.replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
        .replace(Regex("\\s*\\(\\d+\\s+\\S+\\)\\s*$"), "")
        .trim()

object Store {
    private const val PREFS = "tempo"
    private const val KEY = "state"
    private const val MAX_LOG = 40
    private const val MAX_RECENT = 15

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private var loaded = false

    fun get(ctx: Context): AppState {
        load(ctx)
        return _state.value
    }

    fun find(ctx: Context, name: String): Contact? {
        val k = nameKey(name)
        return get(ctx).contacts.firstOrNull { nameKey(it.name) == k }
    }

    /**
     * Finds a watched contact by name, or — if the name changed — by phone number.
     * [number] is any number already known for this chat (e.g. from WhatsApp's notification).
     * When found by number under a new name, the contact is renamed.
     */
    fun resolve(ctx: Context, name: String, number: String? = null): Contact? {
        find(ctx, name)?.let { c ->
            if (c.number.isBlank()) {
                val n = number?.takeIf { it.length >= 8 } ?: Avatars.whatsAppNumber(ctx, name)
                if (n != null) updateContact(ctx, c.name) { it.copy(number = n) }
            }
            return find(ctx, name)
        }
        val watchedWithNumbers = get(ctx).contacts.filter { it.number.isNotBlank() }
        if (watchedWithNumbers.isEmpty()) return null
        val candidates = listOfNotNull(
            number?.takeIf { it.length >= 8 },
            name.filter { it.isDigit() }.takeIf { it.length >= 8 && name.none { ch -> ch.isLetter() } },
            lookupNumberCached(ctx, name)
        )
        val match = watchedWithNumbers.firstOrNull { c -> candidates.any { sameNumber(it, c.number) } } ?: return null
        val newName = cleanName(name)
        Store.log(ctx, "${match.name} is now called $newName in WhatsApp — updated")
        Avatars.rename(ctx, match.name, newName)
        Notifier.cancel(ctx, match.name)
        updateContact(ctx, match.name) { it.copy(name = newName) }
        return find(ctx, newName)
    }

    private val lookupCache = HashMap<String, Pair<String?, Long>>()

    private fun lookupNumberCached(ctx: Context, name: String): String? {
        val k = nameKey(name)
        val now = System.currentTimeMillis()
        synchronized(lookupCache) {
            lookupCache[k]?.let { if (now - it.second < 10 * 60_000) return it.first }
        }
        val n = Avatars.whatsAppNumber(ctx, name)
        synchronized(lookupCache) { lookupCache[k] = n to now }
        return n
    }

    fun update(ctx: Context, fn: (AppState) -> AppState): AppState = synchronized(this) {
        load(ctx)
        val next = fn(_state.value)
        _state.value = next
        prefs(ctx).edit().putString(KEY, toJson(next).toString()).apply()
        next
    }

    fun updateContact(ctx: Context, name: String, fn: (Contact) -> Contact) {
        val k = nameKey(name)
        update(ctx) { st -> st.copy(contacts = st.contacts.map { if (nameKey(it.name) == k) fn(it) else it }) }
    }

    fun log(ctx: Context, line: String) {
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        update(ctx) { it.copy(log = (listOf("$stamp  $line") + it.log).take(MAX_LOG)) }
    }

    fun addRecent(ctx: Context, name: String) {
        val clean = cleanName(name)
        if (clean.isBlank()) return
        val cur = get(ctx).recent
        if (cur.firstOrNull() == clean) return
        update(ctx) { st ->
            st.copy(recent = (listOf(clean) + st.recent.filter { nameKey(it) != nameKey(clean) }).take(MAX_RECENT))
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context) = synchronized(this) {
        if (loaded) return@synchronized
        _state.value = try {
            prefs(ctx).getString(KEY, null)?.let { fromJson(JSONObject(it)) } ?: AppState()
        } catch (e: Exception) {
            AppState()
        }
        loaded = true
    }

    // ---- JSON ----

    private fun msgsToJson(list: List<Msg>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.text).put("at", it.time)) }
    }

    private fun msgsFromJson(arr: JSONArray?): List<Msg> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Msg(o.optString("t"), o.optLong("at"))
        }
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }

    private fun toJson(s: AppState): JSONObject = JSONObject().apply {
        put("contacts", JSONArray().apply {
            s.contacts.forEach { c ->
                put(JSONObject()
                    .put("name", c.name)
                    .put("num", c.number)
                    .put("mode", c.mode)
                    .put("fixed", c.fixedMinutes)
                    .put("cap", c.capMinutes)
                    .put("mySend", c.lastMySend)
                    .put("releaseAt", c.releaseAt)
                    .put("held", msgsToJson(c.held))
                    .put("shown", msgsToJson(c.shown))
                    .put("seen", c.lastSeenMsgTime)
                    .put("wait", c.lastWaitMs)
                    .put("arOn", c.autoReplyOn)
                    .put("ar", c.autoReply)
                    .put("arRep", c.autoReplyRepeat)
                    .put("arAt", c.lastAutoReplyAt))
            }
        })
        put("recent", JSONArray(s.recent))
        put("log", JSONArray(s.log))
    }

    private fun fromJson(o: JSONObject): AppState {
        val arr = o.optJSONArray("contacts") ?: JSONArray()
        val contacts = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Contact(
                name = c.getString("name"),
                number = c.optString("num", ""),
                mode = c.optString("mode", MODE_MIRROR),
                fixedMinutes = c.optInt("fixed", 60),
                capMinutes = c.optInt("cap", 12 * 60),
                lastMySend = c.optLong("mySend"),
                releaseAt = c.optLong("releaseAt"),
                held = msgsFromJson(c.optJSONArray("held")),
                shown = msgsFromJson(c.optJSONArray("shown")),
                lastSeenMsgTime = c.optLong("seen"),
                lastWaitMs = c.optLong("wait"),
                autoReplyOn = c.optBoolean("arOn", false),
                autoReply = c.optString("ar", ""),
                autoReplyRepeat = c.optBoolean("arRep", false),
                lastAutoReplyAt = c.optLong("arAt"),
            )
        }
        return AppState(contacts, strings(o.optJSONArray("recent")), strings(o.optJSONArray("log")))
    }
}

fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60_000
    if (totalMin < 1) return "under a minute"
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

fun fmtTime(ms: Long): String {
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.ROOT).let { it.format(Date(ms)) == it.format(Date()) }
    val pattern = if (sameDay) "HH:mm" else "EEE HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

/** Same phone number, ignoring country-code / leading-zero differences. */
fun sameNumber(a: String, b: String): Boolean {
    val x = a.filter { it.isDigit() }
    val y = b.filter { it.isDigit() }
    if (x.length < 8 || y.length < 8) return false
    return x.takeLast(9) == y.takeLast(9)
}

/** Pulls a phone number out of WhatsApp ids like "971501234567@s.whatsapp.net" or "tel:+971...". */
fun numberFromWhatsAppId(id: String?): String? {
    if (id.isNullOrBlank()) return null
    if (id.contains("@g.us")) return null
    val core = if (id.contains('@')) id.substringBefore('@') else id.removePrefix("tel:")
    val d = core.filter { it.isDigit() }
    return d.takeIf { it.length >= 8 }
}
