package com.aistra.hail.widget

internal object WidgetIdentity {
    /** A stable 64-bit ID survives sorting, process recreation and Java string-hash collisions. */
    fun itemId(packageName: String): Long {
        var hash = -3750763034362895579L
        for (byte in packageName.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xffL)) * 1099511628211L
        }
        return hash
    }
}
