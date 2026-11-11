package com.orion.messages

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object OrionBackupScheduler {
    const val PREFS = "orion_backup"
    const val KEY_AUTO = "auto_wifi"
    const val KEY_NETWORK = "network_mode"
    const val MODE_WIFI = "wifi"
    const val MODE_MOBILE = "mobile"
    const val MODE_BOTH = "both"

    fun scheduleAutomatic(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork("orion_auto_sms_backup")
            manager.cancelUniquePeriodicWork("orion_auto_sms_backup")
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<OrionSmsBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf("action" to "backup", "network_mode" to MODE_WIFI, "automatic" to true))
            .build()
        manager.enqueueUniquePeriodicWork(
            "orion_auto_sms_backup",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun backupNow(context: Context, mode: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OrionSmsBackupWorker>()
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf("action" to "backup", "network_mode" to mode, "automatic" to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("orion_manual_sms_backup", ExistingWorkPolicy.REPLACE, request)
    }

    fun restore(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<OrionSmsBackupWorker>()
            .setConstraints(constraints)
            .setInputData(androidx.work.workDataOf("action" to "restore", "network_mode" to MODE_BOTH, "automatic" to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("orion_sms_restore", ExistingWorkPolicy.REPLACE, request)
    }
}

class OrionSmsBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(OrionBackupScheduler.PREFS, Context.MODE_PRIVATE)
        val action = inputData.getString("action") ?: "backup"
        prefs.edit().putString("last_result", if (action == "restore") "Restauration en cours…" else "Sauvegarde en cours…").apply()
        try {
            val mode = inputData.getString("network_mode") ?: OrionBackupScheduler.MODE_WIFI
            if (!networkAllowed(mode)) {
                prefs.edit().putString("last_result", "En attente du réseau autorisé…").apply()
                return@withContext Result.retry()
            }
            val user = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("Connecte d'abord ton compte Google à ORION.")
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Autorisation de lecture des SMS manquante.")
            }
            val count = if (action == "restore") restoreSms(user.uid) else backupSms(user.uid)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("last_result", if (action == "restore") "Restauration terminée : $count nouveau(x) SMS ajouté(s)." else "Sauvegarde terminée : $count SMS vérifié(s).")
                .putLong("last_time", now)
                .putBoolean("last_success", true)
                .apply()
            Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("last_result", e.localizedMessage ?: "Opération de sauvegarde impossible.")
                .putLong("last_time", System.currentTimeMillis()).putBoolean("last_success", false).apply()
            if (e is SecurityException) Result.failure() else Result.retry()
        }
    }

    private fun networkAllowed(mode: String): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        return when (mode) {
            OrionBackupScheduler.MODE_WIFI -> wifi
            OrionBackupScheduler.MODE_MOBILE -> mobile
            else -> wifi || mobile
        }
    }

    private fun backupSms(uid: String): Int {
        val rows = readSms()
        val db = FirebaseFirestore.getInstance()
        val backup = db.collection("users").document(uid).collection("backups").document("sms")
        rows.chunked(400).forEach { group ->
            val batch = db.batch()
            group.forEach { sms ->
                val id = smsId(sms)
                val ref = backup.collection("chunks").document(id)
                batch.set(ref, mapOf(
                    "address" to sms.address,
                    "body" to sms.body,
                    "date" to sms.date,
                    "type" to sms.type,
                    "status" to sms.status,
                    "read" to sms.read
                ))
            }
            Tasks.await(batch.commit())
        }
        Tasks.await(backup.set(mapOf(
            "updatedAt" to System.currentTimeMillis(),
            "messageCount" to rows.size,
            "formatVersion" to 1
        )))
        return rows.size
    }

    private fun restoreSms(uid: String): Int {
        if (Telephony.Sms.getDefaultSmsPackage(applicationContext) != applicationContext.packageName) {
            throw IllegalStateException("Définis ORION comme application SMS par défaut avant de restaurer.")
        }
        val backup = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("backups").document("sms")
        val docs = Tasks.await(backup.collection("chunks").get()).documents
        val localIds = readSms().mapTo(mutableSetOf()) { smsId(it) }
        var inserted = 0
        docs.forEach { doc ->
            val sms = BackupSms(
                address = doc.getString("address") ?: "",
                body = doc.getString("body") ?: "",
                date = doc.getLong("date") ?: 0L,
                type = (doc.getLong("type") ?: Telephony.Sms.MESSAGE_TYPE_INBOX.toLong()).toInt(),
                status = (doc.getLong("status") ?: Telephony.Sms.STATUS_NONE.toLong()).toInt(),
                read = doc.getBoolean("read") ?: false
            )
            if (sms.address.isNotBlank() && smsId(sms) !in localIds) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sms.address)
                    put(Telephony.Sms.BODY, sms.body)
                    put(Telephony.Sms.DATE, sms.date)
                    put(Telephony.Sms.TYPE, sms.type)
                    put(Telephony.Sms.STATUS, sms.status)
                    put(Telephony.Sms.READ, if (sms.read) 1 else 0)
                    put(Telephony.Sms.SEEN, if (sms.read) 1 else 0)
                }
                val insertedUri = applicationContext.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                if (insertedUri != null) {
                    localIds.add(smsId(sms))
                    inserted++
                }
            }
        }
        return inserted
    }

    private fun readSms(): List<BackupSms> {
        val result = mutableListOf<BackupSms>()
        applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " ASC"
        )?.use { cursor ->
            val address = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndex(Telephony.Sms.BODY)
            val date = cursor.getColumnIndex(Telephony.Sms.DATE)
            val type = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val status = cursor.getColumnIndex(Telephony.Sms.STATUS)
            val read = cursor.getColumnIndex(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                result.add(BackupSms(
                    if (address >= 0) cursor.getString(address).orEmpty() else "",
                    if (body >= 0) cursor.getString(body).orEmpty() else "",
                    if (date >= 0) cursor.getLong(date) else 0L,
                    if (type >= 0) cursor.getInt(type) else Telephony.Sms.MESSAGE_TYPE_INBOX,
                    if (status >= 0) cursor.getInt(status) else Telephony.Sms.STATUS_NONE,
                    read >= 0 && cursor.getInt(read) == 1
                ))
            }
        } ?: throw IllegalStateException("Impossible de lire les SMS.")
        return result
    }

    private fun smsId(sms: BackupSms): String {
        val raw = "${sms.address}|\u0000|${sms.date}|\u0000|${sms.type}|\u0000|${sms.body}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
