package com.orion.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Android routes incoming SMS here when ORION is the default SMS app.
        // Inbox persistence and multipart decoding are the next implementation step.
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MMS delivery entry point; MMS parsing is not implemented in this build.
    }
}
