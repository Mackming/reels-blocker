package com.reelbreaker.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Accessibility services are automatically restored by the OS after reboot,
 * but we still verify the flag & keep the blocker state consistent so the
 * dashboard doesn't lie about protection status.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains("${context.packageName}/${ReelAccessibilityService::class.java.name}") == true
            com.reelbreaker.data.Store.get(context).blockerActive = enabled
        }
    }
}
