@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:Suppress("DEPRECATION")
package com.ghost.smshelper

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InboxScreen(sims:List<SimInfo>,selectedSim:SimInfo?,onReply:(String,Int,String)->Boolean,onSecret:()->Unit) {
    val context=LocalContext.current
    val colors=AppTheme.colors
    val clipboard=LocalClipboardManager.current
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    val messages by SmsRepository.messages.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var simFilter by rememberSaveable { mutableIntStateOf(-1) }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var conversations by rememberSaveable { mutableStateOf(true) }
    var conversation by rememberSaveable { mutableStateOf<String?>(null) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyId by rememberSaveable { mutableStateOf<String?>(null) }
    val reply=messages.find { it.id==replyId }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var exportToken by rememberSaveable { mutableStateOf<String?>(null) }
    val listState=rememberLazyListState()
    val currentDetail=messages.find { it.id==detailId }
    fun conversationKey(msg:SmsItem)="${msg.subId}:${msg.sender}"
    val filtered=messages.filter { msg ->
        (search.isBlank() || msg.body.contains(search,true) || msg.sender.contains(search,true)) &&
            (number.isBlank() || msg.sender.contains(number)) && (date.isBlank() || msg.time.contains(date)) &&
            (simFilter==-1 || if(simFilter==-2)msg.subId==null else msg.subId==simFilter) &&
            (!unreadOnly || !msg.read) && (!starredOnly || msg.starred) &&
            (conversation==null || conversationKey(msg)==conversation)
    }
    val groups=filtered.groupBy(::conversationKey).values.toList()
    val grouped=conversations && conversation==null && !selecting
    LaunchedEffect(messages) { selected=selected.intersect(messages.map{it.id}.toSet()) }
    BackHandler(enabled=conversation!=null || selecting) { if(selecting){selected=emptySet();selecting=false}else conversation=null }
    fun updateMessage(action:()->Unit):Boolean = runCatching(action).onFailure { Toast.makeText(context,it.message?:"保存失败，请检查可用存储空间",Toast.LENGTH_LONG).show() }.isSuccess
    fun open(msg:SmsItem) { detailId=msg.id;updateMessage { SmsRepository.markRead(context,setOf(msg.id)) } }
    fun share(items:List<SmsItem>) {
        val text=items.joinToString("\n\n") { "${it.sender} · ${it.time}\n${it.body}" }
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"分享短信")) }
            .onFailure { Toast.makeText(context,"没有可用的分享应用",Toast.LENGTH_SHORT).show() }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri->
        val token=exportToken
        exportToken=null
        if(token!=null)scope.launch {
            val success=withContext(Dispatchers.IO) { runCatching {
                if(uri!=null) {
                    val snapshot=InboxExportCache.read(context,token)
                    context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(snapshot.toByteArray(Charsets.UTF_8))} ?: error("无法打开文件")
                }
            }.also { InboxExportCache.remove(context,token) }.isSuccess }
            if(uri!=null)Toast.makeText(context,if(success)"短信已导出"else"导出失败，请重新选择短信和保存位置",Toast.LENGTH_LONG).show()
        }
    }
    fun export(items:List<SmsItem>) {scope.launch {
        val token=withContext(Dispatchers.IO){runCatching{InboxExportCache.prepare(context,items)}.getOrNull()}
        if(token==null)Toast.makeText(context,"无法准备导出，请检查可用存储空间",Toast.LENGTH_LONG).show()
        else{exportToken=token;exporter.launch("SmsHelper-selected-${System.currentTimeMillis()}.txt")}
    }}
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),singleLine=true,placeholder={Text("搜索短信和号码")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={HapticIconButton(onClick={filtersOpen=true}){Icon(Icons.Default.List,"筛选号码、日期和 SIM")}})
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                HapticFilterChip(selected=conversations,onClick={conversations=!conversations;conversation=null},label={Text(if(conversations)"会话视图"else"全部消息")})
                HapticFilterChip(selected=unreadOnly,onClick={unreadOnly=!unreadOnly},label={Text("未读")})
                HapticFilterChip(selected=starredOnly,onClick={starredOnly=!starredOnly},label={Text("收藏")})
                HapticFilterChip(selected=selecting,onClick={selecting=!selecting;selected=emptySet()},label={Text("多选")})
                HapticAssistChip(onClick=onSecret,label={Text("信箱设置")})
            }
            if(conversation!=null)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                HapticIconButton(onClick={conversation=null}){Icon(Icons.Default.ArrowBack,"返回会话")}
                Text(filtered.firstOrNull()?.sender ?: "会话",fontWeight=FontWeight.Bold,color=colors.text)
            }
            if(number.isNotBlank() || date.isNotBlank() || simFilter!=-1)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(listOf(number,date,if(simFilter==-2)"未分卡" else sims.find{it.subId==simFilter}?.let{"卡${it.slotIndex+1}"}.orEmpty()).filter(String::isNotBlank).joinToString(" · "),Modifier.weight(1f),fontSize=12.sp,color=colors.mutedText)
                HapticTextButton(onClick={number="";date="";simFilter=-1}){Text("清除筛选")}
            }
            if(selecting)Column(Modifier.fillMaxWidth().background(colors.surfaceAlt).padding(horizontal=16.dp,vertical=6.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 条",fontWeight=FontWeight.Bold)
                    HapticTextButton(onClick={selected=if(selected.containsAll(filtered.map{it.id}))emptySet()else filtered.map{it.id}.toSet()}){Text("全选 / 取消")}
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    HapticTextButton(enabled=selected.isNotEmpty(),onClick={updateMessage { SmsRepository.markRead(context,selected) };selecting=false;selected=emptySet()}){Text("设为已读")}
                    HapticTextButton(enabled=selected.isNotEmpty(),onClick={updateMessage { SmsRepository.markRead(context,selected,false) };selecting=false;selected=emptySet()}){Text("设为未读")}
                    HapticTextButton(enabled=selected.isNotEmpty(),onClick={export(messages.filter{it.id in selected})}){Text("导出")}
                    HapticTextButton(enabled=selected.isNotEmpty(),onClick={deleteIds=selected}){Text("删除",color=colors.danger)}
                }
            }
            Box(Modifier.weight(1f)) {
                if(filtered.isEmpty())Box(Modifier.fillMaxSize().padding(32.dp),contentAlignment=Alignment.Center){Text(if(messages.isEmpty())"信箱空空如也～\n查询后，收到的回复会来到这里。"else"没有匹配的短信，试试调整筛选。",color=colors.mutedText)}
                LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=20.dp,top=8.dp,bottom=84.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(grouped)items(groups,key={conversationKey(it.first())}) { thread ->
                        val first=thread.first();val unread=thread.count{!it.read}
                        CartoonCard(Modifier.fillMaxWidth(),backgroundColor=colors.inboxReceived,onClick={conversation=conversationKey(first)},onLongClick={selecting=true;selected=thread.map{it.id}.toSet()}) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(first.sender,fontWeight=FontWeight.Black,color=colors.text);if(unread>0)Badge{Text("$unread 未读")}}
                            Text(first.body,maxLines=2,color=colors.text)
                            Text("${simLabel(first.subId,sims)} · ${first.time} · ${thread.size} 条",fontSize=11.sp,color=colors.mutedText)
                        }}
                    } else items(filtered,key={it.id}) { msg ->
                        CartoonCard(Modifier.fillMaxWidth(),backgroundColor=if(msg.id in selected)colors.navSelected else colors.inboxReceived,onClick={if(selecting)selected=if(msg.id in selected)selected-msg.id else selected+msg.id else open(msg)},onLongClick={if(selecting)selected=selected+msg.id else if(msg.sender!="系统")replyId=msg.id}) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text("${if(!msg.read)"● "else""}${msg.sender}",Modifier.weight(1f),fontWeight=FontWeight.Black,color=colors.text)
                                if(selecting)Checkbox(msg.id in selected,{checked->selected=if(checked)selected+msg.id else selected-msg.id})
                                else HapticIconButton(onClick={updateMessage { SmsRepository.toggleStar(context,msg.id) }},modifier=Modifier.size(48.dp)){Icon(Icons.Default.Star,if(msg.starred)"取消收藏"else"收藏",tint=if(msg.starred)colors.primary else colors.mutedText.copy(alpha=.45f))}
                            }
                            Text(msg.body,color=colors.text,lineHeight=23.sp)
                            Text("${simLabel(msg.subId,sims)} · ${msg.time}",fontSize=11.sp,color=colors.mutedText)
                        }}
                    }
                }
                if(filtered.isNotEmpty()) {
                    val itemCount=if(grouped)groups.size else filtered.size
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp).pointerInput(itemCount){detectVerticalDragGestures{change,_->change.consume();val index=((change.position.y/size.height)*itemCount).toInt().coerceIn(0,itemCount-1);scope.launch{listState.scrollToItem(index)}}}) {
                        Box(Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight(.6f).padding(vertical=8.dp).background(colors.outline.copy(alpha=.25f),RoundedCornerShape(4.dp)))
                    }
                }
                if(listState.firstVisibleItemIndex>0)SmallFloatingActionButton(onClick={scope.launch{listState.animateScrollToItem(0)}},modifier=Modifier.align(Alignment.BottomEnd).padding(18.dp),containerColor=colors.primary,contentColor=colors.onPrimary){Icon(Icons.Default.KeyboardArrowUp,"回到顶部")}
            }
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    if(filtersOpen)AlertDialog(onDismissRequest={filtersOpen=false},containerColor=colors.dialog,title={Text("筛选短信")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(number,{number=it},label={Text("号码包含")},singleLine=true)
        OutlinedTextField(date,{date=it},label={Text("日期，如 2026-10 或 2026-10-13")},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            HapticFilterChip(simFilter==-1,{simFilter=-1},label={Text("全部 SIM")});HapticFilterChip(simFilter==-2,{simFilter=-2},label={Text("未分卡")})
            sims.forEach{sim->HapticFilterChip(simFilter==sim.subId,{simFilter=sim.subId},label={Text("卡${sim.slotIndex+1}")})}
        }
    }},confirmButton={HapticTextButton(onClick={filtersOpen=false}){Text("应用")}},dismissButton={HapticTextButton(onClick={search="";number="";date="";simFilter=-1;unreadOnly=false;starredOnly=false;filtersOpen=false}){Text("清除全部")}})
    currentDetail?.let{msg->AlertDialog(onDismissRequest={detailId=null},containerColor=colors.dialog,title={Text(msg.sender)},text={LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("${simLabel(msg.subId,sims)} · ${msg.time}",fontSize=12.sp,color=colors.mutedText);Text(msg.body,Modifier.padding(top=10.dp),lineHeight=24.sp)}
        item{Row(Modifier.horizontalScroll(rememberScrollState())) {
            HapticTextButton(onClick={clipboard.setText(AnnotatedString(msg.body));Toast.makeText(context,"已复制",Toast.LENGTH_SHORT).show()}){Text("复制")}
            HapticTextButton(onClick={share(listOf(msg))}){Text("分享")}
            HapticTextButton(onClick={export(listOf(msg))}){Text("导出")}
            HapticTextButton(onClick={updateMessage { SmsRepository.toggleStar(context,msg.id) }}){Text(if(msg.starred)"取消收藏"else"收藏")}
        }}
        item{Row{HapticTextButton(onClick={updateMessage { SmsRepository.markRead(context,setOf(msg.id),false) };detailId=null}){Text("设为未读")};HapticTextButton(onClick={deleteIds=setOf(msg.id)}){Text("删除",color=colors.danger)}}}
    }},confirmButton={if(msg.sender!="系统")HapticTextButton(onClick={replyId=msg.id;detailId=null}){Text("回复")}},dismissButton={HapticTextButton(onClick={detailId=null}){Text("关闭")}})}
    reply?.let{msg->ReplyDialog(msg,sims,selectedSim,onDismiss={replyId=null},onSend={text,sub,target->if(onReply(text,sub,target)){replyId=null;true}else false})}
    deleteIds?.let{ids->AlertDialog(onDismissRequest={deleteIds=null},containerColor=colors.dialog,title={Text("删除 ${ids.size} 条短信？")},text={Text("只删除本应用保存的记录，不影响系统短信。删除后可撤销。")},confirmButton={HapticTextButton(onClick={
        val token=runCatching { SmsRepository.deleteMessages(context,ids) }.onFailure { Toast.makeText(context,it.message?:"删除失败",Toast.LENGTH_LONG).show() }.getOrNull()
        if(token!=null) {deleteIds=null;detailId=null;selected=emptySet();selecting=false
            scope.launch{if(snackbar.showSnackbar("已删除 ${ids.size} 条短信",actionLabel="撤销",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed)updateMessage { check(SmsRepository.undoDelete(context,token)) {"无法撤销，请检查存储空间"} }}
        }
    }){Text("删除",color=colors.danger)}},dismissButton={HapticTextButton(onClick={deleteIds=null}){Text("取消")}})}
}

@Composable
private fun ReplyDialog(message:SmsItem,sims:List<SimInfo>,selectedSim:SimInfo?,onDismiss:()->Unit,onSend:(String,Int,String)->Boolean) {
    val colors=AppTheme.colors
    var text by rememberSaveable(message.id){mutableStateOf("")}
    var sub by rememberSaveable(message.id){mutableIntStateOf(message.subId?:selectedSim?.subId?:-1)}
    var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.imePadding(),containerColor=colors.dialog,title={Text("回复 ${message.sender}")},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(if(message.subId==null)"旧消息没有接收卡记录，请确认发送 SIM。"else"默认使用收到这条短信的 SIM。",fontSize=12.sp,color=colors.mutedText)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){sims.forEach{sim->HapticFilterChip(selected=sub==sim.subId,onClick={sub=sim.subId;error=null},label={Text("卡${sim.slotIndex+1} · ${sim.displayName}")})}}
        if(sims.none{it.subId==sub})Text("原 SIM 已不可用，请手动选择一张卡。",color=colors.danger)
        OutlinedTextField(text,{text=it.take(4000)},Modifier.fillMaxWidth(),label={Text("回复内容")},minLines=2,maxLines=5)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){HapticAssistChip(onClick={text="1"},label={Text("1")});HapticAssistChip(onClick={text="TD"},label={Text("TD")})}
        if(text.trim().uppercase().startsWith("TD"))Text("TD 通常用于退订，请确认后再发送。",color=colors.danger,fontSize=12.sp)
        error?.let{Text(it,color=colors.danger)}
    }},confirmButton={HapticTextButton(onClick={error=when{text.isBlank()->"请填写回复内容";sims.none{it.subId==sub}->"请选择可用 SIM";else->null};if(error==null)onSend(text.trim(),sub,message.sender)}){Text("确认发送")}},dismissButton={HapticTextButton(onClick=onDismiss){Text("取消")}})
}

private fun simLabel(subId:Int?,sims:List<SimInfo>)=sims.find{it.subId==subId}?.let{"卡${it.slotIndex+1}"} ?: if(subId==null)"未分卡"else"已移除的 SIM"


internal object InboxExportCache {
    private fun directory(context:android.content.Context)=java.io.File(context.cacheDir,"inbox-export").apply{mkdirs()}
    fun prepare(context:android.content.Context,messages:List<SmsItem>):String {
        require(messages.isNotEmpty()) { "未选择短信" }
        val dir=directory(context)
        dir.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }?.forEach{it.delete()}
        val token=java.util.UUID.randomUUID().toString()
        java.io.File(dir,token).writeText(CryptoUtil.encrypt(TxtBackup.encode(messages)))
        return token
    }
    fun read(context:android.content.Context,token:String):String {
        require(token.matches(Regex("[a-fA-F0-9-]{36}")))
        return CryptoUtil.decrypt(java.io.File(directory(context),token).readText())
    }
    fun remove(context:android.content.Context,token:String) {if(token.matches(Regex("[a-fA-F0-9-]{36}")))java.io.File(directory(context),token).delete()}
    fun clear(context:android.content.Context) {directory(context).deleteRecursively()}
}
