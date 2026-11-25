package com.aistra.hail.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.collection.LruCache
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader

object AppIconCache {
    private data class Key(val pkg: String, val user: Int, val size: Int, val pack: String, val adaptive: Boolean)
    private val cache = object : LruCache<Key, Bitmap>((Runtime.getRuntime().maxMemory() / 4096).toInt()) {
        override fun sizeOf(key: Key, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val loaders = mutableMapOf<Pair<Int, Boolean>, AppIconLoader>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    fun clear() {
        cache.evictAll()
        IconPack.clear()
    }

    @Synchronized private fun loader(context: Context, key: Key) = loaders.getOrPut(key.size to key.adaptive) {
        AppIconLoader(key.size, key.adaptive, context.applicationContext)
    }

    fun loadIconBitmapAsync(context: Context, info: ApplicationInfo, userId: Int, view: ImageView,
                            setColorFilter: Boolean = false): Job = scope.launch {
        val size = view.measuredWidth.takeIf { it > 0 } ?: context.resources.getDimensionPixelSize(R.dimen.app_icon_size)
        val key = Key(info.packageName, userId, size, HailData.iconPack, HailData.synthesizeAdaptiveIcons)
        view.colorFilter = if (setColorFilter) grayscale else null
        cache[key]?.let { view.setImageBitmap(it); return@launch }
        view.setImageDrawable(null)
        val bitmap = withContext(dispatcher) {
            cache[key] ?: runCatching {
                IconPack.loadIcon(key.pkg, key.pack) ?: loader(context, key).loadIcon(info, false)
            }.getOrNull()?.also { cache.put(key, it) }
        }
        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageDrawable(context.packageManager.defaultActivityIcon)
    }
}
