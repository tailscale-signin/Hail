package com.aistra.hail.utils

import android.util.Log
import com.aistra.hail.BuildConfig

object HLog {
    fun e(error: Throwable) {
        if (BuildConfig.DEBUG) Log.e("Hail", error.javaClass.simpleName)
    }
}
