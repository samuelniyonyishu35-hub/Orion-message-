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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Blue = Color(0xFF087CFA)
data class ThreadItem(val address:String,val body:String,val date:Long)
data class SimChoice(val id:Int,val slot:Int,val label:String)

class MainActivity:ComponentActivity(){
    private var tick by mutableIntStateOf(0)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){tick++}
    private val role=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
        tick++
        val ok=if(Build.VERSION.SDK_INT>=29)getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)==true else Telephony.Sms.getDefaultSmsPackage(this)==packageName
        Toast.makeText(this,if(ok)"ORION est l'application SMS par défaut" else "Choix non confirmé",Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_PHONE_STATE))
        setContent{OrionApp(this,tick,{tick++},::makeDefault,::send)}
    }
    private fun makeDefault(){
        try{if(Build.VERSION.SDK_INT>=29){val r=getSystemService(RoleManager::class.java)
            if(r!=null&&r.isRoleAvailable(RoleManager.ROLE_SMS)){if(r.isRoleHeld(RoleManager.ROLE_SMS))Toast.makeText(this,"Déjà l'application SMS par défaut",Toast.LENGTH_SHORT).show() else role.launch(r.createRequestRoleIntent(RoleManager.ROLE_SMS))}
        }else{ @Suppress("DEPRECATION") role.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,packageName))}}
        catch(e:Exception){Toast.makeText(this,e.localizedMessage,Toast.LENGTH_LONG).show()}
    }
    private fun send(to:String,body:String,sim:Int?,done:(String)->Unit){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.SEND_SMS));done("Autorise l'envoi SMS puis réessaie.");return}
        try{val sms=if(Build.VERSION.SDK_INT>=31){val base=getSystemService(SmsManager::class.java);if(sim!=null)base.createForSubscriptionId(sim) else base}else{@Suppress("DEPRECATION") if(sim!=null)SmsManager.getSmsManagerForSubscriptionId(sim) else SmsManager.getDefault()}
            sms.sendTextMessage(to,null,body,null,null);done("SMS transmis au service Android. Livraison selon le réseau.")
        }catch(e:Exception){done("Échec : ${e.localizedMessage ?: "vérifie la SIM et le réseau"}")}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OrionApp(ctx:Context,tick:Int,refresh:()->Unit,setDefault:()->Unit,send:(String,String,Int?,(String)->Unit)->Unit){
    var page by remember{mutableStateOf("home")}; var thread by remember{mutableStateOf<ThreadItem?>(null)}
    var dark by remember{mutableStateOf(false)}; var query by remember{mutableStateOf("")}
    var number by remember{mutableStateOf("")}; var draft by remember{mutableStateOf("")}; var status by remember{mutableStateOf("")}
    var sim by remember{mutableIntStateOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID)}
    val sims=remember(tick){readSims(ctx)}; val conversations=remember(tick){readThreads(ctx)}
    LaunchedEffect(sims){if(sims.none{it.id==sim})sim=sims.firstOrNull()?.id?:SubscriptionManager.INVALID_SUBSCRIPTION_ID}
    val bg=if(dark)Color(0xFF000000) else Color(0xFFF2F2F7); val surface=if(dark)Color(0xFF1C1C1E) else Color.White
    val ink=if(dark)Color.White else Color(0xFF17171A); val secondary=if(dark)Color(0xFF98989D) else Color(0xFF8E8E93)
    MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Blue,background=bg,surface=surface) else lightColorScheme(primary=Blue,background=bg,surface=surface)){
        Scaffold(containerColor=bg,topBar={TopAppBar(
            title={Column{Text(if(page=="settings")"Réglages" else if(thread!=null)if(thread!!.address.isBlank())"Nouveau message" else thread!!.address else "Messages",fontSize=28.sp,fontWeight=FontWeight.Bold,color=ink,letterSpacing=(-.6).sp)
                if(page=="home"&&thread==null)Text("ORION",fontSize=10.sp,color=secondary,letterSpacing=2.sp)}},
            navigationIcon={if(page=="settings"||thread!=null)IconButton(onClick={if(thread!=null)thread=null else page="home"}){Icon(Icons.Default.ArrowBack,"Retour",tint=Blue)}},
            actions={if(page=="home"&&thread==null)IconButton(onClick={page="settings"}){Icon(Icons.Default.Settings,"Réglages",tint=Blue)}},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))},
            floatingActionButton={if(page=="home"&&thread==null)FloatingActionButton(onClick={number="";draft="";thread=ThreadItem("","",0);status=""},containerColor=Blue,contentColor=Color.White,shape=CircleShape){Icon(Icons.Default.Add,"Nouveau SMS")}}
        ){pad->
            when{
                page=="settings"->SettingsScreen(Modifier.fillMaxSize().padding(pad),surface,ink,secondary,dark,{dark=it},sims,sim,{sim=it},setDefault)
                thread==null->HomeScreen(Modifier.fillMaxSize().padding(pad),surface,ink,secondary,conversations,query,{query=it}){thread=it;status=""}
                else->ChatScreen(Modifier.fillMaxSize().padding(pad),thread!!,number,{number=it},draft,{draft=it},status,ink,secondary,surface){
                    val dest=thread!!.address.ifBlank{number}.trim()
                    if(dest.isBlank())status="Saisis le numéro du destinataire."
                    else if(draft.isBlank())status="Écris un message."
                    else send(dest,draft.trim(),sim.takeIf{it!=SubscriptionManager.INVALID_SUBSCRIPTION_ID}){result->status=result;if(result.startsWith("SMS transmis")){draft="";thread=ThreadItem(dest,"SMS envoyé depuis ORION",System.currentTimeMillis());refresh()}}
                }
            }
        }
    }
}

@Composable private fun HomeScreen(mod:Modifier,surface:Color,ink:Color,muted:Color,threads:List<ThreadItem>,query:String,onQuery:(String)->Unit,onOpen:(ThreadItem)->Unit){
    Column(mod.padding(horizontal=16.dp)){
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if(surface==Color.White)Color(0xFFE5E5EA) else surface).padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            Text("⌕",fontSize=22.sp,color=muted);Spacer(Modifier.width(8.dp))
            BasicTextField(query,onQuery,Modifier.weight(1f),singleLine=true,textStyle=TextStyle(color=ink,fontSize=16.sp),decorationBox{inner->if(query.isEmpty())Text("Rechercher",color=muted);inner()})
        }
        Spacer(Modifier.height(20.dp));Text("MESSAGES",fontSize=11.sp,fontWeight=FontWeight.Bold,color=muted,letterSpacing=1.4.sp)
        if(threads.isEmpty()){Column(Modifier.fillMaxWidth().padding(top=70.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Box(Modifier.size(70.dp).clip(CircleShape).background(Blue.copy(alpha=.12f)),contentAlignment=Alignment.Center){Icon(Icons.Default.Sms,null,tint=Blue,modifier=Modifier.size(34.dp))}
            Spacer(Modifier.height(14.dp));Text("Aucun message",fontWeight=FontWeight.SemiBold,color=ink,fontSize=18.sp);Text("Touchez + pour commencer une conversation.",color=muted,fontSize=13.sp)
        }}else LazyColumn(contentPadding=PaddingValues(bottom=90.dp)){
            items(threads.filter{query.isBlank()||it.address.contains(query,true)||it.body.contains(query,true)}){item->
                Row(Modifier.fillMaxWidth().clickable{onOpen(item)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7BAAFF),Color(0xFF3769D8)))),contentAlignment=Alignment.Center){Text(item.address.takeLast(1).uppercase(),color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold)}
                    Spacer(Modifier.width(13.dp));Column(Modifier.weight(1f)){Text(item.address,color=ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(4.dp));Text(item.body,color=muted,fontSize=14.sp,maxLines=1)}
                    Text("›",fontSize=25.sp,color=muted)
                };HorizontalDivider(color=muted.copy(alpha=.2f),modifier=Modifier.padding(start=65.dp))
            }
        }
    }
}

@Composable private fun ChatScreen(mod:Modifier,item:ThreadItem,number:String,onNumber:(String)->Unit,draft:String,onDraft:(String)->Unit,status:String,ink:Color,muted:Color,surface:Color,onSend:()->Unit){
    Column(mod.padding(horizontal=12.dp)){
        if(item.address.isBlank()){Spacer(Modifier.height(10.dp));Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
            Text("À :",color=muted);Spacer(Modifier.width(8.dp));BasicTextField(number,onNumber,Modifier.weight(1f),textStyle=TextStyle(color=ink,fontSize=16.sp),decorationBox{inner->if(number.isEmpty())Text("Numéro de téléphone",color=muted);inner()})
        }}
        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.BottomEnd){
            if(item.body.isNotBlank())Column(horizontalAlignment=Alignment.End){
                Box(Modifier.widthIn(max=300.dp).clip(RoundedCornerShape(21.dp,21.dp,5.dp,21.dp)).background(Blue).padding(horizontal=15.dp,vertical=11.dp)){Text(item.body,color=Color.White,fontSize=16.sp)}
                Text("SMS",color=muted,fontSize=10.sp,modifier=Modifier.padding(top=5.dp,end=6.dp))
            }else Column(Modifier.fillMaxWidth().padding(bottom=25.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Box(Modifier.size(65.dp).clip(CircleShape).background(surface),contentAlignment=Alignment.Center){Icon(Icons.Default.Person,null,tint=Blue,modifier=Modifier.size(31.dp))}
                Spacer(Modifier.height(10.dp));Text(if(item.address.isBlank())"Nouveau message" else item.address,color=ink,fontWeight=FontWeight.SemiBold);Text("SMS · ORION Messages",color=muted,fontSize=12.sp)
            }
        }
        if(status.isNotBlank())Text(status,color=muted,fontSize=11.sp,modifier=Modifier.padding(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.Bottom){
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(surface).padding(horizontal=16.dp,vertical=13.dp)){
                BasicTextField(draft,onDraft,Modifier.fillMaxWidth(),textStyle=TextStyle(color=ink,fontSize=16.sp),decorationBox{inner->if(draft.isEmpty())Text("SMS · Message texte",color=muted);inner()})
            };Spacer(Modifier.width(8.dp));IconButton(onClick=onSend,modifier=Modifier.size(46.dp).clip(CircleShape).background(Blue)){Icon(Icons.Default.Send,"Envoyer",tint=Color.White)}
        }
    }
}

@Composable private fun SettingsScreen(mod:Modifier,surface:Color,ink:Color,muted:Color,dark:Boolean,onDark:(Boolean)->Unit,sims:List<SimChoice>,selected:Int,onSim:(Int)->Unit,onDefault:()->Unit){
    Column(mod.padding(horizontal=16.dp)){
        Spacer(Modifier.height(16.dp));SectionTitle("APPLICATION",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Blue),contentAlignment=Alignment.Center){Icon(Icons.Default.Sms,null,tint=Color.White)}
                Spacer(Modifier.width(12.dp));Column{Text("ORION Messages",color=ink,fontWeight=FontWeight.SemiBold);Text("Messagerie SMS",color=muted,fontSize=12.sp)}}
            Spacer(Modifier.height(16.dp));Button(onClick=onDefault,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp)){Text("Définir comme application SMS par défaut")}
        }
        Spacer(Modifier.height(22.dp));SectionTitle("CARTES SIM",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Text("SIM pour les nouveaux messages",color=ink,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp))
            if(sims.isEmpty())Text("Aucune SIM détectée. Vérifie que ta SIM est active et autorise l'accès Téléphone.",color=muted,fontSize=13.sp)
            sims.forEach{sim->Row(Modifier.fillMaxWidth().clickable{onSim(sim.id)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(sim.label,color=ink);Text("Emplacement ${sim.slot+1}",color=muted,fontSize=11.sp)}
                if(selected==sim.id)Icon(Icons.Default.Check,null,tint=Blue)
            }}
        }
        Spacer(Modifier.height(22.dp));SectionTitle("APPARENCE",muted)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Mode sombre",color=ink,modifier=Modifier.weight(1f));Switch(dark,onDark)
        }
        Spacer(Modifier.height(22.dp));SectionTitle("CONFIDENTIALITÉ",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Text("Accès aux contacts désactivé",color=ink,fontWeight=FontWeight.SemiBold)
            Text("ORION ne demande pas l'autorisation Contacts et ne lit pas ton carnet d'adresses. Les conversations utilisent les numéros présents dans les SMS.",color=muted,fontSize=12.sp)
        }
    }
}
@Composable private fun SectionTitle(value:String,color:Color){Text(value,color=color,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp);Spacer(Modifier.height(9.dp))}

@SuppressLint("MissingPermission") private fun readSims(ctx:Context):List<SimChoice>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    return try{ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map{SimChoice(it.subscriptionId,it.simSlotIndex,it.displayName?.toString()?.takeIf{s->s.isNotBlank()}?:"SIM ${it.simSlotIndex+1}")}?.sortedBy{it.slot}?:emptyList()}catch(_:Exception){emptyList()}
}
private fun readThreads(ctx:Context):List<ThreadItem>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    val out=mutableListOf<ThreadItem>()
    try{ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE),null,null,Telephony.Sms.DATE+" DESC")?.use{c->
        val ai=c.getColumnIndex(Telephony.Sms.ADDRESS);val bi=c.getColumnIndex(Telephony.Sms.BODY);val di=c.getColumnIndex(Telephony.Sms.DATE);val seen=mutableSetOf<String>()
        while(c.moveToNext()&&out.size<100){val a=if(ai>=0)c.getString(ai)?:"Inconnu" else "Inconnu";if(seen.add(a))out.add(ThreadItem(a,if(bi>=0)c.getString(bi)?:"" else "",if(di>=0)c.getLong(di) else 0L))}
    }}catch(_:Exception){};return out
}
