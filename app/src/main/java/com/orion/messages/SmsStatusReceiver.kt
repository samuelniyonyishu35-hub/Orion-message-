package com.orion.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rawUri = intent.getStringExtra(EXTRA_URI) ?: return
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return
        val values = ContentValues()
        when (intent.action) {
            ACTION_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                } else {
                    values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                    values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
                }
            }
            ACTION_DELIVERED -> values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            else -> return
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    companion object {
        const val ACTION_SENT = "com.orion.messages.SMS_SENT"
        const val ACTION_DELIVERED = "com.orion.messages.SMS_DELIVERED"
        const val EXTRA_URI = "sms_uri"
    }
}
