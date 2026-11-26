package com.aistra.hail.utils

internal enum class DisableObservation {
    UNKNOWN, DISABLED, STOPPED;

    /** A failed or unobservable disable must never force-stop a still-enabled package. */
    inline fun confirmStopped(stop: () -> Unit, observe: () -> DisableObservation): Boolean = when (this) {
        UNKNOWN -> false
        STOPPED -> true
        DISABLED -> {
            stop()
            observe() == STOPPED
        }
    }
}
