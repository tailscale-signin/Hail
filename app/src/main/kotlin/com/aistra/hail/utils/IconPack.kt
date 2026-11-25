package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData

object IconPack {
    private data class Index(val pack: String, val resources: Resources, val icons: Map<String, String>)
    private var loaded: Index? = null

    @Synchronized fun clear() { loaded = null }

    @SuppressLint("DiscouragedApi")
    fun loadIcon(packageName: String, pack: String = HailData.iconPack): Bitmap? {
        if (pack == HailData.ACTION_NONE) return null
        return runCatching {
            val index = index(pack)
            val name = index.icons[packageName] ?: return null
            val id = index.resources.getIdentifier(name, "drawable", pack)
            if (id != 0) BitmapFactory.decodeResource(index.resources, id) else null
        }.getOrNull()
    }

    @SuppressLint("DiscouragedApi")
    @Synchronized private fun index(pack: String): Index {
        loaded?.takeIf { it.pack == pack }?.let { return it }
        val resources = app.packageManager.getResourcesForApplication(pack)
        val id = resources.getIdentifier("appfilter", "xml", pack)
        val icons = mutableMapOf<String, String>()
        if (id != 0) resources.getXml(id).use { parser ->
            while (parser.eventType != XmlResourceParser.END_DOCUMENT) {
                if (parser.eventType == XmlResourceParser.START_TAG) {
                    val pkg = parser.getAttributeValue(null, "component")?.substringAfter('{')?.substringBefore('/')?.removeSuffix("}")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (pkg != null && drawable != null && pkg !in icons) icons[pkg] = drawable
                }
                parser.next()
            }
        }
        return Index(pack, resources, icons).also { loaded = it }
    }
}
