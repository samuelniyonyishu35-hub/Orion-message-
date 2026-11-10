package com.orion.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

data class ThreadItem(val address: String, val body: String, val date: Long)

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val role = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS))
        setContent { OrionApp(this, refreshTick, ::refresh, ::requestDefaultSms) }
    }
    private fun refresh() { refreshTick++ }
    private fun requestDefaultSms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS))
                role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            @Suppress("DEPRECATION")
            startActivity(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrionApp(context: Context, tick: Int, refresh: () -> Unit, setDefault: () -> Unit) {
    val navy = Color(0xFF101827)
    var selected by remember { mutableStateOf<ThreadItem?>(null) }
    var number by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    val bg = if (dark) Color(0xFF090F1B) else Color(0xFFF3F6FB)
    val surface = if (dark) Color(0xFF151F30) else Color.White
    val fg = if (dark) Color(0xFFF4F7FC) else Color(0xFF172033)
    val threads = remember(tick) { loadThreads(context) }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF69A9FF), background = bg, surface = surface) else lightColorScheme(primary = Color(0xFF1677E8), background = bg, surface = surface)) {
        Scaffold(containerColor = bg, topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (selected == null) "ORION" else selected!!.address, color = fg, fontSize = 23.sp)
                    Text(if (selected == null) "Messages" else "Conversation", color = fg.copy(alpha=.6f), fontSize = 12.sp)
                }
            }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, null, tint=fg) } },
                actions = { TextButton(onClick = { dark = !dark }) { Text(if (dark) "Clair" else "Sombre") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg))
        }, floatingActionButton = { if (selected == null) FloatingActionButton(onClick = { selected = ThreadItem("", "", 0) }) { Icon(Icons.Default.Add, "Nouveau message") } }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=16.dp)) {
                if (selected == null) {
                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Card(Modifier.weight(1f), colors=CardDefaults.cardColors(containerColor=surface)) { Column(Modifier.padding(14.dp)) { Text("SMS", color=fg); Text("Vos messages", color=fg.copy(alpha=.6f), fontSize=12.sp) } }
                        Card(Modifier.weight(1f), colors=CardDefaults.cardColors(containerColor=surface)) { Column(Modifier.padding(14.dp)) { Text("SIM", color=fg); Text("Double SIM compatible", color=fg.copy(alpha=.6f), fontSize=12.sp) } }
                    }
                    Button(onClick=setDefault, modifier=Modifier.fillMaxWidth()) { Text("Définir ORION comme application SMS") }
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(threads) { item ->
                            Row(Modifier.fillMaxWidth().background(surface, RoundedCornerShape(20.dp)).clickable { selected=item }.padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(Color(0xFF438BFF), Color(0xFF875CFA))), CircleShape), contentAlignment=Alignment.Center) { Text(item.address.take(1).uppercase(), color=Color.White) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(item.address, color=fg); Text(item.body, color=fg.copy(alpha=.62f), maxLines=1, fontSize=13.sp) }
                            }
                        }
                        if (threads.isEmpty()) item { Text("Aucune conversation trouvée. Autorisez l'accès aux SMS ou commencez un nouveau message.", color=fg.copy(alpha=.65f), modifier=Modifier.padding(12.dp)) }
                    }
                } else {
                    if (selected!!.address.isEmpty()) OutlinedTextField(number, { number=it }, label={Text("Numéro du destinataire")}, modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(selected!!.body, color=fg)
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(message, { message=it }, label={Text("Votre message")}, modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        val dest=selected!!.address.ifEmpty { number }.trim()
                        if (dest.isNotEmpty() && message.isNotBlank() && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED) {
                            try {
                                val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                                sm.sendTextMessage(dest, null, message, null, null)
                                message=""; selected=ThreadItem(dest, "Message envoyé", System.currentTimeMillis()); refresh()
                            } catch (_: Exception) { }
                        }
                    }, modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Envoyer") }
                }
            }
        }
    }
}

fun loadThreads(context: Context): List<ThreadItem> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = mutableListOf<ThreadItem>()
    try {
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
            val a=c.getColumnIndex(Telephony.Sms.ADDRESS); val b=c.getColumnIndex(Telephony.Sms.BODY); val d=c.getColumnIndex(Telephony.Sms.DATE)
            val seen=mutableSetOf<String>()
            while(c.moveToNext() && result.size<100) {
                val address=if(a>=0)c.getString(a)?: "Inconnu" else "Inconnu"
                if(seen.add(address)) result.add(ThreadItem(address, if(b>=0)c.getString(b)?: "" else "", if(d>=0)c.getLong(d) else 0L))
            }
        }
    } catch (_: Exception) { }
    return result
}
