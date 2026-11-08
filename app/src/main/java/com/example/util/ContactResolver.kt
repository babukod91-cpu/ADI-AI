package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.model.ContactMatch

class ContactResolver(private val ctx: Context) {
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Name -> matching numbers (deduplicated by number). Empty if no permission or no match. */
    fun find(name: String): List<ContactMatch> {
        if (!hasPermission() || name.isBlank()) return emptyList()
        val out = LinkedHashMap<String, ContactMatch>()
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 5) {
                val n = c.getString(0) ?: continue
                val num = (c.getString(1) ?: continue).filter { it.isDigit() || it == '+' }
                if (num.isNotEmpty()) out.putIfAbsent(num.takeLast(10), ContactMatch(n, num))
            }
        }
        return out.values.sortedBy { if (it.name.equals(name.trim(), true)) 0 else 1 }
    }
}
