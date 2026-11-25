package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData

object IconPack {
    @SuppressLint("DiscouragedApi")
    fun loadIcon(packageName: String): Bitmap? = runCatching {
//        val componentName = app.packageManager.getLaunchIntentForPackage(packageName)
//            ?.run { resolveActivity(app.packageManager).toString() } ?: return null
        val pack = HailData.iconPack
        if (pack == HailData.ACTION_NONE) return null
        val resources = app.packageManager.getResourcesForApplication(pack)
        getResourceName(resources, pack, packageName)?.let {
            return BitmapFactory.decodeResource(resources, resources.getIdentifier(it, "drawable", pack))
        }
    }.getOrNull()

    @SuppressLint("DiscouragedApi")
    private fun getResourceName(resources: Resources, resPackage: String, componentName: String): String? {
        val id = resources.getIdentifier("appfilter", "xml", resPackage)
        if (id == 0) return null
        resources.getXml(id).use { parser ->
            while (parser.eventType != XmlResourceParser.END_DOCUMENT) {
                if (parser.eventType == XmlResourceParser.START_TAG &&
                    parser.getAttributeValue(null, "component")?.contains(componentName) == true
                ) return parser.getAttributeValue(null, "drawable")
                parser.next()
            }
        }
        return null
    }
}
