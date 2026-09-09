package com.sightline.app.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the wake word listener after a reboot so "Hey Sightline"
 * works even before the app is opened.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed - starting wake word service")
            WakeWordService.startCapture(context)
        }
    }
}