package com.memoflow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_START, true)) {
            // Android 14+ does not allow a BOOT_COMPLETED receiver to create a
            // microphone foreground service. Remember the user's intent and
            // resume once the app is visible again.
            prefs.edit().putBoolean(KEY_RESUME_REQUESTED, true).apply()
        }
    }

    companion object {
        const val PREFS = "recording"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_RESUME_REQUESTED = "resume_requested"
    }
}
