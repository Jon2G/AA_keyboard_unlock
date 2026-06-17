package com.jon2g.aa_keyboard_unlock

import android.content.Context
import android.content.Intent

/**
 * Cross-process signal: gearhead search tap → Maps process opens stock rek/reh IME.
 * Not a custom overlay — only triggers native Maps car keyboard bind/show.
 */
object MapsNativeIme {
    const val ACTION_PREPARE = "com.jon2g.aa_keyboard_unlock.action.PREPARE_MAPS_NATIVE_IME"
    const val ACTION_OPEN = "com.jon2g.aa_keyboard_unlock.action.OPEN_MAPS_NATIVE_IME"
    private const val MAPS_PKG = "com.google.android.apps.maps"

    fun sendPrepare(context: Context) {
        send(context, ACTION_PREPARE)
    }

    fun sendOpen(context: Context) {
        send(context, ACTION_OPEN)
    }

    private fun send(context: Context, action: String) {
        val intent = Intent(action).apply {
            setPackage(MAPS_PKG)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent)
    }
}
