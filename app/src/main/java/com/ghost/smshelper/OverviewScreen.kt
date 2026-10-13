@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.ghost.smshelper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OverviewScreen(sim: SimInfo?, commands: List<UiCommand>, preferences: CommandPreferences,
    onCommand:(UiCommand)->Unit,onQuery:()->Unit,onInbox:()->Unit,onCommands:()->Unit,onHistory:()->Unit) {
    val palette=AppTheme.colors
    val context=LocalContext.current
    val wideMetrics=LocalDensity.current.fontScale<=1.25f
    val info by SmsRepository.parsedInfo.collectAsState()
    val metadata by SmsRepository.selectedMetadata.collectAsState()
    val legacy by SmsRepository.isLegacyAccount.collectAsState()
    val messages by SmsRepository.messages.collectAsState()
    val tasks by QueryRepository.tasks.collectAsState()
    var layoutDialog by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<SmsItem?>(null) }
    val recent=messages.filter { it.subId==sim?.subId }.take(3)
    val lastQuery=tasks.firstOrNull { it.subId==sim?.subId }?.createdAt ?: preferences.lastQuery[sim?.subId]
    fun known(key:String)=metadata.containsKey(key)
    fun viewSource(id:String) { val msg=messages.find { it.id==id }; if(msg!=null)source=msg else Toast.makeText(context,"来源短信已删除或未保留",Toast.LENGTH_SHORT).show() }
    LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("今天也要元气满格",fontWeight=FontWeight.Black,fontSize=21.sp,color=palette.text)
                Text("最后查询：${lastQuery?.let(::friendlyTime) ?: "从未"}",fontSize=12.sp,color=palette.mutedText) }
            HapticIconButton(onClick={layoutDialog=true}) {Icon(Icons.Default.Edit,"整理首页",tint=palette.text)}
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CartoonButton("↻ 一键查询",Modifier.weight(1f),color=palette.primary,onClick=onQuery)
            CartoonButton("查询记录",Modifier.weight(1f),color=palette.button,onClick=onHistory)
        } }
        if(legacy)item { Text("当前展示旧版未分卡数据，新的运营商回复会按 SIM 分别更新。",color=palette.mutedText,fontSize=12.sp) }
        preferences.cards.filterNot(preferences.hiddenCards::contains).forEach { card -> when(card) {
            "account" -> item {
                CartoonCard(Modifier.fillMaxWidth(),backgroundColor=palette.surface) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("我的账户",fontWeight=FontWeight.Black,fontSize=18.sp,color=palette.text)
                    FlowRow(maxItemsInEachRow=if(wideMetrics)2 else 1,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        MetricTile("账户余额",if(known("balance"))"¥ ${info.balance}"else"未获取",metadata["balance"],Modifier.weight(1f),onClick={detail="balance"},onSource=::viewSource)
                        MetricTile("本月消费",if(known("consumption"))"¥ ${info.consumption}"else"未获取",metadata["consumption"],Modifier.weight(1f),onClick={detail="bill"},onSource=::viewSource)
                    }
                    HorizontalDivider(color=palette.outline.copy(alpha=.18f))
                    MetricTile("通用流量剩余",if(known("generalData"))"${String.format(Locale.getDefault(),"%.2f",info.generalDataMb/1024)} GB"else"未获取",metadata["generalData"],Modifier.fillMaxWidth(),onClick={detail="traffic"},onSource=::viewSource)
                    if(known("remainingData")) {
                        val total=info.usedDataMb+info.remainingTotalDataMb
                        if(known("usedData") && metadata["usedData"]?.updatedAt==metadata["remainingData"]?.updatedAt)LinearProgressIndicator(progress={if(total>0)(info.remainingTotalDataMb/total).toFloat().coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth().height(10.dp),color=palette.chartData,trackColor=palette.surfaceAlt)
                        Text("套餐合计剩余 ${String.format(Locale.getDefault(),"%.2f",info.remainingTotalDataMb/1024)} GB · 已用 ${if(known("usedData"))String.format(Locale.getDefault(),"%.2f",info.usedDataMb/1024)+" GB" else "未获取"}",fontSize=12.sp,color=palette.mutedText)
                    }
                    MetricTile("语音剩余",if(known("voiceRemain"))"${info.remainingVoice} 分钟"else"未获取",metadata["voiceRemain"],Modifier.fillMaxWidth(),onClick={detail="voice"},onSource=::viewSource)
                    if(known("voiceInclude") && known("voiceRemain") && info.totalVoice>0)LinearProgressIndicator(progress={(info.remainingVoice.toFloat()/info.totalVoice).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(10.dp),color=palette.chartVoice,trackColor=palette.surfaceAlt)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HapticTextButton(onClick={detail="traffic"}){Text("流量明细")};HapticTextButton(onClick={detail="bill"}){Text("历史账单")}
                    }
                } }
            }
            "favorites" -> item {
                val favorites=commands.filter { it.id in preferences.favorites }.take(8)
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    SectionTitle("常用指令", "全部指令",onCommands)
                    if(favorites.isEmpty())CartoonCard(Modifier.fillMaxWidth(),backgroundColor=palette.surfaceAlt,onClick=onCommands) {Text("把常用指令点亮星标，放进首页口袋。",Modifier.padding(16.dp),color=palette.text)}
                    favorites.chunked(2).forEach { row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {row.forEach { cmd->CartoonButton(cmd.command.name,Modifier.weight(1f),color=categoryColor(preferences,cmd.category,palette.button),onClick={onCommand(cmd)}) };if(row.size==1)Spacer(Modifier.weight(1f))} }
                }
            }
            "recent" -> item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SectionTitle("最近回复","打开信箱",onInbox)
                if(recent.isEmpty())Text("这张卡还没有收到短信，查询后回来看看。",color=palette.mutedText)
                recent.forEach { msg->CartoonCard(Modifier.fillMaxWidth(),backgroundColor=palette.inboxReceived,onClick={source=msg}) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${msg.sender} · ${msg.time}",fontWeight=FontWeight.Bold,fontSize=12.sp,color=palette.mutedText)
                    Text(msg.body,maxLines=3,color=palette.text)
                }} }
            } }
        } }
        if(preferences.hiddenCards.size==3)item {Text("首页卡片已隐藏。点击右上角整理首页，可以随时找回来。",color=palette.mutedText)}
    }
    if(layoutDialog)AlertDialog(onDismissRequest={layoutDialog=false},containerColor=palette.dialog,title={Text("整理首页")},text={Column {
        preferences.cards.forEachIndexed { index,id->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            HapticFilterChip(selected=id !in preferences.hiddenCards,onClick={CommandStore.hideCard(id)},label={Text(mapOf("account" to "账户面板","favorites" to "常用指令","recent" to "最近回复")[id].orEmpty())})
            Row {HapticIconButton(onClick={CommandStore.moveCard(id,-1)},enabled=index>0){Icon(Icons.Default.KeyboardArrowUp,"上移")};HapticIconButton(onClick={CommandStore.moveCard(id,1)},enabled=index<preferences.cards.lastIndex){Icon(Icons.Default.KeyboardArrowDown,"下移")}}
        } }
        Text("点标签显示或隐藏，使用箭头调整顺序。",fontSize=12.sp,color=palette.mutedText)
    }},confirmButton={HapticTextButton(onClick={layoutDialog=false}){Text("完成")}})
    detail?.let { key->AlertDialog(onDismissRequest={detail=null},containerColor=palette.dialog,title={Text(when(key){"traffic"->"流量报告";"bill"->"历史账单";"voice"->"语音详情";else->"账户余额"})},text={LazyColumn(Modifier.heightIn(max=360.dp)) {
        when(key) {
            "traffic"->if(info.trafficDetails.isEmpty())item{Text("暂时没有明细，查询流量后等待运营商回复。")}else items(info.trafficDetails){Text(it,Modifier.padding(vertical=6.dp))}
            "bill"->if(info.historicalBills.isEmpty())item{Text("还没有历史账单，去指令页查询指定月份账单。")}else items(info.historicalBills.reversed()){bill->Text("${bill.month}    ¥ ${bill.amount}",Modifier.padding(vertical=8.dp),fontWeight=FontWeight.Bold)}
            "voice"->item{Text("套餐包含：${if(known("voiceInclude"))"${info.totalVoice} 分钟" else "未获取"}\n剩余：${if(known("voiceRemain"))"${info.remainingVoice} 分钟" else "未获取"}")}
            else->item{Text("余额：${if(known("balance"))"¥ ${info.balance}" else "未获取"}\n本月消费：${if(known("consumption"))"¥ ${info.consumption}" else "未获取"}")}
        }
    }},confirmButton={HapticTextButton(onClick={detail=null}){Text("知道啦")}}) }
    source?.let { msg->AlertDialog(onDismissRequest={source=null},containerColor=palette.dialog,title={Text("数据来源 · ${msg.sender}")},text={LazyColumn(Modifier.heightIn(max=400.dp)){item{Text(msg.time,color=palette.mutedText);Text(msg.body,Modifier.padding(top=12.dp))}}},confirmButton={HapticTextButton(onClick={source=null}){Text("关闭")}}) }
}

@Composable
private fun MetricTile(title:String,value:String,freshness:FieldFreshness?,modifier:Modifier,onClick:()->Unit,onSource:(String)->Unit) {
    val colors=AppTheme.colors
    Column(modifier) {
        HapticTextButton(onClick=onClick,contentPadding=PaddingValues(0.dp)) {Column {Text(title,fontSize=12.sp,color=colors.mutedText);Text(value,fontWeight=FontWeight.Black,fontSize=23.sp,color=colors.text)} }
        if(freshness!=null) {
            val old=System.currentTimeMillis()-freshness.updatedAt>24*60*60*1000L
            Text(if(freshness.updatedAt<=0)"历史数据 · 更新时间未知" else "${if(old)"旧数据 · "else""}${friendlyTime(freshness.updatedAt)}",fontSize=11.sp,color=if(old)colors.danger else colors.mutedText)
            if(freshness.sourceMessageId!=null)HapticTextButton(onClick={freshness.sourceMessageId.let(onSource)},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=36.dp)){Text("查看来源",fontSize=12.sp)}
        } else Text("等待运营商回复",fontSize=11.sp,color=colors.mutedText)
    }
}

@Composable
internal fun SectionTitle(title:String,action:String,onClick:()->Unit) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Text(title,Modifier.weight(1f),fontWeight=FontWeight.Black,fontSize=18.sp,color=AppTheme.colors.text);HapticTextButton(onClick=onClick){Text(action)}}}
internal fun friendlyTime(time:Long):String = SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(time))
