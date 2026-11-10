package com.orion.messages

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.provider.ContactsContract
import android.provider.BlockedNumberContract
import android.provider.MediaStore
import android.media.MediaRecorder
import android.content.ContentValues
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import androidx.compose.ui.platform.LocalContext
import java.io.File
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private val iBlue = Color(0xFF087CFA)
private val iGray = Color(0xFF8E8E93)
private val iLight = Color(0xFFF2F2F7)
private val iDark = Color(0xFF000000)

data class ThreadItem(val address:String,val body:String,val date:Long,val displayName:String=address)
data class SmsRecord(val id:Long,val body:String,val date:Long,val type:Int,val status:Int)
data class ContactItem(val name:String,val number:String)
data class SimChoice(val id:Int,val slot:Int,val label:String)

class MainActivity:ComponentActivity(){
    private var tick by mutableIntStateOf(0)
    private val smsObserver=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean,uri:Uri?){tick++}}
    private var pendingCallNumber:String?=null
    private val callPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        val target=pendingCallNumber
        pendingCallNumber=null
        if(granted && !target.isNullOrBlank()) placeCall(target)
        else Toast.makeText(this,"Autorisation d'appel refusée.",Toast.LENGTH_LONG).show()
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){tick++}
    private val role=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
        tick++
        val ok=if(Build.VERSION.SDK_INT>=29)getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)==true else Telephony.Sms.getDefaultSmsPackage(this)==packageName
        Toast.makeText(this,if(ok)"ORION est l'application SMS par défaut" else "Choix non confirmé",Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        showSystemBars()
        permissions.launch(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_CONTACTS))
        setContent{OrionApp(this,tick,{tick++},::makeDefault,::call,::send)}
    }
    override fun onResume(){super.onResume();runCatching{contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,smsObserver)};tick++}
    override fun onPause(){runCatching{contentResolver.unregisterContentObserver(smsObserver)};super.onPause()}
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) showSystemBars()
    }
    private fun showSystemBars(){
        WindowCompat.setDecorFitsSystemWindows(window,true)
        val controller=WindowInsetsControllerCompat(window,window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.isAppearanceLightStatusBars=true
        controller.isAppearanceLightNavigationBars=true
    }
    private fun makeDefault(){
        try{if(Build.VERSION.SDK_INT>=29){val r=getSystemService(RoleManager::class.java)
            if(r!=null&&r.isRoleAvailable(RoleManager.ROLE_SMS)){if(r.isRoleHeld(RoleManager.ROLE_SMS))Toast.makeText(this,"Déjà l'application SMS par défaut",Toast.LENGTH_SHORT).show() else role.launch(r.createRequestRoleIntent(RoleManager.ROLE_SMS))}
        }else{@Suppress("DEPRECATION") role.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,packageName))}}
        catch(e:Exception){Toast.makeText(this,e.localizedMessage,Toast.LENGTH_LONG).show()}
    }
    private fun call(number:String){
        if(number.isBlank()){Toast.makeText(this,"Numéro indisponible.",Toast.LENGTH_SHORT).show();return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED){placeCall(number)}
        else {pendingCallNumber=number;callPermission.launch(Manifest.permission.CALL_PHONE)}
    }
    private fun placeCall(number:String){
        try{startActivity(Intent(Intent.ACTION_CALL,Uri.fromParts("tel",number,null)))}
        catch(e:Exception){Toast.makeText(this,"Impossible de lancer l'appel : ${e.localizedMessage}",Toast.LENGTH_LONG).show()}
    }
    private fun send(to:String,body:String,sim:Int?,done:(String)->Unit){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.SEND_SMS));done("Autorise l'envoi SMS puis réessaie.");return}
        try{
            val values=ContentValues().apply{
                put(Telephony.Sms.ADDRESS,to);put(Telephony.Sms.BODY,body);put(Telephony.Sms.DATE,System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT,System.currentTimeMillis());put(Telephony.Sms.READ,1);put(Telephony.Sms.SEEN,1)
                put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_OUTBOX);put(Telephony.Sms.STATUS,Telephony.Sms.STATUS_PENDING)
                if(sim!=null&&Build.VERSION.SDK_INT>=22)put(Telephony.Sms.SUBSCRIPTION_ID,sim)
            }
            val savedUri=contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI,values) ?: throw IllegalStateException("ORION doit être l’application SMS par défaut pour enregistrer les messages.")
            val request=(System.currentTimeMillis()%Int.MAX_VALUE).toInt()
            val sentIntent=Intent(this,SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_SENT).putExtra(SmsStatusReceiver.EXTRA_URI,savedUri.toString())
            val deliveredIntent=Intent(this,SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_DELIVERED).putExtra(SmsStatusReceiver.EXTRA_URI,savedUri.toString())
            val sent=PendingIntent.getBroadcast(this,request,sentIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val delivered=PendingIntent.getBroadcast(this,request+1,deliveredIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val sms=if(Build.VERSION.SDK_INT>=31){val base=getSystemService(SmsManager::class.java);if(sim!=null)base.createForSubscriptionId(sim) else base}else{@Suppress("DEPRECATION") if(sim!=null)SmsManager.getSmsManagerForSubscriptionId(sim) else SmsManager.getDefault()}
            sms.sendTextMessage(to,null,body,sent,delivered)
            done("Message envoyé")
        }catch(e:Exception){done("Échec : ${e.localizedMessage ?: "vérifie la SIM, le réseau et l’application SMS par défaut"}")}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OrionApp(ctx:Context,tick:Int,refresh:()->Unit,setDefault:()->Unit,call:(String)->Unit,send:(String,String,Int?,(String)->Unit)->Unit){
    var page by remember{mutableStateOf("home")}
    var thread by remember{mutableStateOf<ThreadItem?>(null)}
    var dark by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
    var composeQuery by remember{mutableStateOf("")}
    var composeNotice by remember{mutableStateOf("")}
    var number by remember{mutableStateOf("")}
    var draft by remember{mutableStateOf("")}
    var photoUri by remember{mutableStateOf<Uri?>(null)}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->photoUri=uri}
    val cameraPicker=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->
        if(bitmap!=null){val saved=runCatching{MediaStore.Images.Media.insertImage(ctx.contentResolver,bitmap,"ORION photo","Photo prise avec ORION")}.getOrNull();photoUri=saved?.let{Uri.parse(it)}}
    }

    val photoPreview=remember(photoUri){photoUri?.let{uri->runCatching{ctx.contentResolver.openInputStream(uri)?.use{stream->BitmapFactory.decodeStream(stream)?.asImageBitmap()}}.getOrNull()}}
    var status by remember{mutableStateOf("")}
    var selectedContact by remember{mutableStateOf<ContactItem?>(null)}
    var overflowExpanded by remember{mutableStateOf(false)}
    var sim by remember{mutableIntStateOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID)}
    val sims=remember(tick){readSims(ctx)}
    val conversations=remember(tick){readThreads(ctx)}
    val messages=remember(tick,thread?.address){thread?.address?.takeIf{it.isNotBlank()}?.let{readMessages(ctx,it)}?: emptyList()}
    var fontChoice by remember{mutableStateOf(ctx.getSharedPreferences("orion_settings",Context.MODE_PRIVATE).getString("font","System")?:"System")}
    val appFont=fontFamilyForChoice(ctx,fontChoice)
    val appWeight=when(fontChoice){"Light"->FontWeight.Light;"Bold"->FontWeight.Bold;else->FontWeight.Normal}
    val contacts=remember(tick){readContacts(ctx)}
    LaunchedEffect(sims){if(sims.none{it.id==sim})sim=sims.firstOrNull()?.id?:SubscriptionManager.INVALID_SUBSCRIPTION_ID}
    val bg=if(dark)iDark else iLight
    val surface=if(dark)Color(0xFF1C1C1E) else Color.White
    val ink=if(dark)Color.White else Color(0xFF111114)
    val muted=if(dark)Color(0xFF98989D) else iGray
    SideEffect {
        (ctx as? Activity)?.window?.let { window ->
            window.statusBarColor=bg.toArgb()
            window.navigationBarColor=bg.toArgb()
            val bars=WindowInsetsControllerCompat(window,window.decorView)
            bars.isAppearanceLightStatusBars=!dark
            bars.isAppearanceLightNavigationBars=!dark
        }
    }
    MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=iBlue,background=bg,surface=surface) else lightColorScheme(primary=iBlue,background=bg,surface=surface),typography=Typography().copy(bodyLarge=Typography().bodyLarge.copy(fontFamily=appFont,fontWeight=appWeight),bodyMedium=Typography().bodyMedium.copy(fontFamily=appFont,fontWeight=appWeight),bodySmall=Typography().bodySmall.copy(fontFamily=appFont,fontWeight=appWeight),titleLarge=Typography().titleLarge.copy(fontFamily=appFont,fontWeight=appWeight))){
        Scaffold(containerColor=bg,topBar={
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { when {
                page=="compose" -> TopAppBar(title={Text("Nouveau message",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ink)},navigationIcon={IconButton(onClick={page="home";composeQuery="";composeNotice=""}){Icon(Icons.Default.ArrowBack,null,tint=iBlue)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
                page=="settings" -> TopAppBar(title={Text("Réglages",fontSize=28.sp,fontWeight=FontWeight.Bold,color=ink)},navigationIcon={IconButton(onClick={page="home"}){Icon(Icons.Default.ArrowBack,null,tint=iBlue)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
                page=="about" -> TopAppBar(title={Text("À propos",fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=ink)},navigationIcon={IconButton(onClick={page="settings"}){Icon(Icons.Default.ArrowBack,null,tint=iBlue)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
                page=="profile" -> TopAppBar(title={Text("Informations",fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=ink)},navigationIcon={IconButton(onClick={page="chat"}){Icon(Icons.Default.ArrowBack,null,tint=iBlue)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
                thread!=null -> TopAppBar(
                    title={Row(Modifier.fillMaxWidth().clickable{page="profile"}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
                        Avatar(thread!!.displayName,42)
                        Spacer(Modifier.width(10.dp))
                        Column { Text(thread!!.displayName,fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=ink,maxLines=1,overflow=TextOverflow.Ellipsis);Text("touchez pour afficher le profil",fontSize=11.sp,color=muted) }
                    }},
                    navigationIcon={IconButton(onClick={thread=null;page="home";status=""}){Icon(Icons.Default.ArrowBack,null,tint=iBlue)}},
                    actions={IconButton(onClick={call(thread!!.address.ifBlank{number})}){Icon(Icons.Default.Call,"Appeler",tint=iBlue)}},
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
                else -> TopAppBar(title={Column{Text("Messages",fontSize=30.sp,fontWeight=FontWeight.Bold,color=ink,letterSpacing=(-.8).sp);Text("ORION",fontSize=10.sp,color=muted,letterSpacing=2.sp)}},
                    navigationIcon={IconButton(onClick={page="settings"}){Icon(Icons.Default.MoreVert,"Ouvrir les réglages",tint=iBlue)}},
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=bg))
            } }
        },floatingActionButton={if(page=="home"&&thread==null)FloatingActionButton(onClick={composeQuery="";composeNotice="";number="";draft="";thread=null;page="compose";status=""},containerColor=iBlue,contentColor=Color.White,shape=CircleShape){Icon(Icons.Default.Edit,"Nouveau message")}}
        ){pad->
            AnimatedContent(targetState=page+if(thread!=null)"-thread" else "",label="orion-page"){screen->
                when {
                    page=="settings" -> SettingsScreen(Modifier.fillMaxSize().padding(pad),ctx,surface,ink,muted,dark,{dark=it},sims,sim,{sim=it},fontChoice,{fontChoice=it;ctx.getSharedPreferences("orion_settings",Context.MODE_PRIVATE).edit().putString("font",it).apply()},setDefault,{page="about"})
                    page=="about" -> AboutScreen(Modifier.fillMaxSize().padding(pad),surface,ink,muted)
                    page=="profile" && thread!=null -> ProfileScreen(Modifier.fillMaxSize().padding(pad),thread!!,contacts,ink,muted,surface,ctx,call,{page="chat"},{message->status=message})
                    page=="compose" -> NewMessageScreen(Modifier.fillMaxSize().padding(pad),contacts,composeQuery,{composeQuery=it;composeNotice=""},{contact->thread=ThreadItem(contact.number,"",0,contact.name);number=contact.number;draft="";page="chat";status=""},{raw->val cleaned=raw.trim();val digits=cleaned.filter{it.isDigit()};val found=contacts.firstOrNull{it.name.equals(cleaned,true)||it.number.filter{ch->ch.isDigit()}==digits}?:contacts.firstOrNull{it.name.startsWith(cleaned,true)};if(found!=null||digits.length in 7..15){val dest=found?.number?:cleaned;val label=found?.name?:dest;thread=ThreadItem(dest,"",0,label);number=dest;draft="";page="chat";status=""}else composeNotice="Aucun contact correspondant. Vérifie le nom ou saisis un numéro valide."},composeNotice,{composeNotice=it})
                    thread==null -> HomeScreen(Modifier.fillMaxSize().padding(pad),ctx,surface,ink,muted,conversations,contacts,query,{query=it},
                        {item->thread=item;number=item.address;page="chat";status=""},
                        {contact->thread=ThreadItem(contact.number,"",0,contact.name);number=contact.number;page="chat";status=""})
                    else -> ChatScreen(Modifier.fillMaxSize().padding(pad).imePadding(),thread!!,messages,appFont,appWeight,number,{number=it},draft,{draft=it},status,photoUri,photoPreview,
                        {photoPicker.launch("image/*")},{cameraPicker.launch(null)},{photoUri=null},{uri->
                            val dest=thread!!.address.ifBlank{number}.trim()
                            if(dest.isBlank())status="Saisis le numéro du destinataire avant de joindre une photo."
                            else try{
                                val share=Intent(Intent.ACTION_SEND).apply{
                                    type="image/*"
                                    putExtra(Intent.EXTRA_STREAM,uri)
                                    putExtra(Intent.EXTRA_TEXT,draft)
                                    putExtra("address",dest)
                                    putExtra("sms_body",draft)
                                    clipData=ClipData.newUri(ctx.contentResolver,"Photo ORION",uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(share,"Envoyer la photo par MMS"))
                                photoUri=null
                                status="Photo ouverte dans l'application de messagerie. Appuie sur Envoyer pour transmettre le MMS."
                            }catch(e:Exception){status="Impossible d'ouvrir l'envoi MMS : ${e.localizedMessage ?: "application compatible introuvable"}"}
                        },ink,muted,surface,
                        {page="profile"},{call(thread!!.address.ifBlank{number})},{
                            val dest=thread!!.address.ifBlank{number}.trim()
                            if(photoUri!=null)status="Utilise le bouton Envoyer pour joindre la photo."
                            else if(dest.isBlank())status="Saisis le numéro du destinataire."
                            else if(draft.isBlank())status="Écris un message."
                            else {val sentBody=draft.trim();send(dest,sentBody,sim.takeIf{it!=SubscriptionManager.INVALID_SUBSCRIPTION_ID}){result->status=result;if(result.startsWith("Message envoyé")){draft="";thread=ThreadItem(dest,sentBody,System.currentTimeMillis(),thread!!.displayName);refresh()}}}
                        })
                }
            }
        }
    }
}

@Composable private fun Avatar(name:String,size:Int){
    val initials=name.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase().ifBlank{"?"}
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8DB8FF),Color(0xFF3769D8)))),contentAlignment=Alignment.Center){
        Text(initials,color=Color.White,fontSize=(size/2.5f).sp,fontWeight=FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun HomeScreen(mod:Modifier,ctx:Context,surface:Color,ink:Color,muted:Color,threads:List<ThreadItem>,contacts:List<ContactItem>,query:String,onQuery:(String)->Unit,onOpen:(ThreadItem)->Unit,onContact:(ContactItem)->Unit){
    var selectedThreads by remember{mutableStateOf(setOf<String>())}
    val normalizedQuery=query.filter{it.isDigit()}
    val matchingThreads=threads.filter{
        query.isBlank() || it.address.contains(query,true) || it.displayName.contains(query,true) ||
        it.body.contains(query,true) || (normalizedQuery.length>=2 && it.address.filter{ch->ch.isDigit()}.contains(normalizedQuery))
    }
    val matchingContacts=if(query.isBlank()) emptyList() else contacts.filter{
        it.name.contains(query,true) || it.number.contains(query,true) ||
        (normalizedQuery.length>=2 && it.number.filter{ch->ch.isDigit()}.contains(normalizedQuery))
    }.take(8)
    Column(mod.padding(horizontal=16.dp)){
        if(selectedThreads.isNotEmpty()){
            Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={selectedThreads=emptySet()}){Icon(Icons.Default.Close,null,tint=ink)}
                Text("${selectedThreads.size} sélectionnée(s)",color=ink,modifier=Modifier.weight(1f))
                IconButton(onClick={val t=threads.filter{selectedThreads.contains(it.address)}.joinToString("\n"){ "${it.displayName}: ${it.body}" };if(copyTextToClipboard(ctx,t))Toast.makeText(ctx,"Texte copié",Toast.LENGTH_SHORT).show() else Toast.makeText(ctx,"Copie impossible",Toast.LENGTH_SHORT).show()}){Icon(Icons.Default.ContentCopy,"Copier",tint=iBlue)}
                IconButton(onClick={val t=threads.filter{selectedThreads.contains(it.address)}.joinToString("\n"){ "${it.displayName}: ${it.body}" };shareTextWithChooser(ctx,t)}){Icon(Icons.Default.Share,"Partager",tint=iBlue)}
                IconButton(onClick={val count=selectedThreads.sumOf{deleteConversation(ctx,it)};selectedThreads=emptySet();Toast.makeText(ctx,if(count>0)"$count message(s) supprimé(s)" else "Suppression impossible : définis ORION comme application SMS par défaut",Toast.LENGTH_LONG).show()}){Icon(Icons.Default.Delete,"Supprimer",tint=Color(0xFFFF453A))}
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(surface==Color.White)Color(0xFFE5E5EA) else surface).padding(horizontal=12.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(Icons.Default.Search,null,tint=muted)
            Spacer(Modifier.width(8.dp))
            BasicTextField(value=query,onValueChange=onQuery,modifier=Modifier.weight(1f).padding(vertical=12.dp),singleLine=true,textStyle=TextStyle(color=ink,fontSize=16.sp),decorationBox={inner->if(query.isEmpty())Text("Rechercher",color=muted,fontSize=16.sp);inner()})
            if(query.isNotEmpty())IconButton(onClick={onQuery("")},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Close,null,tint=muted)}
        }
        if(query.isNotBlank()){
            Spacer(Modifier.height(14.dp))
            if(matchingContacts.isNotEmpty()){
                Text("CONTACTS",fontSize=11.sp,fontWeight=FontWeight.Bold,color=muted,letterSpacing=1.3.sp)
                matchingContacts.forEach{c->Row(Modifier.fillMaxWidth().clickable{onContact(c)}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
                    Avatar(c.name,42);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(c.name,color=ink,fontWeight=FontWeight.SemiBold);Text(c.number,color=muted,fontSize=13.sp)}
                    Icon(Icons.Default.Message,null,tint=iBlue)
                }}
                HorizontalDivider(color=muted.copy(alpha=.25f))
            }
            Text("DISCUSSIONS",fontSize=11.sp,fontWeight=FontWeight.Bold,color=muted,letterSpacing=1.3.sp,modifier=Modifier.padding(top=12.dp,bottom=4.dp))
        }else{
            Spacer(Modifier.height(18.dp))
            Text("DISCUSSIONS",fontSize=11.sp,fontWeight=FontWeight.Bold,color=muted,letterSpacing=1.3.sp)
        }
        if(matchingThreads.isEmpty() && matchingContacts.isEmpty()){
            Column(Modifier.fillMaxWidth().padding(top=55.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Box(Modifier.size(72.dp).clip(CircleShape).background(iBlue.copy(alpha=.12f)),contentAlignment=Alignment.Center){Icon(Icons.Default.ChatBubble,null,tint=iBlue,modifier=Modifier.size(32.dp))}
                Spacer(Modifier.height(14.dp));Text(if(query.isBlank())"Aucun message" else "Aucun résultat",fontWeight=FontWeight.SemiBold,color=ink,fontSize=18.sp)
                Text(if(query.isBlank())"Touchez le bouton pour commencer." else "Essaie un autre nom ou numéro.",color=muted,fontSize=13.sp)
            }
        }else LazyColumn(contentPadding=PaddingValues(bottom=100.dp)){
            items(matchingThreads){item->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick={if(selectedThreads.isNotEmpty())selectedThreads=if(selectedThreads.contains(item.address))selectedThreads-item.address else selectedThreads+item.address else onOpen(item)},onLongClick={selectedThreads=selectedThreads+item.address}).background(if(selectedThreads.contains(item.address))iBlue.copy(alpha=.10f) else Color.Transparent).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                    if(selectedThreads.contains(item.address))Icon(Icons.Default.CheckCircle,null,tint=iBlue,modifier=Modifier.size(50.dp).padding(12.dp)) else Avatar(item.displayName,50)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)){
                        Text(item.displayName,color=ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp));Text(item.body.ifBlank{"Message de ${item.displayName}"},color=muted,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ChevronRight,null,tint=muted)
                }
                HorizontalDivider(color=muted.copy(alpha=.2f),modifier=Modifier.padding(start=63.dp))
            }
        }
    }
}

@Composable private fun NewMessageScreen(mod:Modifier,contacts:List<ContactItem>,query:String,onQuery:(String)->Unit,onContact:(ContactItem)->Unit,onSubmit:(String)->Unit,notice:String,onNotice:(String)->Unit){
    val normalized=query.filter{it.isDigit()}
    val filtered=contacts.filter{query.isBlank()||it.name.contains(query,true)||it.number.contains(query,true)||(normalized.length>=2&&it.number.filter{ch->ch.isDigit()}.contains(normalized))}
    Column(mod.padding(horizontal=16.dp)){
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value=query,onValueChange=onQuery,modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Nom ou numéro de téléphone")},leadingIcon={Icon(Icons.Default.Search,null,tint=iBlue)},trailingIcon={if(query.isNotBlank())IconButton(onClick={onQuery("");onNotice("")}){Icon(Icons.Default.Close,"Effacer")}},shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={if(query.isNotBlank())onSubmit(query)}))
        Text("TOUS LES CONTACTS",fontSize=11.sp,fontWeight=FontWeight.Bold,color=iGray,letterSpacing=1.2.sp,modifier=Modifier.padding(top=22.dp,bottom=8.dp))
        if(notice.isNotBlank())Text(notice,color=Color(0xFFFF453A),fontSize=12.sp,modifier=Modifier.padding(vertical=6.dp))
        if(filtered.isEmpty()){
            Column(Modifier.fillMaxWidth().padding(top=48.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Icon(Icons.Default.PersonSearch,null,tint=iGray,modifier=Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if(query.isBlank())"Aucun contact disponible" else "Aucun contact trouvé",color=iGray,fontWeight=FontWeight.Medium)
                if(query.isNotBlank())Text("Appuie sur Entrée pour utiliser ce nom ou ce numéro.",color=iGray,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))
            }
        }else LazyColumn(contentPadding=PaddingValues(bottom=28.dp)){
            items(filtered,key={it.number}){contact->
                Row(Modifier.fillMaxWidth().clickable{onContact(contact)}.padding(vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
                    Avatar(contact.name,46)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){
                        Text(contact.name,color=Color.Unspecified,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                        Text(contact.number,color=iGray,fontSize=13.sp)
                    }
                    Icon(Icons.Default.Message,null,tint=iBlue)
                }
                HorizontalDivider(color=iGray.copy(alpha=.18f),modifier=Modifier.padding(start=58.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ChatScreen(mod:Modifier,item:ThreadItem,messages:List<SmsRecord>,font:FontFamily,fontWeight:FontWeight,number:String,onNumber:(String)->Unit,draft:String,onDraft:(String)->Unit,status:String,photoUri:Uri?,photoPreview:ImageBitmap?,onPickPhoto:()->Unit,onTakePhoto:()->Unit,onRemovePhoto:()->Unit,onSendPhoto:(Uri)->Unit,ink:Color,muted:Color,surface:Color,onProfile:()->Unit,onCall:()->Unit,onSend:()->Unit){
    val context=LocalContext.current
    var currentTime by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(60_000);currentTime=System.currentTimeMillis()}}
    var selectedMessages by remember{mutableStateOf(setOf<Long>())}
    val cannotReply=item.address.isNotBlank() && item.address.count{it.isDigit()} !in 7..15
    var attachMenu by remember{mutableStateOf(false)}
    var recorder by remember{mutableStateOf<MediaRecorder?>(null)}
    var recordingFile by remember{mutableStateOf<File?>(null)}
    var recording by remember{mutableStateOf(false)}
    fun startAudioRecording(){
        try{
            val file=File(context.cacheDir,"orion-audio-${System.currentTimeMillis()}.m4a")
            val media=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setOutputFile(file.absolutePath)
            media.prepare();media.start();recorder=media;recordingFile=file;recording=true
        }catch(e:Exception){Toast.makeText(context,"Impossible de démarrer l’enregistrement : ${e.localizedMessage}",Toast.LENGTH_LONG).show();recorder?.release();recorder=null;recording=false}
    }
    fun stopAndShareAudio(){
        val file=recordingFile
        try{recorder?.stop()}catch(_:Exception){}
        recorder?.release();recorder=null;recording=false;recordingFile=null
        if(file==null||!file.exists()||file.length()==0L){Toast.makeText(context,"Enregistrement audio vide.",Toast.LENGTH_SHORT).show();return}
        try{
            val values=ContentValues().apply{put(MediaStore.Audio.Media.DISPLAY_NAME,file.name);put(MediaStore.Audio.Media.MIME_TYPE,"audio/mp4");put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/ORION")}
            val uri=context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values) ?: throw IllegalStateException("Stockage audio indisponible")
            context.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{input->input.copyTo(out)}} ?: throw IllegalStateException("Impossible d’écrire le fichier audio")
            val share=Intent(Intent.ACTION_SEND).apply{type="audio/mp4";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            context.startActivity(Intent.createChooser(share,"Envoyer l’audio"))
        }catch(e:Exception){Toast.makeText(context,"Partage audio impossible : ${e.localizedMessage}",Toast.LENGTH_LONG).show()}
        file.delete()
    }
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)startAudioRecording() else Toast.makeText(context,"Autorisation du microphone refusée.",Toast.LENGTH_LONG).show()}
    Column(mod.padding(horizontal=14.dp)){
        if(selectedMessages.isNotEmpty()){
            Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={selectedMessages=emptySet()}){Icon(Icons.Default.Close,null,tint=ink)}
                Text("${selectedMessages.size} sélectionné(s)",color=ink,modifier=Modifier.weight(1f))
                IconButton(onClick={val t=messages.filter{selectedMessages.contains(it.id)}.joinToString("\n"){it.body};if(copyTextToClipboard(context,t))Toast.makeText(context,"Message(s) copié(s)",Toast.LENGTH_SHORT).show() else Toast.makeText(context,"Copie impossible",Toast.LENGTH_SHORT).show()}){Icon(Icons.Default.ContentCopy,"Copier",tint=iBlue)}
                IconButton(onClick={val t=messages.filter{selectedMessages.contains(it.id)}.joinToString("\n"){it.body};shareTextWithChooser(context,t)}){Icon(Icons.Default.Share,"Partager",tint=iBlue)}
                IconButton(onClick={val count=deleteSmsByIds(context,selectedMessages);selectedMessages=emptySet();Toast.makeText(context,if(count>0)"$count message(s) supprimé(s)" else "Suppression impossible : définis ORION comme application SMS par défaut",Toast.LENGTH_LONG).show()}){Icon(Icons.Default.Delete,"Supprimer",tint=Color(0xFFFF453A))}
            }
        }
        if(item.address.isBlank()){
            Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text("À :",color=muted,fontSize=16.sp);Spacer(Modifier.width(10.dp))
                OutlinedTextField(value=number,onValueChange=onNumber,modifier=Modifier.weight(1f),singleLine=true,placeholder={Text("Nom ou numéro")},shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=surface,focusedContainerColor=surface))
            }
            HorizontalDivider(color=muted.copy(alpha=.25f))
        }else{
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color=muted.copy(alpha=.3f),thickness=0.8.dp)
        }
        if(messages.isEmpty())Box(Modifier.weight(1f).fillMaxWidth().padding(top=14.dp),contentAlignment=Alignment.Center){
            Column(horizontalAlignment=Alignment.CenterHorizontally){Avatar(item.displayName,62);Spacer(Modifier.height(10.dp));Text(item.displayName,color=ink,fontWeight=FontWeight.SemiBold,fontSize=17.sp);Text(if(item.address.isBlank())"Nouveau message" else item.address,color=muted,fontSize=13.sp)}
        }else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top=12.dp),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            itemsIndexed(messages,key={_,message->message.id}){index,message->
                val outgoing=message.type!=Telephony.Sms.MESSAGE_TYPE_INBOX
                val newDay=index==0 || !sameCalendarDay(messages[index-1].date,message.date)
                if(newDay) Text(messageDayLabel(message.date,currentTime),color=muted,fontSize=12.sp,fontWeight=FontWeight.Medium,modifier=Modifier.fillMaxWidth().padding(vertical=7.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Column(Modifier.fillMaxWidth().combinedClickable(onClick={if(selectedMessages.isNotEmpty())selectedMessages=if(selectedMessages.contains(message.id))selectedMessages-message.id else selectedMessages+message.id},onLongClick={selectedMessages=selectedMessages+message.id}).background(if(selectedMessages.contains(message.id))iBlue.copy(alpha=.10f) else Color.Transparent).padding(vertical=2.dp),horizontalAlignment=if(outgoing)Alignment.End else Alignment.Start){
                    Box(Modifier.widthIn(max=310.dp).clip(if(outgoing)RoundedCornerShape(21.dp,21.dp,5.dp,21.dp) else RoundedCornerShape(21.dp,21.dp,21.dp,5.dp)).background(if(outgoing)iBlue else surface).padding(horizontal=16.dp,vertical=11.dp)){
                        Text(message.body,color=if(outgoing)Color.White else ink,fontSize=16.sp,lineHeight=22.sp,fontFamily=font,fontWeight=fontWeight)
                    }
                    Text(messageTimeLabel(message.date),color=muted,fontSize=10.sp,modifier=Modifier.padding(top=3.dp,start=5.dp,end=5.dp))
                    if(outgoing)Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=1.dp,end=5.dp)){
                        Text(if(message.type==Telephony.Sms.MESSAGE_TYPE_FAILED)"Échec" else "Envoyé",color=muted,fontSize=10.sp)
                        Spacer(Modifier.width(5.dp))
                        Text(if(message.type==Telephony.Sms.MESSAGE_TYPE_FAILED)"!" else if(message.status==Telephony.Sms.STATUS_COMPLETE)"✓✓" else "✓",color=if(message.status==Telephony.Sms.STATUS_COMPLETE) iBlue else muted,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
        if(photoUri!=null){
            Row(Modifier.fillMaxWidth().padding(bottom=9.dp).clip(RoundedCornerShape(16.dp)).background(surface).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
                if(photoPreview!=null)Image(photoPreview,contentDescription="Aperçu de la photo",modifier=Modifier.size(68.dp).clip(RoundedCornerShape(11.dp)),contentScale=ContentScale.Crop)
                else Box(Modifier.size(68.dp).clip(RoundedCornerShape(11.dp)).background(muted.copy(alpha=.2f)),contentAlignment=Alignment.Center){Icon(Icons.Default.Image,null,tint=muted)}
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)){Text("Photo jointe",color=ink,fontWeight=FontWeight.SemiBold,fontSize=14.sp);Text("Prête à être envoyée par MMS",color=muted,fontSize=11.sp)}
                IconButton(onClick=onRemovePhoto){Icon(Icons.Default.Close,"Retirer la photo",tint=muted)}
            }
        }
        if(recording)Text("Enregistrement en cours… Touchez le bouton rouge pour terminer et partager.",color=Color(0xFFFF453A),fontSize=11.sp,modifier=Modifier.padding(horizontal=5.dp,vertical=5.dp))
        if(status.isNotBlank())Text(status,color=muted,fontSize=11.sp,modifier=Modifier.padding(horizontal=5.dp,vertical=5.dp))
        if(cannotReply){
            HorizontalDivider(color=muted.copy(alpha=.25f))
            Text("Impossible de répondre à cet expéditeur : ce numéro court ou identifiant ne reçoit pas les SMS.",color=muted,fontSize=13.sp,modifier=Modifier.fillMaxWidth().padding(vertical=16.dp))
        }else{
        HorizontalDivider(color=muted.copy(alpha=.25f))
        Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=8.dp),verticalAlignment=Alignment.Bottom){
            Box {
                IconButton(onClick=onPickPhoto,modifier=Modifier.size(44.dp).clip(CircleShape).background(surface)){Icon(Icons.Default.AddPhotoAlternate,"Joindre une photo",tint=iBlue,modifier=Modifier.size(23.dp))}
                DropdownMenu(expanded=attachMenu,onDismissRequest={attachMenu=false}){
                    DropdownMenuItem(text={Text("Prendre une photo")},leadingIcon={Icon(Icons.Default.CameraAlt,null)},onClick={attachMenu=false;onTakePhoto()})
                    DropdownMenuItem(text={Text("Choisir dans la galerie")},leadingIcon={Icon(Icons.Default.PhotoLibrary,null)},onClick={attachMenu=false;onPickPhoto()})
                }
            }
            Spacer(Modifier.width(7.dp))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(23.dp)).background(surface).padding(start=15.dp,end=6.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                BasicTextField(value=draft,onValueChange=onDraft,modifier=Modifier.weight(1f).heightIn(min=36.dp,max=112.dp).padding(vertical=7.dp),textStyle=TextStyle(color=ink,fontSize=16.sp,lineHeight=21.sp,fontFamily=font),decorationBox={inner->if(draft.isEmpty())Text("SMS · Message texte",color=muted,fontSize=15.sp);inner()})
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick={if(recording)stopAndShareAudio() else micPermission.launch(Manifest.permission.RECORD_AUDIO)},modifier=Modifier.size(42.dp).clip(CircleShape).background(if(recording)Color(0xFFFF3B30) else surface)){
                Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,if(recording)"Arrêter et partager l’audio" else "Enregistrer un audio",tint=if(recording)Color.White else iBlue,modifier=Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick={if(photoUri!=null)onSendPhoto(photoUri) else onSend()},enabled=draft.isNotBlank()||photoUri!=null,modifier=Modifier.size(46.dp).clip(CircleShape).background(if(draft.isNotBlank()||photoUri!=null)iBlue else muted.copy(alpha=.28f))){
                Icon(Icons.Default.Send,"Envoyer",tint=Color.White,modifier=Modifier.size(22.dp))
            }
        }
        }
    }
}

@Composable private fun ProfileScreen(mod:Modifier,item:ThreadItem,contacts:List<ContactItem>,ink:Color,muted:Color,surface:Color,ctx:Context,call:(String)->Unit,onMessage:()->Unit,onBlockResult:(String)->Unit){
    val number=item.address
    var blocked by remember(number){mutableStateOf(runCatching{BlockedNumberContract.canCurrentUserBlockNumbers(ctx) && BlockedNumberContract.isBlocked(ctx,number)}.getOrDefault(false))}
    val contact=contacts.firstOrNull{normalizePhone(it.number)==normalizePhone(number)}
    Column(mod.fillMaxSize().padding(horizontal=18.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(108.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF9CC5FF),Color(0xFF3269D6)))),contentAlignment=Alignment.Center){
            Text(item.displayName.take(1).uppercase(),color=Color.White,fontSize=43.sp,fontWeight=FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp));Text(item.displayName,fontSize=24.sp,fontWeight=FontWeight.Bold,color=ink)
        Text(number,color=muted,fontSize=14.sp)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            ProfileAction("message",Icons.Default.Message,Modifier.weight(1f),surface,ink){onMessage()}
            ProfileAction("appeler",Icons.Default.Call,Modifier.weight(1f),surface,ink){call(number)}
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            ProfileAction(if(contact==null)"Enregistrer" else "Ajouter contact",Icons.Default.PersonAdd,Modifier.weight(1f),surface,ink){
                val i=Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE).apply{putExtra(ContactsContract.Intents.Insert.PHONE,number);if(contact!=null)putExtra(ContactsContract.Intents.Insert.NAME,contact.name)}
                runCatching{ctx.startActivity(i)}.onFailure{onBlockResult("Impossible d'ouvrir les contacts")}
            }
            ProfileAction("Partager",Icons.Default.Share,Modifier.weight(1f),surface,ink){shareTextWithChooser(ctx,number)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            ProfileAction("WhatsApp",Icons.Default.Chat,Modifier.weight(1f),surface,ink){runCatching{ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+number.filter{it.isDigit()})))}}
            ProfileAction("E-mail",Icons.Default.Email,Modifier.weight(1f),surface,ink){val i=Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")).putExtra(Intent.EXTRA_TEXT,number);runCatching{ctx.startActivity(Intent.createChooser(i,"Envoyer par e-mail"))}}
        }
        Spacer(Modifier.height(25.dp));HorizontalDivider(color=muted.copy(alpha=.3f))
        Column(Modifier.fillMaxWidth().padding(vertical=17.dp)){
            Text("INFORMATIONS",fontSize=11.sp,color=muted,fontWeight=FontWeight.Bold,letterSpacing=1.2.sp)
            Spacer(Modifier.height(14.dp))
            Text("Téléphone",fontSize=12.sp,color=muted);Text(number,fontSize=16.sp,color=ink,modifier=Modifier.padding(top=4.dp))
            if(contact!=null){Spacer(Modifier.height(14.dp));Text("Nom dans les contacts",fontSize=12.sp,color=muted);Text(contact.name,fontSize=16.sp,color=ink,modifier=Modifier.padding(top=4.dp))}
        }
        HorizontalDivider(color=muted.copy(alpha=.3f))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(surface).clickable{
            runCatching{
                if(!BlockedNumberContract.canCurrentUserBlockNumbers(ctx)) throw SecurityException("Le blocage Android n'est pas disponible pour cet utilisateur.")
                if(blocked){
                    val removed=BlockedNumberContract.unblock(ctx,number)
                    if(removed<=0) throw IllegalStateException("Android n'a pas supprimé ce numéro.")
                }else{
                    val uri=ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI,android.content.ContentValues().apply{put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,number)})
                    if(uri==null) throw IllegalStateException("Android a refusé l'ajout du numéro.")
                }
                val actual=BlockedNumberContract.isBlocked(ctx,number)
                if(actual==blocked) throw IllegalStateException("Android n'a pas confirmé le changement.")
                blocked=actual
                onBlockResult(if(actual)"Numéro bloqué par Android." else "Numéro débloqué.")
            }.onFailure{error->onBlockResult("Échec du blocage : ${error.localizedMessage ?: "Android a refusé l'opération"}. Vérifie qu'ORION est l'application SMS par défaut.")}
        }.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(if(blocked)Icons.Default.CheckCircle else Icons.Default.Block,null,tint=if(blocked)Color(0xFF34C759) else Color(0xFFFF453A))
            Spacer(Modifier.width(12.dp));Text(if(blocked)"Débloquer ce correspondant" else "Bloquer ce correspondant",color=if(blocked)Color(0xFFFF453A) else ink,fontSize=15.sp)
        }
        Spacer(Modifier.height(8.dp));Text("Le blocage dépend des fonctions de blocage prises en charge par Android sur cet appareil.",fontSize=11.sp,color=muted)
    }
}

@Composable private fun ProfileAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,surface:Color,ink:Color,onClick:()->Unit){
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(surface).clickable(onClick=onClick).padding(vertical=13.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Icon(icon,null,tint=iBlue,modifier=Modifier.size(23.dp));Spacer(Modifier.height(5.dp));Text(label,color=ink,fontSize=13.sp)
    }
}

@Composable private fun SettingsScreen(mod:Modifier,ctx:Context,surface:Color,ink:Color,muted:Color,dark:Boolean,onDark:(Boolean)->Unit,sims:List<SimChoice>,selected:Int,onSim:(Int)->Unit,fontChoice:String,onFont:(String)->Unit,onDefault:()->Unit,onAbout:()->Unit){
    Column(mod.padding(horizontal=16.dp)){
        Spacer(Modifier.height(16.dp));SectionTitle("APPLICATION",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(iBlue),contentAlignment=Alignment.Center){Icon(Icons.Default.Sms,null,tint=Color.White)}
                Spacer(Modifier.width(12.dp));Column{Text("ORION Messages",color=ink,fontWeight=FontWeight.SemiBold);Text("Messagerie SMS",color=muted,fontSize=12.sp)}}
            Spacer(Modifier.height(16.dp));Button(onClick=onDefault,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp)){Text("Définir comme application SMS par défaut")}
        }
        Spacer(Modifier.height(22.dp));SectionTitle("CARTES SIM",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Text("SIM pour les nouveaux messages",color=ink,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp))
            if(sims.isEmpty())Text("Aucune SIM détectée. Vérifie que ta SIM est active.",color=muted,fontSize=13.sp)
            sims.forEach{entry->Row(Modifier.fillMaxWidth().clickable{onSim(entry.id)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(entry.label,color=ink);Text("Emplacement ${entry.slot+1}",color=muted,fontSize=11.sp)}
                if(selected==entry.id)Icon(Icons.Default.Check,null,tint=iBlue)
            }}
        }
        Spacer(Modifier.height(22.dp));SectionTitle("APPARENCE",muted)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text("Mode sombre",color=ink,modifier=Modifier.weight(1f));Switch(dark,onDark)}
        Spacer(Modifier.height(22.dp));SectionTitle("POLICE D’ÉCRITURE",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Text("Choisir la police des messages",color=ink,fontWeight=FontWeight.SemiBold)
            Text("Aperçu : Aa Bonjour ! 012345",color=ink,fontSize=21.sp,fontFamily=fontFamilyForChoice(ctx,fontChoice),modifier=Modifier.padding(vertical=12.dp))
            val choices=listOf("System","San Francisco","Roboto","Rounded","Handwritten","Elegant","Typewriter","Condensed","Lato","Pacifico","Dancing Script","Light","Bold")
            choices.forEach{choice->Row(Modifier.fillMaxWidth().clickable{onFont(choice)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(when(choice){"System"->"Police du système";"Handwritten"->"Manuscrite / stylo";"Elegant"->"Élégante";"Typewriter"->"Machine à écrire";"Pacifico"->"Manuscrite — Pacifico";"Dancing Script"->"Manuscrite — Dancing Script";"Lato"->"Lato";else->choice},color=ink,fontFamily=fontFamilyForChoice(ctx,choice),fontWeight=if(choice=="Bold")FontWeight.Bold else FontWeight.Normal);Text("Aa Bonjour ! 012345",color=muted,fontSize=12.sp,fontFamily=fontFamilyForChoice(ctx,choice))}
                if(fontChoice==choice)Icon(Icons.Default.Check,null,tint=iBlue)
            }}
        }
        Spacer(Modifier.height(22.dp));SectionTitle("À PROPOS",muted)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).clickable(onClick=onAbout).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,tint=iBlue);Spacer(Modifier.width(12.dp));Text("À propos d’ORION",color=ink,modifier=Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null,tint=muted)}
        Spacer(Modifier.height(22.dp));SectionTitle("CONFIDENTIALITÉ",muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(16.dp)){
            Text("Noms de tes contacts",color=ink,fontWeight=FontWeight.SemiBold)
            Text("ORION utilise l'autorisation Contacts pour associer les numéros aux noms enregistrés dans ton carnet d'adresses.",color=muted,fontSize=12.sp)
        }
    }
}
@Composable private fun AboutScreen(mod:Modifier,surface:Color,ink:Color,muted:Color){
    Column(mod.padding(horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Spacer(Modifier.height(38.dp))
        Box(Modifier.size(94.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF68A8FF),Color(0xFF0756C9)))),contentAlignment=Alignment.Center){
            Icon(Icons.Default.Sms,null,tint=Color.White,modifier=Modifier.size(48.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("ORION Messages",fontSize=25.sp,fontWeight=FontWeight.Bold,color=ink)
        Text("Messagerie SMS Android",fontSize=14.sp,color=muted)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(surface).padding(18.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Version",color=ink,modifier=Modifier.weight(1f))
                Text("1.0",color=muted,fontWeight=FontWeight.SemiBold)
            }
            HorizontalDivider(Modifier.padding(vertical=13.dp),color=muted.copy(alpha=.25f))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Application",color=ink,modifier=Modifier.weight(1f))
                Text("ORION",color=muted)
            }
            HorizontalDivider(Modifier.padding(vertical=13.dp),color=muted.copy(alpha=.25f))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Type",color=ink,modifier=Modifier.weight(1f))
                Text("SMS classiques via carte SIM",color=muted,fontSize=12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("ORION Messages — une messagerie conçue pour Android.",color=muted,fontSize=12.sp)
    }
}
@Composable private fun SectionTitle(value:String,color:Color){Text(value,color=color,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp);Spacer(Modifier.height(9.dp))}


private fun fontFamilyForChoice(ctx:Context,choice:String):FontFamily{
    val id=when(choice){
        "San Francisco"->R.font.inter
        "Roboto"->R.font.roboto
        "Rounded"->R.font.nunito
        "Handwritten"->R.font.caveat
        "Elegant"->R.font.playfair_display
        "Typewriter"->R.font.source_code_pro
        "Condensed"->R.font.oswald
        "Lato"->R.font.lato
        "Pacifico"->R.font.pacifico
        "Dancing Script"->R.font.dancing_script
        else->return FontFamily.Default
    }
    return runCatching{FontFamily(Font(id))}.getOrDefault(FontFamily.Default)
}
private fun sameCalendarDay(first:Long,second:Long):Boolean{
    val a=java.util.Calendar.getInstance().apply{timeInMillis=first}
    val b=java.util.Calendar.getInstance().apply{timeInMillis=second}
    return a.get(java.util.Calendar.YEAR)==b.get(java.util.Calendar.YEAR)&&a.get(java.util.Calendar.DAY_OF_YEAR)==b.get(java.util.Calendar.DAY_OF_YEAR)
}
private fun messageDayLabel(timestamp:Long,now:Long):String{
    val today=java.util.Calendar.getInstance().apply{timeInMillis=now}
    val date=java.util.Calendar.getInstance().apply{timeInMillis=timestamp}
    if(sameCalendarDay(timestamp,now))return "Aujourd’hui"
    today.add(java.util.Calendar.DAY_OF_YEAR,-1)
    if(sameCalendarDay(timestamp,today.timeInMillis))return "Hier"
    return java.text.SimpleDateFormat("d MMMM yyyy",java.util.Locale.FRENCH).format(java.util.Date(timestamp))
}
private fun messageTimeLabel(timestamp:Long):String=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(timestamp))

private fun openDialer(ctx:Context,number:String){
    if(number.isBlank())return
    runCatching{ctx.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))))}
}

@SuppressLint("MissingPermission") private fun readSims(ctx:Context):List<SimChoice>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    return try{ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map{SimChoice(it.subscriptionId,it.simSlotIndex,it.displayName?.toString()?.takeIf{s->s.isNotBlank()}?:"SIM ${it.simSlotIndex+1}")}?.sortedBy{it.slot}?:emptyList()}catch(_:Exception){emptyList()}
}
@SuppressLint("MissingPermission") private fun readContacts(ctx:Context):List<ContactItem>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    val out=linkedMapOf<String,ContactItem>()
    try{ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE LOCALIZED ASC")?.use{c->
        val ni=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);val di=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        while(c.moveToNext()){val n=if(ni>=0)c.getString(ni) else null;val d=if(di>=0)c.getString(di) else null
            if(!n.isNullOrBlank()&&!d.isNullOrBlank())out.putIfAbsent(normalizePhone(n),ContactItem(d,n))}
    }}catch(_:Exception){}
    return out.values.toList()
}
private fun readMessages(ctx:Context,address:String):List<SmsRecord>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    val out=mutableListOf<SmsRecord>()
    try{ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms._ID,Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.TYPE,Telephony.Sms.STATUS),null,null,Telephony.Sms.DATE+" ASC")?.use{c->
        val id=c.getColumnIndex(Telephony.Sms._ID);val ai=c.getColumnIndex(Telephony.Sms.ADDRESS);val bi=c.getColumnIndex(Telephony.Sms.BODY);val di=c.getColumnIndex(Telephony.Sms.DATE);val ti=c.getColumnIndex(Telephony.Sms.TYPE);val si=c.getColumnIndex(Telephony.Sms.STATUS)
        while(c.moveToNext()&&out.size<300){val a=if(ai>=0)c.getString(ai).orEmpty() else "";if(normalizePhone(a)==normalizePhone(address)){out.add(SmsRecord(if(id>=0)c.getLong(id) else out.size.toLong(),if(bi>=0)c.getString(bi).orEmpty() else "",if(di>=0)c.getLong(di) else 0L,if(ti>=0)c.getInt(ti) else Telephony.Sms.MESSAGE_TYPE_INBOX,if(si>=0)c.getInt(si) else Telephony.Sms.STATUS_NONE))}}
    }}catch(_:Exception){}
    return out
}

private fun readThreads(ctx:Context):List<ThreadItem>{
    if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
    val names=readContacts(ctx).associate{normalizePhone(it.number) to it.name}
    val out=mutableListOf<ThreadItem>()
    try{ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE),null,null,Telephony.Sms.DATE+" DESC")?.use{c->
        val ai=c.getColumnIndex(Telephony.Sms.ADDRESS);val bi=c.getColumnIndex(Telephony.Sms.BODY);val di=c.getColumnIndex(Telephony.Sms.DATE);val seen=mutableSetOf<String>()
        while(c.moveToNext()&&out.size<100){val a=if(ai>=0)c.getString(ai)?:"Inconnu" else "Inconnu";if(seen.add(a))out.add(ThreadItem(a,if(bi>=0)c.getString(bi)?:"" else "",if(di>=0)c.getLong(di) else 0L,names[normalizePhone(a)]?:senderLabel(a)))}
    }}catch(_:Exception){}
    return out
}

private fun copyTextToClipboard(ctx:Context,text:String):Boolean{
    if(text.isBlank())return false
    val clipboard=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("ORION Messages",text))
    return true
}
private fun deleteSmsByIds(ctx:Context,ids:Set<Long>):Int{
    if(Telephony.Sms.getDefaultSmsPackage(ctx)!=ctx.packageName)return 0
    var deleted=0
    ids.forEach{id->deleted+=runCatching{ctx.contentResolver.delete(Uri.parse("content://sms/$id"),null,null)}.getOrDefault(0)}
    return deleted
}
private fun deleteConversation(ctx:Context,address:String):Int{
    if(Telephony.Sms.getDefaultSmsPackage(ctx)!=ctx.packageName)return 0
    val ids=mutableListOf<Long>()
    runCatching{ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms._ID,Telephony.Sms.ADDRESS),null,null,null)?.use{c->
        val idCol=c.getColumnIndex(Telephony.Sms._ID);val addrCol=c.getColumnIndex(Telephony.Sms.ADDRESS)
        while(c.moveToNext()){val addr=if(addrCol>=0)c.getString(addrCol).orEmpty() else "";if(normalizePhone(addr)==normalizePhone(address)&&idCol>=0)ids.add(c.getLong(idCol))}
    }}
    return deleteSmsByIds(ctx,ids.toSet())
}
private fun shareTextWithChooser(ctx:Context,text:String){
    if(text.isBlank())return
    val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text)
    val whatsappTargets=listOf("com.whatsapp","com.whatsapp.w4b").mapNotNull{packageName->
        val target=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text).setPackage(packageName)
        if(runCatching{target.resolveActivity(ctx.packageManager)!=null}.getOrDefault(false))target else null
    }
    val chooser=Intent.createChooser(intent,"Choisir WhatsApp, WhatsApp Business ou une autre application")
    if(whatsappTargets.isNotEmpty())chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,whatsappTargets.toTypedArray())
    runCatching{ctx.startActivity(chooser)}.onFailure{Toast.makeText(ctx,"Aucune application de partage disponible.",Toast.LENGTH_LONG).show()}
}

private fun senderLabel(address:String):String=if(address.trim().equals("PCB",ignoreCase=true)||address.trim().equals("BCB",ignoreCase=true)) "Connect" else address
private fun normalizePhone(value:String):String=value.filter{it.isDigit()}
