package com.orion.messages

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val OrionBlue = Color(0xFF5B9DFF)
private val OrionViolet = Color(0xFF8B75FF)

data class ThreadItem(val address: String, val body: String, val date: Long)
data class SimChoice(val id: Int, val slot: Int, val label: String)

class MainActivity : ComponentActivity() {
    private var refreshTick by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val role = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else Telephony.Sms.getDefaultSmsPackage(this) == packageName
        Toast.makeText(this, if (active) "ORION est maintenant votre application SMS par défaut" else "ORION n'a pas été défini par défaut", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        ))
        setContent { OrionApp(this, refreshTick, ::refresh, ::requestDefaultSms, ::sendSms) }
    }

    private fun refresh() { refreshTick++ }

    private fun requestDefaultSms() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = getSystemService(RoleManager::class.java)
                if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    Toast.makeText(this, "Le rôle SMS n'est pas disponible sur cet appareil", Toast.LENGTH_LONG).show()
                } else if (manager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    Toast.makeText(this, "ORION est déjà l'application SMS par défaut", Toast.LENGTH_SHORT).show()
                } else {
                    role.launch(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
            } else {
                @Suppress("DEPRECATION")
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                role.launch(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir le réglage SMS : ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSms(destination: String, body: String, subscriptionId: Int?, onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
            onResult("Autorise l'envoi de SMS, puis réessaie.")
            return
        }
        try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val base = getSystemService(SmsManager::class.java)
                if (subscriptionId != null && subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    base.createForSubscriptionId(subscriptionId) else base
            } else {
                @Suppress("DEPRECATION")
                if (subscriptionId != null && subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
            }
            manager.sendTextMessage(destination, null, body, null, null)
            onResult("SMS remis au service Android pour envoi. La livraison dépend du réseau mobile.")
        } catch (e: Exception) {
            onResult("Échec de l'envoi : ${e.localizedMessage ?: "vérifie la SIM et le réseau"}.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrionApp(context: Context, tick: Int, refresh: () -> Unit, setDefault: () -> Unit,
             sendSms: (String, String, Int?, (String) -> Unit) -> Unit) {
    var selected by remember { mutableStateOf<ThreadItem?>(null) }
    var number by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var chosenSim by remember { mutableIntStateOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
    val bg = if (dark) Color(0xFF080D18) else Color(0xFFF2F5FA)
    val panel = if (dark) Color(0xFF141D2D) else Color.White
    val fg = if (dark) Color(0xFFF4F7FF) else Color(0xFF172033)
    val muted = if (dark) Color(0xFF9BAAC2) else Color(0xFF6C7890)
    val threads = remember(tick) { loadThreads(context) }
    val sims = remember(tick) { loadSims(context) }
    LaunchedEffect(sims) {
        if (sims.none { it.id == chosenSim }) chosenSim = sims.firstOrNull()?.id ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = OrionBlue, background = bg, surface = panel) else lightColorScheme(primary = Color(0xFF286FE8), background = bg, surface = panel)) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(if (selected == null) "ORION" else selected!!.address, color = fg, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Text(if (selected == null) "MESSAGES  ·  PRIVÉ & SIMPLE" else "CONVERSATION SMS", color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        }
                    },
                    navigationIcon = { if (selected != null) IconButton(onClick = { selected = null; status = "" }) { Icon(Icons.Default.ArrowBack, null, tint = fg) } },
                    actions = {
                        IconButton(onClick = { dark = !dark }) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Changer le thème", tint = fg) }
                        IconButton(onClick = { selected = null; status = "Choisis « Définir ORION par défaut » pour activer le rôle SMS." }) { Icon(Icons.Default.Settings, "Réglages", tint = fg) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
                )
            },
            floatingActionButton = {
                if (selected == null) FloatingActionButton(onClick = { number = ""; message = ""; selected = ThreadItem("", "", 0); status = "" }, containerColor = OrionBlue, contentColor = Color.White) {
                    Icon(Icons.Default.Add, "Nouveau message")
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                if (selected == null) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF1A3D70), Color(0xFF553C91)))).padding(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha=.14f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Sms, null, tint = Color.White)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Votre espace de messages", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text("Une expérience pensée pour vous", color = Color.White.copy(alpha=.72f), fontSize = 12.sp)
                                }
                            }
                            Text("Vos SMS, réunis dans une interface claire.", color = Color.White.copy(alpha=.88f), fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("CONFIGURATION", color = muted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                            Button(onClick = setDefault, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                Text("Définir ORION par défaut", fontWeight = FontWeight.SemiBold)
                            }
                            Text("Nécessaire pour recevoir et gérer les SMS sur Android.", color = muted, fontSize = 11.sp)
                            HorizontalDivider(color = muted.copy(alpha=.18f))
                            Text("CARTE SIM POUR L'ENVOI", color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                            if (sims.isEmpty()) {
                                Text("Aucune SIM détectée. Vérifie que ta carte SIM est active et autorise l'accès au téléphone.", color = muted, fontSize = 12.sp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    sims.forEach { sim ->
                                        FilterChip(
                                            selected = chosenSim == sim.id,
                                            onClick = { chosenSim = sim.id },
                                            label = { Text(sim.label) },
                                            leadingIcon = if (chosenSim == sim.id) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Text("SIM sélectionnée : ${sims.firstOrNull { it.id == chosenSim }?.label ?: "aucune"}", color = muted, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Messages récents", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${threads.size} conversations", color = muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                        items(threads) { item ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(panel).clickable { selected = item; status = "" }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).background(Brush.linearGradient(listOf(OrionBlue, OrionViolet)), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(item.address.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.address, color = fg, fontWeight = FontWeight.SemiBold)
                                    Text(item.body, color = muted, maxLines = 1, fontSize = 12.sp)
                                }
                            }
                        }
                        if (threads.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Aucune conversation", color = fg, fontWeight = FontWeight.SemiBold)
                                Text("Appuie sur + pour écrire ton premier SMS.", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    if (selected!!.address.isEmpty()) {
                        OutlinedTextField(number, { number = it }, label = { Text("Numéro du destinataire") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
                        Spacer(Modifier.height(10.dp))
                    }
                    Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                            if (selected!!.body.isNotBlank()) {
                                Box(Modifier.align(Alignment.Start).clip(RoundedCornerShape(18.dp)).background(if (dark) Color(0xFF26344B) else Color(0xFFE8EEF8)).padding(13.dp)) {
                                    Text(selected!!.body, color = fg, fontSize = 14.sp)
                                }
                            } else {
                                Text("Nouveau message", color = muted, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (sims.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sims.forEach { sim ->
                                FilterChip(selected = chosenSim == sim.id, onClick = { chosenSim = sim.id }, label = { Text(sim.label) })
                            }
                        }
                    }
                    OutlinedTextField(message, { message = it }, label = { Text("Votre message") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), minLines = 1, maxLines = 4)
                    if (status.isNotBlank()) Text(status, color = if (status.startsWith("Échec")) Color(0xFFFF8A80) else muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Button(
                        onClick = {
                            val dest = selected!!.address.ifEmpty { number }.trim()
                            if (dest.isBlank()) status = "Entre le numéro du destinataire."
                            else if (message.isBlank()) status = "Écris ton message avant l'envoi."
                            else {
                                sendSms(dest, message.trim(), chosenSim.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }) { result ->
                                    status = result
                                    if (result.startsWith("SMS remis")) {
                                        message = ""
                                        selected = ThreadItem(dest, "SMS envoyé depuis ORION", System.currentTimeMillis())
                                        refresh()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Envoyer le SMS", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
fun loadSims(context: Context): List<SimChoice> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        manager?.activeSubscriptionInfoList?.map {
            SimChoice(it.subscriptionId, it.simSlotIndex, it.displayName?.toString()?.takeIf { name -> name.isNotBlank() } ?: "SIM ${it.simSlotIndex + 1}")
        }?.sortedBy { it.slot } ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

fun loadThreads(context: Context): List<ThreadItem> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = mutableListOf<ThreadItem>()
    try {
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, Telephony.Sms.DATE + " DESC")?.use { c ->
            val a = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val b = c.getColumnIndex(Telephony.Sms.BODY)
            val d = c.getColumnIndex(Telephony.Sms.DATE)
            val seen = mutableSetOf<String>()
            while (c.moveToNext() && result.size < 100) {
                val address = if (a >= 0) c.getString(a) ?: "Inconnu" else "Inconnu"
                if (seen.add(address)) result.add(ThreadItem(address, if (b >= 0) c.getString(b) ?: "" else "", if (d >= 0) c.getLong(d) else 0L))
            }
        }
    } catch (_: Exception) { }
    return result
}
