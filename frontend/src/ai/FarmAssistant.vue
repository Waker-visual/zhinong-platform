<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { api } from '../api';
import { confirmAction as confirm } from '../ui/confirm';
import { createAgentRun, reduceAgentEvent, cancelAgentRun, visibleMessages, formatMessageTime, irrigationApprovalTarget, groupConversationsByDate } from './agent-events.js';
import { defaultConversationAdapter } from './agent-adapter.js';
import { isNearBottom } from './scroll.js';
import './assistant.css';
import AssistantText from './AssistantText.vue';
import AiActivityDisclosure from './AiActivityDisclosure.vue';
import AiApprovalCard from './AiApprovalCard.vue';
import AiStreamingStatus from './AiStreamingStatus.vue';
import AiMessageActions from './AiMessageActions.vue';
import AiHistoryMenu from './AiHistoryMenu.vue';
import AppIcon from '../ui/AppIcon.vue';
import IrrigationWorkspace from './IrrigationWorkspace.vue';
import { diagnosticLabels } from './diagnostics';
const props = defineProps({ farmId: String, role: String, pageRefreshing: Boolean });
const emit = defineEmits(['farm']);
const report = ref(null), conversations = ref([]), messages = ref([]), selected = ref(''), question = ref('');
const panel = ref('chat'), busy = ref(false), sending = ref(false), error = ref(''), notice = ref(''), scroller = ref(null), status = ref(null);
const refreshing = ref(false);
const refreshBlocked = computed(() => busy.value || sending.value || refreshing.value);
const interactionLocked = computed(() => refreshBlocked.value || props.pageRefreshing);
const irrigation = ref({ plots: [], devices: [], policies: [], runs: [] });
const run = ref(null);
const highlightedRunId = ref(''); // 从聊天里的灌溉审批活动点击“去确认”后，高亮对应建议
const initialLoading = ref(true); // 首次读取完成前，历史栏/消息区/分析页/灌溉页展示与最终布局等高的骨架屏，避免跳动
const followOutput = ref(true), showJump = ref(false);
const displayedMessages = computed(() => visibleMessages(messages.value, run.value));
let activeCancel = null;
// 输入框默认单行，随内容增高到上限后内部滚动；停止后把问题还原回输入框时同样要重新量高。
const composerInput = ref(null);
function resizeComposer() {
  const el=composerInput.value; if(!el) return;
  el.style.height='auto';
  el.style.height=Math.min(el.scrollHeight,180)+'px';
}
// 阶段F：历史栏分组、行内重命名、置顶/删除菜单。
const historyGroups = computed(() => groupConversationsByDate(conversations.value, new Date(nowTick.value)));
const renamingId = ref(''), renameValue = ref('');
const freshlyTitledId = ref(''); // 刚收到 conversation.titled 事件的会话 id：短暂淡入新标题，不打扰其余历史项
let freshTitleTimer = null;
const openMenuId = ref(''); // 当前展开着操作菜单的会话 id：用于让该项的 ⋮ 按钮常显（而不仅是 hover/focus-within）
const writer = computed(() => ['ADMIN','OPERATOR'].includes(props.role));
const prompts = ['结合当前作物和四情数据，今天优先做什么？','分析近7天的天气变化及对作物的影响','当前地块是否需要灌溉？请说明依据和缺失信息。','对比历史生产记录，给出下季管理建议。'];
let generation=0, timer, pendingRequest=null, alive=true;
// 相对时间（“刚刚”“N 天前”）只有在页面打开时经过的时间推进才会变化：formatMessageTime() 本身是
// 纯函数，需要一个会变化的 "now" 作为依赖才能让 Vue 重新渲染。一个所有消息共用的计时器，每分钟
// 推进一次就足够——相对时间文案本来就不需要秒级精度，不必每条消息各开一个 setInterval。
const nowTick = ref(Date.now());
let clockTimer = setInterval(() => { nowTick.value = Date.now(); }, 60000);
const time = value => value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '暂无';
async function refresh() {
  if (!props.farmId || sending.value || refreshing.value) return false;
  // 数据刷新不取消回答，也不清空可重试的运行；切换农场由组件卸载隔离上下文。
  refreshing.value = true; error.value = ''; notice.value = '';
  const g=++generation;
  try {
    // 等所有请求结束后再解除刷新状态，即使其中一项提前失败也不误报完成。
    const results=await Promise.allSettled([api('/ai/analysis?farmId='+props.farmId),api('/ai/conversations?farmId='+props.farmId),api('/ai/irrigation?farmId='+props.farmId),api('/ai/status')]);
    if(!alive || g!==generation) return false;
    const failed=results.find(result=>result.status==='rejected');
    if(failed) throw failed.reason;
    const [r,c,i,s]=results.map(result=>result.value);
    report.value=r; conversations.value=c; irrigation.value=i; status.value=s;
    if (!selected.value && c.length) await select(c[0].id);
    return alive && g===generation;
  } catch(e) { if(alive && g===generation) error.value=e.message; return false; }
  finally { if(alive && g===generation) {initialLoading.value=false;refreshing.value=false;} }
}
async function select(id) {
  if(sending.value) return;
  // 一次已经停止/出错的运行只属于它发生时所在的对话；切换到另一个对话后必须清空，
  // 不能让上一个对话的问题、空回答和状态条跟着一起显示出来。
  run.value=null; pendingRequest=null;
  selected.value=id; error.value='';
  const rows=await api(`/ai/conversations/${id}/messages`);
  if(alive && selected.value===id) {messages.value=rows; await scrollToLatest(true);}
}
function reducedMotion() { return typeof matchMedia==='function' && matchMedia('(prefers-reduced-motion: reduce)').matches; }
function handleScroll() {
  const el=scroller.value; if(!el) return;
  const near=isNearBottom(el.scrollTop, el.scrollHeight, el.clientHeight, 72);
  followOutput.value=near; showJump.value=!near;
}
async function scrollToLatest(instant=false) {
  await nextTick();
  const el=scroller.value; if(!el) return;
  el.scrollTo({top:el.scrollHeight, behavior:(instant||reducedMotion())?'auto':'smooth'});
  followOutput.value=true; showJump.value=false;
}
async function newChat() {if(sending.value) return;selected.value='';messages.value=[];question.value='';pendingRequest=null;run.value=null;}
// 阶段F：删除从历史项的操作菜单发起，不再是选中对话下方的独立链接——可以删除任意一条
// （不一定是当前选中的那条）。删掉的若恰好是当前对话，行为和原来的 removeChat 一致：回到新建对话。
async function deleteConversation(id) {
  if(sending.value) return;
  if(!await confirm({title:'删除这条对话？',message:'只删除当前账号的这段对话，灌溉审计记录会保留。',confirmLabel:'删除对话',danger:true})) return;
  await perform(async()=>{
    await api('/ai/conversations/'+id,'DELETE');
    if(selected.value===id) await newChat();
    await refresh();
  });
}
// 行内重命名：点击菜单“重命名”后把标签换成输入框；Enter 保存、Esc 取消、失焦保存。
const renameInput = ref(null);
function startRename(c) { renamingId.value=c.id; renameValue.value=c.title; nextTick(()=>{renameInput.value?.focus();renameInput.value?.select();}); }
function cancelRename() { renamingId.value=''; renameValue.value=''; }
async function commitRename(id) {
  if(renamingId.value!==id) return;
  const title=renameValue.value.trim();
  cancelRename();
  if(!title || title===conversations.value.find(c=>c.id===id)?.title) return;
  try {
    const result=await api('/ai/conversations/'+id+'/rename','POST',{title});
    const row=conversations.value.find(c=>c.id===id);
    if(row) {row.title=result.title; row.titleSource=result.titleSource;}
  } catch(e) {error.value=e.message;}
}
async function togglePin(c) {
  try {
    await api('/ai/conversations/'+c.id+'/pin','POST',{pinned:!c.pinnedAt});
    conversations.value=await api('/ai/conversations?farmId='+props.farmId);
  } catch(e) {error.value=e.message;}
}
// conversation.titled 事件到达时，实时把历史栏里对应会话的标题换成模型生成的（或回退）标题，
// 不用重新拉取整个列表；短暂加一个淡入 class 提示用户“标题刚刚变了”，减弱动态下由 CSS 直接跳过。
// 新建的会话立即进入历史栏（今天组），不必等整轮回答结束后重新拉取列表才出现。
function addConversationRow(c) {
  if(conversations.value.some(row=>row.id===c.id)) return;
  const now=new Date().toISOString();
  conversations.value=[{titleSource:'auto',pinnedAt:null,createdAt:now,updatedAt:now,...c},...conversations.value];
}
function applyFreshTitle(conversationId,title) {
  const row=conversations.value.find(c=>c.id===conversationId);
  if(row) {row.title=title; row.titleSource='auto';}
  else addConversationRow({id:conversationId,title});
  freshlyTitledId.value=conversationId;
  clearTimeout(freshTitleTimer);
  freshTitleTimer=setTimeout(()=>{if(freshlyTitledId.value===conversationId) freshlyTitledId.value='';},1200);
}
function onHistoryMenuAction(c,action) {
  if(action==='rename') startRename(c);
  else if(action==='pin') togglePin(c);
  else if(action==='delete') deleteConversation(c.id);
}
async function send(text=question.value) {
  text=text.trim(); if(!text || interactionLocked.value || text.length>2000) return;
  sending.value=true; error.value=''; question.value='';
  const controller=new AbortController();
  let cancelled=false;
  activeCancel=()=>{cancelled=true; controller.abort(); if(run.value) run.value=cancelAgentRun(run.value);};
  try {
    if(!selected.value) {const c=await api('/ai/conversations','POST',{farmId:props.farmId});selected.value=c.id;addConversationRow(c);}
    const id=selected.value;
    if(!pendingRequest || pendingRequest.text!==text || pendingRequest.id!==id) pendingRequest={text,id,requestId:crypto.randomUUID()};
    run.value=createAgentRun(text,pendingRequest.requestId);
    await scrollToLatest();
    for await (const event of defaultConversationAdapter({farmId:props.farmId,conversationId:id,question:text,requestId:pendingRequest.requestId,signal:controller.signal})) {
      if(!alive || cancelled) break;
      if(event.type==='conversation.titled') { applyFreshTitle(event.conversationId,event.title); continue; }
      run.value=reduceAgentEvent(run.value,event);
      if(event.type==='message.delta' && followOutput.value) await scrollToLatest();
    }
    // 取消：真实流式连接会被 controller.abort() 立即中断，服务端检测到断开后不会写入成功回答；
    // 如果当时已经回退到旧的同步接口，那次请求仍可能在后台跑完并写入，下次重新打开此对话会看到它。
    // 停止后把问题文字还原回输入框——用户停下来通常是想修改问题重新问，不是想把它丢掉。
    if(cancelled || !alive) { if(cancelled) question.value=text; return; }
    if(run.value.status==='error') { question.value=text; return; } // 保留已生成正文与活动摘要，交由 retry() 重试
    pendingRequest=null;
    const hadApproval=run.value.activities.some(a=>a.kind==='approval');
    messages.value=await api(`/ai/conversations/${id}/messages`); conversations.value=await api('/ai/conversations?farmId='+props.farmId);
    run.value=null;
    // 回答里出现了审批活动：同步一次灌溉数据，让卡片的有效性核对与“去确认”高亮基于最新建议
    if(hadApproval) refreshIrrigation().catch(()=>{});
    if(followOutput.value) await scrollToLatest();
  } catch(e) {
    if(alive) {
      error.value=e.message; question.value=text;
      if(run.value) run.value={...run.value,status:'error',error:e.message};
    }
  } finally {sending.value=false; activeCancel=null;}
}
function retry() { if(run.value && !sending.value) send(run.value.question); }
function cancel() { activeCancel?.(); }
function onActivityToggle() { /* 手动展开/收起由 AiActivityDisclosure 自行记忆本轮运行内的状态 */ }
// 聊天里的灌溉审批卡片点“去确认”：只是导航到既有灌溉管理页签并高亮对应建议，批准/取消仍然
// 只能通过该页签原有的确认对话框完成——聊天本身不审批、不下发任何设备指令。
// 建议可能由自动模式或其他成员生成，本页加载时的灌溉数据未必包含它：先刷新再切页高亮，并滚动到对应建议。
async function onViewApproval(target) {
  if (!target || target.type !== 'irrigation-run') return;
  try { await refreshIrrigation(); } catch { /* 刷新失败时仍按已有数据跳转 */ }
  panel.value = 'irrigation';
  highlightedRunId.value = target.id;
  await nextTick();
  document.querySelector('.ai-run-highlight')?.scrollIntoView({block:'center',behavior:reducedMotion()?'auto':'smooth'});
}
// 时间线下方的审批卡片只在建议仍然有效（PROPOSED）时展示；一旦在灌溉管理页签批准/取消/过期，
// 已持久化的活动摘要是一张静态快照不会跟着变，这里用当前已加载的 irrigation.runs 再核对一次，
// 避免聊天里一直挂着一个其实早就处理完的“去确认”卡片。找不到对应建议时（例如还没加载）默认展示。
function approvalActivities(activities) {
  return (activities || []).filter((a) => {
    const target = irrigationApprovalTarget(a);
    if (!target) return false;
    const run = irrigation.value.runs.find((r) => r.id === target.id);
    return !run || run.status === 'PROPOSED';
  });
}
// 消息时间：只有已持久化的消息才有 createdAt；流式占位消息还没有，不显示时间。
function messageTime(m) {
  // 读取 nowTick.value 建立响应式依赖：每分钟它变化一次，模板里显示的“刚刚/N 分钟前”才会跟着刷新，
  // 而不是在页面打开的整个生命周期里都停留在首次渲染那一刻算出的文案上。
  return m.createdAt ? formatMessageTime(m.createdAt, nowTick.value) : null;
}
// 从某条消息“分支”出一个新对话后：刷新历史栏并直接选中新对话，和新建对话的体验一致。
async function onBranched(newId) {
  await perform(async () => {
    conversations.value = await api('/ai/conversations?farmId=' + props.farmId);
    await select(newId);
  });
}
async function perform(work) {if(busy.value) return;busy.value=true;error.value='';notice.value='';try{await work();}catch(e){error.value=e.message;}finally{busy.value=false;}}
async function refreshIrrigation() {
  const farm=props.farmId;const data=await api('/ai/irrigation?farmId='+farm);
  if(alive&&farm===props.farmId)irrigation.value=data;
}
const weatherCharts=computed(()=>[
  ['TEMPERATURE','空气温度','℃'],['HUMIDITY','空气湿度','%'],['WIND_SPEED','风速','m/s']
].map(([metric,label,unit])=>{
  const rows=(report.value?.weather||[]).filter(r=>r.metric===metric);
  const values=rows.map(r=>Number(r.average)),lo=Math.min(...values),hi=Math.max(...values),range=hi-lo||1;
  return {metric,label,unit,rows,latest:values.at(-1),delta:values.length>1?(values.at(-1)-values[0]).toFixed(1):null,
    points:values.map((v,i)=>`${10+i*260/Math.max(1,values.length-1)},${72-(v-lo)*50/range}`).join(' ')};
}));
defineExpose({refresh, refreshing, refreshBlocked, sending});
watch(()=>props.farmId,refresh,{immediate:true});
watch([question,panel],resizeComposer,{flush:'post'});
timer=setInterval(async()=>{if(panel.value==='irrigation' && !busy.value && props.farmId){try{await refreshIrrigation();}catch{/* next refresh shows errors */}}},10000);
onUnmounted(()=>{alive=false;activeCancel?.();generation++;clearInterval(timer);clearInterval(clockTimer);clearTimeout(freshTitleTimer);});
</script>

<template>
  <section class="farm-assistant" v-if="farmId">
    <div class="ai-context">
      <p><strong>{{report?.farmName || '正在读取农场'}}</strong><span>虚构学术演示数据</span></p>
      <span v-if="status===null" class="ai-model-state ai-model-state-skeleton skeleton" aria-hidden="true"></span>
      <span v-else class="ai-model-state">{{status.llm?`云端模型 · ${status.model}`:'规则分析模式'}}</span>
    </div>
    <nav class="ai-tabs" aria-label="助手功能">
      <button v-for="t in [['chat','农事对话'],['analysis','天气与四情'],['irrigation','灌溉管理']]" :key="t[0]" :aria-pressed="panel===t[0]" @click="panel=t[0]">{{t[1]}}</button>
    </nav>
    <p v-if="error" class="error" role="alert">{{error}}</p><p v-if="notice" role="status" class="ai-notice">{{notice}}</p>
    <div v-if="panel==='chat'" class="ai-chat-layout">
      <aside class="ai-history"><button class="primary" :disabled="interactionLocked" @click="newChat">＋ 新建对话</button>
        <p class="muted">我的农事对话</p>
        <div v-if="initialLoading" class="ai-history-skeleton" aria-hidden="true"><span class="skeleton skeleton-block" v-for="n in 4" :key="n"></span></div>
        <template v-else>
          <template v-for="group in historyGroups" :key="group.key">
            <p class="ai-history-group-label">{{group.label}}</p>
            <div v-for="c in group.items" :key="c.id" class="ai-history-item" :class="{selected:selected===c.id,'menu-open':openMenuId===c.id}">
              <input v-if="renamingId===c.id" :ref="el=>{if(el) renameInput=el;}" class="ai-history-rename" :value="renameValue" maxlength="40"
                @input="e=>renameValue=e.target.value" @keydown.enter="commitRename(c.id)" @keydown.esc="cancelRename"
                @blur="commitRename(c.id)" @click.stop />
              <button v-else :disabled="interactionLocked" :aria-current="selected===c.id?'true':undefined" :title="`创建于 ${time(c.createdAt)}`"
                @click="perform(()=>select(c.id))">
                <span class="ai-history-title" :class="{fresh:freshlyTitledId===c.id}">{{c.title}}</span>
              </button>
              <AiHistoryMenu v-if="renamingId!==c.id" :pinned="!!c.pinnedAt" :label="`“${c.title}”的更多操作`"
                @rename="onHistoryMenuAction(c,'rename')" @pin="onHistoryMenuAction(c,'pin')" @delete="onHistoryMenuAction(c,'delete')"
                @open-change="v=>openMenuId=v?c.id:''" />
            </div>
          </template>
          <p v-if="!conversations.length" class="muted">对话会自动保存。不同农场与账号分别管理。</p>
        </template>
      </aside>
      <div class="ai-chat-main">
        <div class="ai-messages-wrap">
        <div ref="scroller" class="ai-messages" role="log" aria-label="农事对话记录" :aria-busy="!!run || initialLoading" @scroll="handleScroll">
          <div v-if="initialLoading" class="ai-message-skeleton" aria-hidden="true"><span class="skeleton"></span><span class="skeleton" style="width:85%"></span><span class="skeleton" style="width:60%"></span></div>
          <template v-else>
            <div v-if="!displayedMessages.length" class="ai-welcome"><span class="ai-monogram">禾</span><h3>今天想了解农场的什么？</h3><p>我会结合当前农场的种植、气象、监测和生产记录，为你梳理依据与行动建议；发送问题后会先读取当前农场资料。</p>
              <div class="ai-prompts"><button v-for="p in prompts" :key="p" :disabled="interactionLocked" @click="send(p)"><AppIcon name="send" />{{p}}</button></div>
            </div>
            <article v-for="(m,index) in displayedMessages" :key="m.id||('tmp-'+index)" class="ai-message" :class="m.role">
              <small>{{m.role==='user'?'你':'农场 AI 助手'}}<span v-if="m.mode==='rule'" class="ai-mode-badge">规则回退</span></small>
              <template v-if="m.pending">
                <AiActivityDisclosure :activities="m.run.activities" :run-status="m.run.status" @toggle="onActivityToggle" />
                <AiApprovalCard v-for="a in approvalActivities(m.run.activities)" :key="a.id" :activity="a" :writer="writer" @view-approval="onViewApproval" />
                <div class="ai-message-text">
                  <AssistantText v-if="m.run.text" :text="m.run.text" :streaming="m.run.status==='running'" />
                  <div v-else class="ai-skeleton-lines" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton" style="width:42%"></span></div>
                </div>
                <AiStreamingStatus :status="m.run.status" :diagnostic="m.run.error" :can-retry="m.run.status==='error'" :using-sync-fallback="m.run.adapter==='sync'" @retry="retry" />
              </template>
              <template v-else>
                <AiActivityDisclosure v-if="m.activities?.length" :activities="m.activities" run-status="completed" />
                <AiApprovalCard v-for="a in approvalActivities(m.activities)" :key="a.activityId" :activity="a" :writer="writer" @view-approval="onViewApproval" />
                <div class="ai-message-text"><AssistantText v-if="m.role==='assistant'" :text="m.content"/><template v-else>{{m.content}}</template></div>
                <p v-if="m.diagnostic && m.diagnostic!=='OK'" class="ai-diagnostic-note"><AppIcon name="info" />{{diagnosticLabels[m.diagnostic] || '模型暂不可用'}}</p>
                <AiMessageActions :id="m.id" :text="m.content" :role="m.role" :time="messageTime(m)" :conversation-id="selected" @branched="onBranched" />
              </template>
            </article>
          </template>
        </div>
        <Transition name="ai-jump"><button v-if="showJump" type="button" class="ai-jump-latest" @click="scrollToLatest()"><AppIcon name="arrowDown" />回到最新</button></Transition>
        </div>
        <form class="ai-composer" @submit.prevent="send()"><label class="sr-only" for="ai-question">农事问题</label><textarea id="ai-question" ref="composerInput" v-model="question" rows="1" maxlength="2000" :disabled="sending" placeholder="询问农事、分析天气，或了解作物生长情况…" @keydown.enter.exact="e=>{if(!e.isComposing){e.preventDefault();send();}}"></textarea>
          <small v-if="question.length>=1800" class="ai-composer-count">{{question.length}}/2000</small>
          <button v-if="sending" type="button" class="ai-composer-send" aria-label="停止生成" @click="cancel"><AppIcon name="stop" /></button>
          <button v-else class="ai-composer-send" :disabled="interactionLocked || !question.trim()" aria-label="发送"><AppIcon name="arrowUp" /></button>
        </form>
        <p class="ai-footnote"><span class="ai-composer-hint">Enter 发送 · Shift + Enter 换行。</span>发送问题时，当前农场摘要与最近对话将交由已配置的模型服务处理。回答供农事参考，聊天不会直接控制设备。</p>
      </div>
    </div>
    <div v-else-if="panel==='analysis' && initialLoading" class="ai-analysis ai-panel-skeleton" aria-hidden="true">
      <div class="ai-section-head"><span class="skeleton" style="width:220px"></span></div>
      <div class="ai-condition-grid"><article v-for="n in 4" :key="n"><span class="skeleton skeleton-block" style="height:76px"></span></article></div>
      <div class="ai-weather-grid"><article v-for="n in 3" :key="n"><span class="skeleton skeleton-block" style="height:120px"></span></article></div>
    </div>
    <div v-else-if="panel==='analysis' && report" class="ai-analysis">
      <div class="ai-section-head"><div><h3>农情四情诊断</h3><p>更新于 {{time(report.generatedAt)}} · {{report.freshMeasurements}} 条新鲜指标读数</p></div><button @click="panel='chat';send(prompts[0])" :disabled="interactionLocked">请助手解读 →</button></div>
      <div class="ai-condition-grid"><article v-for="c in report.conditions" :key="c.code"><div><h3>{{c.name}}</h3><span>{{c.state}}</span></div><p>{{c.advice}}</p></article></div>
      <div v-if="report.cautions?.length" class="ai-notice"><p v-for="item in report.cautions" :key="item">{{item}}</p><small>{{report.thresholdNotice}}</small></div>
      <p class="ai-footnote">当前待执行/进行中任务 {{report.tasks?.pending}} 项，其中逾期 {{report.tasks?.overdue}} 项。虫情近7天有 {{report.pestHistory?.length}} 天记录，可在对话中要求进一步解读。</p>
      <div class="ai-section-head"><div><h3>近7天气象趋势</h3><p>{{report.weatherNotice}}</p></div></div>
      <div class="ai-weather-grid"><article v-for="w in weatherCharts" :key="w.metric"><span>{{w.label}} · 日均值</span><h3>{{w.latest ?? '—'}} <small>{{w.unit}}</small></h3><p v-if="w.delta!==null">首末日均值变化 {{Number(w.delta)>0?'+':''}}{{w.delta}} {{w.unit}}</p><p v-else>暂无足够观测数据</p><svg v-if="w.rows.length" viewBox="0 0 280 90" role="img" :aria-label="w.label+'近7天日均值趋势'"><polyline :points="w.points" fill="none" stroke="currentColor" stroke-width="2.5" /></svg><small v-if="w.rows.length">{{String(w.rows[0].date).slice(0,10)}} — {{String(w.rows.at(-1).date).slice(0,10)}}</small></article></div>
      <details class="disclosure"><summary>查看气象数据明细与来源</summary><div class="disclosure-content"><div class="ai-table-wrap"><table><thead><tr><th>日期</th><th>指标</th><th>均值</th><th>最小</th><th>最大</th><th>样本</th></tr></thead><tbody><tr v-for="w in report.weather" :key="w.date+w.metric"><td>{{String(w.date).slice(0,10)}}</td><td>{{({TEMPERATURE:'温度 ℃',HUMIDITY:'湿度 %',WIND_SPEED:'风速 m/s',RAINFALL:'累计雨量读数 mm'})[w.metric]}}</td><td>{{w.average}}</td><td>{{w.min}}</td><td>{{w.max}}</td><td>{{w.samples}}</td></tr></tbody></table></div><p>{{report.sources.join('；')}}</p></div></details>
      <div class="ai-section-head"><h3>当前在种作物与观测</h3></div>
      <p v-if="!report.crops.length">没有有效日期内的在种计划，无法判断当前生育阶段。</p>
      <div class="ai-crops"><article v-for="c in report.crops" :key="c.plotId+c.startDate"><h4>{{c.plotName}} · {{c.crop}}</h4><p>{{c.variety}} · {{c.areaMu}} 亩</p><small>{{c.startDate}} 至 {{c.endDate}}（计划日期）</small></article></div>
      <div class="ai-table-wrap"><table><thead><tr><th>测点</th><th>地块</th><th>指标</th><th>最新值</th><th>状态</th><th>观测时间</th></tr></thead><tbody><tr v-for="s in report.sensors" :key="s.deviceId+s.metric"><td>{{s.deviceName}}</td><td>{{s.plotName||'农场级'}}</td><td>{{({SOIL_MOISTURE:'土壤水分 %',TEMPERATURE:'温度 ℃',HUMIDITY:'湿度 %',WIND_SPEED:'风速 m/s',RAINFALL:'累计雨量 mm',PEST_COUNT:'虫情计数'})[s.metric]}}</td><td>{{s.value}}</td><td>{{s.fresh?'新鲜':'已过期'}}</td><td>{{time(s.time)}}</td></tr></tbody></table></div>
      <p class="ai-footnote">历史生产共 {{report.production.records}} 条，累计 {{report.production.yieldKg}} kg。跨年度累计不能视作单季亩产；图表及规则不代表经过验证的作物模型。</p>
    </div>
    <div v-else-if="panel==='irrigation' && initialLoading" class="ai-irrigation ai-panel-skeleton" aria-hidden="true">
      <div class="ai-section-head"><span class="skeleton" style="width:220px"></span></div>
      <div class="ai-policy-grid"><article v-for="n in 3" :key="n"><span class="skeleton skeleton-block" style="height:140px"></span></article></div>
    </div>
    <IrrigationWorkspace v-else-if="panel==='irrigation'" :key="farmId" :farm-id="farmId" :role="role" :data="irrigation" :highlighted-run-id="highlightedRunId" @updated="irrigation=$event" @farm="emit('farm',$event)" />
  </section>
  <section v-else class="panel"><p>请选择一座农场，开始使用 AI 助手。</p></section>
</template>
