@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ghost.smshelper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, onNavigateToSecretMenu: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences("SmsHelper_Prefs", Context.MODE_PRIVATE) }
    val palette = AppTheme.colors
    val haptic = actionHaptics()
    val tabState=rememberSaveableStateHolder()
    var settingsSubPage by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sims by remember { mutableStateOf(getActiveSims(context)) }
    var selectedId by rememberSaveable { mutableIntStateOf(prefs.getInt("selected_sim_subid", -1)) }
    val selected = sims.find { it.subId == selectedId } ?: sims.firstOrNull()
    val config by ConfigManager.activeConfig.collectAsState()
    val commandPrefs by CommandStore.data.collectAsState()
    val simPrefs by SimPreferences.data.collectAsState()
    val unread by SmsRepository.unreadCount.collectAsState()
    val backupBusy by FullBackupManager.busy.collectAsState()
    val commands = remember(config, commandPrefs) { CommandStore.commands(config, commandPrefs) }
    var simDetails by remember { mutableStateOf<SimInfo?>(null) }
    var pending by remember { mutableStateOf<UiCommand?>(null) }
    var pendingSub by remember { mutableIntStateOf(-1) }
    var parameter by rememberSaveable { mutableStateOf("") }
    var parameterError by remember { mutableStateOf<String?>(null) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var queueSub by rememberSaveable { mutableIntStateOf(-1) }
    var queueTarget by rememberSaveable { mutableStateOf("") }
    var queueIndex by rememberSaveable { mutableIntStateOf(0) }
    var queueInterval by rememberSaveable { mutableIntStateOf(10) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var queueRunning by remember { mutableStateOf(false) }
    var queueStatus by rememberSaveable { mutableStateOf("准备发送") }
    var backTime by remember { mutableLongStateOf(0) }
    fun refreshSims() { sims = getActiveSims(context) }
    fun select(sim: SimInfo?) {
        selectedId = sim?.subId ?: -1
        prefs.edit().putInt("selected_sim_subid", selectedId).apply()
        SmsRepository.selectSubscription(context, sim?.subId)
        ConfigManager.switchSim(sim)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSims(); select(sims.find { it.subId == selectedId } ?: sims.firstOrNull()) }
    LaunchedEffect(Unit) {
        CommandStore.init(context)
        val required = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE)
        if (required.any { ContextCompat.checkSelfPermission(context,it) != PackageManager.PERMISSION_GRANTED } && !prefs.getBoolean("permissions_requested",false)) { prefs.edit().putBoolean("permissions_requested",true).apply(); permissionLauncher.launch(required) }
        else select(selected)
    }
    LaunchedEffect(selected?.subId, simPrefs) { select(selected) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshSims()
            if (event == Lifecycle.Event.ON_PAUSE && queueRunning) { queueRunning=false; queueStatus="已暂停：回到应用后继续" }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    BackHandler(enabled = tab != 3) {
        when {
            queueOpen -> { queueRunning=false; queueOpen=false }
            tab != 0 -> tab=0
            System.currentTimeMillis()-backTime<2000 -> (context as? Activity)?.finish()
            else -> { backTime=System.currentTimeMillis(); Toast.makeText(context,"再按一次退出应用",Toast.LENGTH_SHORT).show() }
        }
    }
    fun dispatch(code: String, subId: Int, target: String): Boolean {
        val sim = getActiveSims(context).find { it.subId==subId }
        if (sim == null) { Toast.makeText(context,"所选 SIM 已不可用，请重新选择",Toast.LENGTH_SHORT).show(); return false }
        return if(prefs.getBoolean("compat_mode",false)) activity?.sendSmsViaSystem(code,sim,target)==true
        else activity?.sendSmsSilently(code,subId,target,false)==true
    }
    fun prepare(command: UiCommand) {
        if(selected==null) Toast.makeText(context,"请先授权并选择 SIM 卡",Toast.LENGTH_SHORT).show()
        else { pendingSub=selected.subId; pending=command; parameter=""; parameterError=null }
    }
    fun startGroup() {
        val sim=selected
        if(sim==null || config==null) { Toast.makeText(context,"请选择 SIM 和查询规则",Toast.LENGTH_SHORT).show(); return }
        val group=commandPrefs.groups[sim.subId] ?: QueryGroup(config!!.syncCommands)
        if(group.commands.isEmpty()) { showGroupEditor=true; return }
        queue=ArrayList(group.commands); queueSub=sim.subId; queueTarget=config!!.targetNumber
        queueInterval=group.intervalSeconds; queueIndex=0; queueRunning=false; queueStatus="核对后开始发送"; queueOpen=true
    }
    LaunchedEffect(queueRunning, queueIndex, queueOpen) {
        if(queueRunning && queueOpen && queueIndex < queue.size) {
            if(queueIndex>0) { queueStatus="下一条将在 ${queueInterval} 秒后发送"; delay(queueInterval*1000L) }
            if(dispatch(queue[queueIndex],queueSub,queueTarget)) {
                CommandStore.queried(queueSub); queueIndex++; queueStatus="已提交 $queueIndex/${queue.size}，等待运营商回复"
                if(queueIndex>=queue.size || prefs.getBoolean("compat_mode",false)) queueRunning=false
            } else { queueRunning=false; queueStatus="提交失败，检查权限和 SIM 后可重试" }
        }
    }
    Scaffold(
        containerColor=palette.background,
        contentWindowInsets=WindowInsets(0,0,0,0),
        topBar={ if(tab != 3) Column {
            TopAppBar(title={ Text(listOf("短信助手", "指令口袋", "大信箱")[tab],fontWeight=FontWeight.Black) },
                actions={
                    if(tab==0) HapticIconButton(onClick={showHistory=true}) { Icon(Icons.Default.List,"查询记录") }
                    if(tab==1) HapticIconButton(onClick={showGroupEditor=true}) { Icon(Icons.Default.Edit,"查询组合") }
                    HapticIconButton(enabled=!backupBusy,onClick={tab=3}) { Icon(Icons.Default.Settings,"设置") }
                }, colors=TopAppBarDefaults.topAppBarColors(containerColor=palette.background,titleContentColor=palette.text,actionIconContentColor=palette.text))
            if(tab != 2) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(sims.isEmpty()) HapticTextButton(onClick={permissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS))}) { Text("授权并读取 SIM 卡") }
                sims.forEach { sim ->
                    val active=sim.subId==selected?.subId
                    val nickname=simPrefs[sim.subId]?.nickname?.takeIf(String::isNotBlank) ?: sim.displayName
                    val overrideColor=simPrefs[sim.subId]?.colorHex?.takeIf(String::isNotBlank)?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                    Surface(modifier=Modifier.heightIn(min=48.dp).border(2.dp,palette.outline,RoundedCornerShape(14.dp)).combinedClickable(onClick={haptic(HapticEvent.Tap);select(sim)},onLongClick={haptic(HapticEvent.LongPress);simDetails=sim}),
                        shape=RoundedCornerShape(14.dp),color=if(active)overrideColor?:palette.simSelected else palette.simUnselected) {
                        Text("${if(active)"● " else ""}卡${sim.slotIndex+1} · $nickname",Modifier.padding(horizontal=14.dp,vertical=10.dp),fontWeight=FontWeight.Bold,color=if(active && overrideColor!=null) readableText(overrideColor) else palette.text)
                    }
                }
            }
        } },
        bottomBar={ if(!settingsSubPage) NavigationBar(containerColor=palette.navBackground, tonalElevation=0.dp) {
            listOf(Icons.Default.Home,Icons.Default.Send,Icons.Default.Email,Icons.Default.Settings).forEachIndexed { index,icon ->
                NavigationBarItem(enabled=!backupBusy,selected=tab==index,onClick={haptic(HapticEvent.Tap);tab=index}, icon={
                    if(index==2 && unread>0) BadgedBox(badge={Badge {Text(if(unread>99)"99+" else unread.toString())}}){Icon(icon,null)} else Icon(icon,null)
                }, label={Text(listOf("总览","指令","信箱","设置")[index])},colors=NavigationBarItemDefaults.colors(selectedIconColor=palette.navText,selectedTextColor=palette.navText,unselectedIconColor=palette.mutedText,unselectedTextColor=palette.mutedText,indicatorColor=palette.navSelected))
            }
        } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            tabState.SaveableStateProvider(tab) { when(tab) {
                0 -> OverviewScreen(selected,commands,commandPrefs,onCommand=::prepare,onQuery=::startGroup,onInbox={tab=2},onCommands={tab=1},onHistory={showHistory=true})
                1 -> CommandsScreen(commands,commandPrefs,onCommand=::prepare,onQuery=::startGroup,onEditGroup={showGroupEditor=true},defaultTarget=config?.targetNumber.orEmpty())
                2 -> InboxScreen(sims,selected,onReply={ code,sub,target -> dispatch(code,sub,target) },onSecret=onNavigateToSecretMenu)
                else -> SettingsScreen(onBack={tab=0},showBack=false,onSubPageChanged={settingsSubPage=it})
            }
        }
        }
    }
    pending?.let { item ->
        val sim=sims.find { it.subId==pendingSub }
        AlertDialog(onDismissRequest={pending=null},containerColor=palette.dialog,title={Text("确认发送 · ${item.command.name}",fontWeight=FontWeight.Black)},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("使用：${sim?.let { "卡${it.slotIndex+1} · ${it.displayName}" } ?: "SIM 已不可用"}\n号码：${item.target}")
            if(item.command.requiresInput) OutlinedTextField(value=parameter,onValueChange={parameter=it;parameterError=null},label={Text(item.command.inputHint.ifBlank {"填写参数"})},isError=parameterError!=null,supportingText={parameterError?.let{Text(it)}},singleLine=true)
            Text("短信内容：${item.command.code}${parameter.trim()}",fontWeight=FontWeight.Bold)
            if(isBusinessCommand(item.command)) Text("此指令可能办理或取消业务，请核对内容。",color=palette.danger)
            if(prefs.getBoolean("compat_mode",false)) Text("将在系统短信应用中继续确认发送。",color=palette.mutedText)
        }},confirmButton={HapticTextButton(onClick={
            val error=if(item.command.requiresInput)CommandParameters.validate(item.command,parameter)else null
            if(error!=null)parameterError=error else if(dispatch(item.command.code+parameter.trim(),pendingSub,item.target)) { CommandStore.used(item.id);pending=null }
        }){Text("发送")}},dismissButton={HapticTextButton(onClick={pending=null}){Text("取消")}})
    }
    if(showGroupEditor) QueryGroupEditor(selected,config,commandPrefs,onDismiss={showGroupEditor=false})
    if(queueOpen) AlertDialog(onDismissRequest={queueRunning=false;queueOpen=false},containerColor=palette.dialog,title={Text("查询组合 · $queueIndex/${queue.size}")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("卡 ${sims.find{it.subId==queueSub}?.let{it.slotIndex+1} ?: "已移除"} → $queueTarget")
        Text(queue.joinToString(" → "));Text(queueStatus,color=palette.mutedText)
        if(prefs.getBoolean("compat_mode",false))Text("系统短信模式每次只打开一条，请完成发送后再继续。")
        Text("提交不代表数据已更新，可在查询记录查看回复。",color=palette.mutedText)
    }},confirmButton={if(queueIndex<queue.size)HapticTextButton(onClick={queueRunning=!queueRunning}){Text(if(queueRunning)"暂停" else if(queueIndex==0)"开始发送" else "继续 / 重试")}else HapticTextButton(onClick={queueOpen=false;showHistory=true}){Text("查看记录")}},dismissButton={HapticTextButton(onClick={queueRunning=false;queueOpen=false}){Text(if(queueIndex>=queue.size)"关闭" else "取消后续")}})
    if(showHistory) QueryHistoryDialog(onDismiss={showHistory=false},onRetry={task -> activity?.retrySms(task)==true})
    simDetails?.let { sim -> AlertDialog(onDismissRequest={simDetails=null},containerColor=palette.dialog,title={Text("高级卡片信息")},text={Column {
        Text("卡槽：SIM ${sim.slotIndex+1}\n运营商：${sim.carrierName}\n本机号码：${sim.number}\n\nICCID：${sim.iccId}\nMCC：${sim.mcc} · MNC：${sim.mnc}\n漫游：${sim.isRoaming}\n订阅标识：${sim.subId}")
    }},confirmButton={HapticTextButton(onClick={simDetails=null}){Text("关闭")}}) }
}

internal fun readableText(color: Color): Color = if((color.red*0.299f+color.green*0.587f+color.blue*0.114f)>0.58f) Color(0xFF20242A) else Color.White
internal fun isBusinessCommand(command: ActionCommand) = listOf("办理","退订","取消","开通","关闭","订购","兑换","充值").any { command.name.contains(it) } || command.code.uppercase().startsWith("TD")
