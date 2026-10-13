package com.ghost.smshelper

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun QueryHistoryDialog(onDismiss:()->Unit,onRetry:(QueryTask)->Boolean) {
    val context=LocalContext.current
    val colors=AppTheme.colors
    val tasks by QueryRepository.tasks.collectAsState()
    val messages by SmsRepository.messages.collectAsState()
    val sims=remember{getActiveSims(context)}
    var selectedId by remember{mutableStateOf<String?>(null)}
    var retry by remember{mutableStateOf<QueryTask?>(null)}
    var clear by remember{mutableStateOf(false)}
    val task=tasks.find{it.id==selectedId}
    LaunchedEffect(Unit){while(true){QueryRepository.refreshTimeouts(context);delay(15_000)}}
    AlertDialog(onDismissRequest=onDismiss,containerColor=colors.dialog,title={Text("查询与发送记录")},text={LazyColumn(Modifier.fillMaxWidth().heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("发送回执与运营商回复分别记录。关联回复依据 SIM、号码和时间辅助判断。",fontSize=12.sp,color=colors.mutedText)}
        if(tasks.isEmpty())item{Text("还没有发送记录，试试发起一次查询。")}
        items(tasks.take(100),key={it.id}){entry->CartoonCard(Modifier.fillMaxWidth(),backgroundColor=colors.inboxSent,onClick={selectedId=entry.id}){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(entry.command,fontWeight=FontWeight.Black,color=colors.text,maxLines=2)
            Text("${sims.find{it.subId==entry.subId}?.let{"卡${it.slotIndex+1}"}?:"已移除 SIM"} → ${entry.target} · ${friendlyTime(entry.createdAt)}",fontSize=11.sp,color=colors.mutedText)
            Text(queryStatusLabel(entry.status),fontWeight=FontWeight.Bold,color=if(entry.status==QueryStatus.FAILED||entry.status==QueryStatus.TIMEOUT)colors.danger else MaterialTheme.colorScheme.primary)
            if(entry.matchedFields.isNotEmpty())Text("相关回复更新了 ${entry.matchedFields.size} 项数据",fontSize=12.sp,color=colors.mutedText)
        }}}
    }},confirmButton={HapticTextButton(onClick=onDismiss){Text("关闭")}},dismissButton={if(tasks.isNotEmpty())HapticTextButton(onClick={clear=true}){Text("清理记录")}})
    task?.let{entry->AlertDialog(onDismissRequest={selectedId=null},containerColor=colors.dialog,title={Text(queryStatusLabel(entry.status))},text={LazyColumn(Modifier.heightIn(max=430.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item{Text("${sims.find{it.subId==entry.subId}?.let{"卡${it.slotIndex+1}"}?:"已移除 SIM"} → ${entry.target}\n${friendlyTime(entry.createdAt)}\n\n${entry.command}")}
        entry.error?.let{item{Text(it,color=colors.danger)}}
        if(entry.status==QueryStatus.COMPOSER_OPENED)item{Text("已打开系统短信应用。应用无法确认你是否完成了发送，请核对系统短信记录。")}
        if(entry.replyMessageIds.isEmpty())item{Text("尚未关联到可解析的回复。并行查询或普通回复无法确定对应关系时，请到信箱查看。",fontSize=12.sp,color=colors.mutedText)}
        entry.replyMessageIds.forEach{id->item{val reply=messages.find{it.id==id};Text(reply?.let{"${it.sender} · ${it.time}\n${it.body}"}?:"关联短信已删除",Modifier.padding(vertical=6.dp))}}
        if(entry.matchedFields.isNotEmpty())item{Text("更新字段："+entry.matchedFields.joinToString("、"){fieldNames[it]?:it},fontSize=12.sp)}
        if(!entry.userConfirmed && entry.replyMessageIds.isNotEmpty())item{HapticTextButton(onClick={if(QueryRepository.confirmReply(context,entry.id))selectedId=null}){Text("我已核对回复，标记完成")}}
    }},confirmButton={if(entry.status in setOf(QueryStatus.FAILED,QueryStatus.TIMEOUT,QueryStatus.COMPOSER_OPENED))HapticTextButton(onClick={retry=entry}){Text("手动重试")}else HapticTextButton(onClick={selectedId=null}){Text("知道了")}},dismissButton={HapticTextButton(onClick={selectedId=null}){Text("关闭")}})}
    retry?.let{entry->AlertDialog(onDismissRequest={retry=null},containerColor=colors.dialog,title={Text("确认重新发送？")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {Text("仍使用原 SIM，发送到 ${entry.target}：\n${entry.command}");Text("请先核对系统短信记录，避免重复办理业务。",Modifier.padding(top=12.dp),color=colors.mutedText)}},confirmButton={HapticTextButton(onClick={if(onRetry(entry)){retry=null;selectedId=null}else Toast.makeText(context,"未能重新发送，请检查原 SIM、权限和任务状态",Toast.LENGTH_LONG).show()}){Text("确认重试")}},dismissButton={HapticTextButton(onClick={retry=null}){Text("取消")}})}
    if(clear)AlertDialog(onDismissRequest={clear=false},containerColor=colors.dialog,title={Text("清理发送记录？")},text={Text("清理已结束的发送记录，保留仍在等待回复的任务。短信和账户数据不受影响。")},confirmButton={HapticTextButton(onClick={if(QueryRepository.clearHistory(context)){clear=false;selectedId=null}else Toast.makeText(context,"清理失败，请检查可用存储空间",Toast.LENGTH_LONG).show()}){Text("清理")}},dismissButton={HapticTextButton(onClick={clear=false}){Text("取消")}})
}

private fun queryStatusLabel(status:QueryStatus)=when(status){
    QueryStatus.SUBMITTED->"正在提交 / 等待发送回执"
    QueryStatus.SENT->"已发送"
    QueryStatus.WAITING->"已发送，等待回复"
    QueryStatus.PARTIAL->"收到相关回复（辅助关联）"
    QueryStatus.REPLIED->"已核对完成"
    QueryStatus.FAILED->"发送失败"
    QueryStatus.TIMEOUT->"等待超时，请核对信箱"
    QueryStatus.COMPOSER_OPENED->"已打开系统短信应用"
}
private val fieldNames=mapOf("balance" to "余额","consumption" to "消费","usedData" to "已用流量","remainingData" to "剩余流量","generalData" to "通用流量","voiceInclude" to "套餐语音","voiceRemain" to "剩余语音","traffic" to "流量明细","bill" to "账单")
