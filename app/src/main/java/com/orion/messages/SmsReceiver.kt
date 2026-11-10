package com.orion.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Android delivers SMS to the default SMS app through this receiver.
        // Inbox persistence and multipart parsing are planned for the next iteration.
    }
}
