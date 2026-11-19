package com.midotheeng.tempo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Profile photos. Source order:
 *  1. The WhatsApp profile photo, saved from WhatsApp's own notifications.
 *  2. The photo saved for that person in the phone's contacts.
 */
object Avatars {
    private const val SIZE = 192
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L

    /** Bumped whenever a photo is saved, so the screen reloads. */
    val version = MutableStateFlow(0)
    private val cache = LruCache<String, Bitmap>(80)
    private val missing = mutableSetOf<String>()

    /** Forget "no photo" results (e.g. after contacts permission is granted) and reload. */
    fun refresh() {
        synchronized(missing) { missing.clear() }
        version.value++
    }

    private fun file(ctx: Context, name: String): File {
        val dir = File(ctx.filesDir, "avatars").apply { mkdirs() }
        val safe = nameKey(name).hashCode().toUInt().toString(16)
        return File(dir, "$safe.png")
    }

    /** Saves a photo taken from a WhatsApp notification (at most every few hours per person). */
    fun saveFromWhatsApp(ctx: Context, name: String, d: Drawable?) {
        d ?: return
        val f = file(ctx, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return
        val bmp = toBitmap(d) ?: return
        try {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cache.put(nameKey(name), bmp)
            synchronized(missing) { missing.remove(nameKey(name)) }
            version.value++
        } catch (_: Exception) {
        }
    }

    /** Blocking — call off the main thread. */
    fun load(ctx: Context, name: String): Bitmap? {
        val key = nameKey(name)
        cache.get(key)?.let { return it }
        if (synchronized(missing) { key in missing }) return null
        val f = file(ctx, name)
        val bmp = (if (f.exists()) BitmapFactory.decodeFile(f.path) else null) ?: fromContacts(ctx, name)
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        return bmp
    }

    private fun fromContacts(ctx: Context, name: String): Bitmap? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
                arrayOf(cleanName(name)), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) {
            return Bitmap.createScaledBitmap(d.bitmap, SIZE, SIZE, true)
        }
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return null
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        return b
    }
}
