package com.ghost.smshelper

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CommandsScreen(commands:List<UiCommand>,preferences:CommandPreferences,onCommand:(UiCommand)->Unit,onQuery:()->Unit,onEditGroup:()->Unit,defaultTarget:String) {
    val colors=AppTheme.colors
    val context=LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var category by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<UiCommand?>(null) }
    var editOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<UiCommand?>(null) }
    var remove by remember { mutableStateOf<UiCommand?>(null) }
    var editCategory by remember { mutableStateOf<String?>(null) }
    val categories=commands.map{it.category}.distinct()
    val visible=commands.filter { item ->
        (search.isBlank() || listOf(item.command.name,item.command.code,item.category,item.target).any { it.contains(search,true) }) &&
            (category.isEmpty() || item.category==category) &&
            (filter!="收藏" || item.id in preferences.favorites) && (filter!="最近" || item.id in preferences.recent) && (filter!="自建" || item.custom)
    }.let { items -> if(filter=="最近")items.sortedBy{preferences.recent.indexOf(it.id)}else items }
    val columns=if(LocalDensity.current.fontScale>1.25f)1 else 2
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),singleLine=true,placeholder={Text("搜索名称、指令、号码")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(search.isNotEmpty())HapticIconButton(onClick={search=""}){Icon(Icons.Default.Close,"清空搜索")}})
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部","收藏","最近","自建").forEach { label->HapticFilterChip(selected=filter==label,onClick={filter=label},label={Text(label)}) }
            HapticAssistChip(onClick={editing=null;editOpen=true},label={Text("新增指令")},leadingIcon={Icon(Icons.Default.Add,null,Modifier.size(16.dp))})
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            HapticFilterChip(selected=category.isEmpty(),onClick={category=""},label={Text("所有分类")})
            categories.forEach { title->HapticFilterChip(selected=category==title,onClick={category=if(category==title)""else title},label={Text(title)}) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CartoonButton("↻ 执行查询组合",Modifier.weight(1f),color=colors.primary,onClick=onQuery)
            CartoonButton("编辑组合",Modifier.weight(1f),color=colors.button,onClick=onEditGroup)
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visible.isEmpty())item {Text(if(commands.isEmpty())"还没有可用规则，先选择 SIM 或到设置导入规则。"else"没有匹配的指令，试试其他分类或关键词。",Modifier.padding(16.dp),color=colors.mutedText)}
            visible.groupBy{it.category}.forEach { (title,items)->
                item {Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Text(title,Modifier.weight(1f),fontWeight=FontWeight.Black,fontSize=17.sp,color=colors.text);HapticTextButton(onClick={editCategory=title}){Text("分类设置")}}}
                items(items.chunked(columns)) { row->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach { item->CartoonCard(Modifier.weight(1f),backgroundColor=colors.surface,onLongClick={menu=item}) { Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(item.command.code,Modifier.weight(1f).padding(top=10.dp),fontSize=12.sp,maxLines=2,color=colors.mutedText)
                            HapticIconButton(onClick={CommandStore.favorite(item.id)},modifier=Modifier.size(48.dp)) {Icon(if(item.id in preferences.favorites)Icons.Default.Star else Icons.Default.Star,if(item.id in preferences.favorites)"取消收藏"else"收藏",tint=if(item.id in preferences.favorites)colors.primary else colors.mutedText)}
                        }
                        CartoonButton(item.command.name,Modifier.fillMaxWidth(),color=categoryColor(preferences,item.category,colors.button),onLongClick={menu=item},onClick={onCommand(item)})
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("→ ${item.target}",Modifier.weight(1f),fontSize=11.sp,color=colors.mutedText)
                            HapticIconButton(onClick={menu=item},modifier=Modifier.size(48.dp)){Icon(Icons.Default.MoreVert,"管理指令",Modifier.size(20.dp),tint=colors.mutedText)}
                        }
                    } } }
                    if(row.size<columns)Spacer(Modifier.weight(1f))
                } }
            }
        }
    }
    menu?.let { item->AlertDialog(onDismissRequest={menu=null},containerColor=colors.dialog,title={Text(item.command.name)},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("分类：${item.category}\n号码：${item.target}\n内容：${item.command.code}")
        HapticTextButton(onClick={CommandStore.favorite(item.id);menu=null}) {Text(if(item.id in preferences.favorites)"取消收藏"else"收藏到首页")}
        Row {HapticTextButton(onClick={CommandStore.move(item.id,-1,commands);menu=null}){Text("分类内上移")};HapticTextButton(onClick={CommandStore.move(item.id,1,commands);menu=null}){Text("分类内下移")}}
        HapticTextButton(onClick={editing=if(item.custom)item else item.copy(id="",custom=true,command=item.command.copy(name=item.command.name+"（副本）"));editOpen=true;menu=null}){Text(if(item.custom)"编辑指令"else"复制并自定义")}
        if(item.custom)HapticTextButton(onClick={remove=item;menu=null}){Text("删除指令",color=colors.danger)}
    }},confirmButton={HapticTextButton(onClick={menu=null}){Text("关闭")}}) }
    remove?.let { item->AlertDialog(onDismissRequest={remove=null},containerColor=colors.dialog,title={Text("删除自建指令？")},text={Text(item.command.name)},confirmButton={HapticTextButton(onClick={CommandStore.remove(item.id);remove=null}){Text("删除",color=colors.danger)}},dismissButton={HapticTextButton(onClick={remove=null}){Text("取消")}}) }
    editCategory?.let { name -> CategoryColorDialog(name,preferences.categoryColors[name],commands,onDismiss={editCategory=null}) }
    if(editOpen)CommandEditor(editing,defaultTarget,onDismiss={editOpen=false},onSave={CommandStore.upsert(it);editOpen=false;Toast.makeText(context,"已保存到指令口袋",Toast.LENGTH_SHORT).show()})
}

@Composable
private fun CommandEditor(original:UiCommand?,defaultTarget:String,onDismiss:()->Unit,onSave:(UiCommand)->Unit) {
    val colors=AppTheme.colors
    var name by remember(original){mutableStateOf(original?.command?.name.orEmpty())}
    var category by remember(original){mutableStateOf(original?.category?:"我的指令")}
    var target by remember(original){mutableStateOf(original?.target?:defaultTarget)}
    var code by remember(original){mutableStateOf(original?.command?.code.orEmpty())}
    var requires by remember(original){mutableStateOf(original?.command?.requiresInput?:false)}
    var hint by remember(original){mutableStateOf(original?.command?.inputHint.orEmpty())}
    var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=colors.dialog,title={Text(if(original?.custom==true && original.id.isNotBlank())"编辑指令"else"新增指令")},text={LazyColumn(Modifier.heightIn(max=450.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item{OutlinedTextField(name,{name=it.take(80)},label={Text("名称")},singleLine=true)}
        item{OutlinedTextField(category,{category=it.take(64)},label={Text("分类")},singleLine=true)}
        item{OutlinedTextField(target,{target=it.take(21)},label={Text("接收号码")},singleLine=true)}
        item{OutlinedTextField(code,{code=it.take(1024)},label={Text("短信指令")},minLines=2)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("发送前填写参数");Switch(requires,{requires=it})}}
        if(requires)item{OutlinedTextField(hint,{hint=it.take(160)},label={Text("参数提示")},supportingText={Text("填写内容会追加在指令末尾。")})}
        error?.let{item{Text(it,color=colors.danger)}}
    }},confirmButton={HapticTextButton(onClick={
        error=when {name.isBlank()->"请填写名称";category.isBlank()->"请填写分类";!target.trim().matches(Regex("\\+?[0-9]{3,20}"))->"请输入有效接收号码";code.isBlank()->"请填写指令";requires&&hint.isBlank()->"请填写参数提示";else->null}
        if(error==null)onSave(UiCommand(original?.id.orEmpty(),category.trim(),ActionCommand(code.trim(),name.trim(),requires,hint.trim()),target.trim(),true))
    }){Text("保存")}},dismissButton={HapticTextButton(onClick=onDismiss){Text("取消")}})
}

@Composable
fun QueryGroupEditor(sim:SimInfo?,config:CarrierConfig?,preferences:CommandPreferences,onDismiss:()->Unit) {
    val colors=AppTheme.colors
    val existing=preferences.groups[sim?.subId]?:QueryGroup(config?.syncCommands.orEmpty())
    var lines by remember(sim?.subId){mutableStateOf(existing.commands.joinToString("\n"))}
    var seconds by remember(sim?.subId){mutableStateOf(existing.intervalSeconds.toString())}
    var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,containerColor=colors.dialog,title={Text("查询组合 · ${sim?.let{"卡${it.slotIndex+1}"}?:"未选 SIM"}")},text={LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Text("每行一条完整指令，按顺序发送。请只放入你确认需要执行的查询指令。")}
        item{OutlinedTextField(lines,{lines=it.take(21000)},label={Text("查询指令，每行一条")},minLines=4,maxLines=8)}
        item{OutlinedTextField(seconds,{seconds=it.filter(Char::isDigit).take(3)},label={Text("发送间隔（3—120 秒）")},singleLine=true)}
        item{Text("切到后台会暂停；系统短信模式需逐条确认。可随时暂停或取消尚未发送的部分。",fontSize=12.sp,color=colors.mutedText)}
        item{HapticTextButton(onClick={lines=config?.syncCommands.orEmpty().joinToString("\n");seconds="10"}){Text("恢复规则默认组合")}}
        error?.let{item{Text(it,color=colors.danger)}}
    }},confirmButton={HapticTextButton(onClick={
        val codes=lines.lines().map(String::trim).filter(String::isNotEmpty);val interval=seconds.toIntOrNull()
        error=when {sim==null->"请先选择 SIM";codes.isEmpty()->"至少填写一条指令";codes.size>20->"每组最多 20 条指令";codes.any{it.length>1024}->"单条指令过长";interval==null||interval !in 3..120->"间隔应为 3—120 秒";else->null}
        if(error==null && sim!=null && interval!=null){CommandStore.setGroup(sim.subId,QueryGroup(codes,interval));onDismiss()}
    }){Text("保存")}},dismissButton={HapticTextButton(onClick=onDismiss){Text("取消")}})
}


internal fun categoryColor(preferences:CommandPreferences,name:String,fallback:Color):Color = preferences.categoryColors[name]?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: fallback

@Composable
private fun CategoryColorDialog(name:String,initial:String?,commands:List<UiCommand>,onDismiss:()->Unit) {
    val colors=AppTheme.colors
    var hex by remember(name){mutableStateOf(initial.orEmpty())}
    var newName by remember(name){mutableStateOf(name)}
    val customOnly=commands.filter {it.category==name}.all {it.custom}
    val categoryNames=commands.map{it.category}.distinct()
    val categoryIndex=categoryNames.indexOf(name)
    val valid=hex.matches(Regex("#[0-9a-fA-F]{6}"))
    val swatch=if(valid)Color(android.graphics.Color.parseColor(hex))else colors.button
    AlertDialog(onDismissRequest=onDismiss,containerColor=colors.dialog,title={Text("$name · 分类设置")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(customOnly)OutlinedTextField(newName,{newName=it.take(64)},label={Text("分类名称")},singleLine=true)
        Row {HapticTextButton(enabled=categoryIndex>0,onClick={CommandStore.moveCategory(name,-1,commands)}){Text("分类上移")};HapticTextButton(enabled=categoryIndex<categoryNames.lastIndex,onClick={CommandStore.moveCategory(name,1,commands)}){Text("分类下移")}}
        Text("此分类的指令按钮使用独立颜色，其他区域继续跟随主题。")
        OutlinedTextField(hex,{hex=it.take(7)},label={Text("HEX 色值，如 #FF8FBA")},singleLine=true,isError=hex.isNotEmpty()&&!valid)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("#FFD45C","#8ACDFF","#FFABD0","#91DEC2","#CBB4F4").forEach{value->HapticButton(onClick={hex=value},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(android.graphics.Color.parseColor(value)),contentColor=readableText(Color(android.graphics.Color.parseColor(value))))){if(hex==value)Text("✓")}}}
        CartoonButton("按钮预览",color=swatch,onClick={})
    }},confirmButton={HapticTextButton(enabled=(valid||hex.isBlank())&&newName.isNotBlank(),onClick={CommandStore.categoryColor(name,hex.takeIf{valid}?.uppercase());if(customOnly)CommandStore.renameCustomCategory(name,newName.trim());onDismiss()}){Text("保存")}},dismissButton={HapticTextButton(onClick={CommandStore.categoryColor(name,null);onDismiss()}){Text("跟随主题")}})
}
