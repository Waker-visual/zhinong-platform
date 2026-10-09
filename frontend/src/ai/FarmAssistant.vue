<script setup>
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue';
import { api } from '../api';
import { confirmAction as confirm } from '../ui/confirm';
import { createAgentRun, reduceAgentEvent, cancelAgentRun, visibleMessages, shouldShowCaret, formatMessageTime, irrigationApprovalTarget } from './agent-events.js';
import { defaultConversationAdapter } from './agent-adapter.js';
import { isNearBottom } from './scroll.js';
import './assistant.css';
import AssistantText from './AssistantText.vue';
import AiActivityDisclosure from './AiActivityDisclosure.vue';
import AiApprovalCard from './AiApprovalCard.vue';
import AiStreamingStatus from './AiStreamingStatus.vue';
import AiMessageActions from './AiMessageActions.vue';
import AppIcon from '../ui/AppIcon.vue';
import { diagnosticLabels } from './diagnostics';
const props = defineProps({ farmId: String, role: String, revision: Number });
const report = ref(null), conversations = ref([]), messages = ref([]), selected = ref(''), question = ref('');
const panel = ref('chat'), busy = ref(false), sending = ref(false), error = ref(''), notice = ref(''), scroller = ref(null), status = ref(null);
const irrigation = ref({ plots: [], devices: [], policies: [], runs: [] });
const run = ref(null);
const highlightedRunId = ref(''); // 从聊天里的灌溉审批活动点击“去确认”后，高亮对应建议
const initialLoading = ref(true); // 首次读取完成前，历史栏/消息区/分析页/灌溉页展示与最终布局等高的骨架屏，避免跳动
const followOutput = ref(true), showJump = ref(false);
const displayedMessages = computed(() => visibleMessages(messages.value, run.value));
let activeCancel = null;
const editing = ref(false);
const policy = reactive({ plotId: '', sensorId: '', pumpId: '', mode: 'MANUAL', thresholdValue: 30, durationSeconds: 60, cooldownMinutes: 120, dailyLimit: 3, revision: 0 });
const writer = computed(() => ['ADMIN','OPERATOR'].includes(props.role));
const sensors = computed(() => irrigation.value.devices.filter(d => d.deviceType !== 'PUMP' && d.plotId === policy.plotId));
const pumps = computed(() => irrigation.value.devices.filter(d => d.deviceType === 'PUMP'));
const states = { PROPOSED:'等待人工确认', RUNNING:'模拟灌溉中', COMPLETED:'已停止', CANCELLED:'已取消', EXPIRED:'已过期' };
const prompts = ['结合当前作物和四情数据，今天优先做什么？','分析近7天的天气变化及对作物的影响','当前地块是否需要灌溉？请说明依据和缺失信息。','对比历史生产记录，给出下季管理建议。'];
let generation=0, timer, pendingRequest=null, alive=true;
const time = value => value ? new Date(value).toLocaleString('zh-CN',{hour12:false}) : '暂无';
async function refresh() {
  if (!props.farmId) return;
  const g=++generation;
  try {
    const [r,c,i,s]=await Promise.all([api('/ai/analysis?farmId='+props.farmId),api('/ai/conversations?farmId='+props.farmId),api('/ai/irrigation?farmId='+props.farmId),api('/ai/status')]);
    if(!alive || g!==generation) return;
    report.value=r; conversations.value=c; irrigation.value=i; status.value=s;
    if (!selected.value && c.length) await select(c[0].id);
  } catch(e) { if(alive && g===generation) error.value=e.message; }
  finally { if(alive && g===generation) initialLoading.value=false; }
}
async function select(id) {
  if(sending.value) return;
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
async function newChat() {if(sending.value) return;selected.value='';messages.value=[];question.value='';pendingRequest=null;}
async function removeChat() {
  if(!selected.value || sending.value) return;
  if(!await confirm({title:'删除当前对话？',message:'只删除当前账号的这段对话，灌溉审计记录会保留。',confirmLabel:'删除对话',danger:true})) return;
  await perform(async()=>{await api('/ai/conversations/'+selected.value,'DELETE'); await newChat(); await refresh();});
}
async function send(text=question.value) {
  text=text.trim(); if(!text || sending.value || text.length>2000) return;
  sending.value=true; error.value=''; question.value='';
  const controller=new AbortController();
  let cancelled=false;
  activeCancel=()=>{cancelled=true; controller.abort(); if(run.value) run.value=cancelAgentRun(run.value);};
  try {
    if(!selected.value) {const c=await api('/ai/conversations','POST',{farmId:props.farmId});selected.value=c.id;}
    const id=selected.value;
    if(!pendingRequest || pendingRequest.text!==text || pendingRequest.id!==id) pendingRequest={text,id,requestId:crypto.randomUUID()};
    run.value=createAgentRun(text,pendingRequest.requestId);
    await scrollToLatest();
    for await (const event of defaultConversationAdapter({farmId:props.farmId,conversationId:id,question:text,requestId:pendingRequest.requestId,signal:controller.signal})) {
      if(!alive || cancelled) break;
      run.value=reduceAgentEvent(run.value,event);
      if(event.type==='message.delta' && followOutput.value) await scrollToLatest();
    }
    // 取消：真实流式连接会被 controller.abort() 立即中断，服务端检测到断开后不会写入成功回答；
    // 如果当时已经回退到旧的同步接口，那次请求仍可能在后台跑完并写入，下次重新打开此对话会看到它。
    if(cancelled || !alive) return;
    if(run.value.status==='error') { question.value=text; return; } // 保留已生成正文与活动摘要，交由 retry() 重试
    pendingRequest=null;
    messages.value=await api(`/ai/conversations/${id}/messages`); conversations.value=await api('/ai/conversations?farmId='+props.farmId);
    run.value=null;
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
function onViewApproval(target) {
  if (!target || target.type !== 'irrigation-run') return;
  panel.value = 'irrigation';
  highlightedRunId.value = target.id;
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
  return m.createdAt ? formatMessageTime(m.createdAt) : null;
}
// 从某条消息“分支”出一个新对话后：刷新历史栏并直接选中新对话，和新建对话的体验一致。
async function onBranched(newId) {
  await perform(async () => {
    conversations.value = await api('/ai/conversations?farmId=' + props.farmId);
    await select(newId);
  });
}
async function perform(work) {if(busy.value) return;busy.value=true;error.value='';notice.value='';try{await work();}catch(e){error.value=e.message;}finally{busy.value=false;}}
function editPolicy(plotId) {
  const p=irrigation.value.policies.find(p=>p.plotId===plotId);
  Object.assign(policy,{plotId,sensorId:'',pumpId:'',mode:'MANUAL',thresholdValue:30,durationSeconds:60,cooldownMinutes:120,dailyLimit:3,revision:0},p?{sensorId:p.sensorId,pumpId:p.pumpId,mode:p.mode,thresholdValue:p.thresholdValue,durationSeconds:p.durationSeconds,cooldownMinutes:p.cooldownMinutes,dailyLimit:p.dailyLimit,revision:p.revision}:{});
  if(!policy.sensorId) policy.sensorId=sensors.value[0]?.id||'';
  if(!policy.pumpId) policy.pumpId=pumps.value.find(p=>p.plotId===plotId)?.id||pumps.value[0]?.id||'';
  editing.value=true;
}
async function savePolicy() {
  if(policy.mode==='AUTO' && !await confirm({title:'启用此地块的自动模拟灌溉？',message:`土壤水分低于 ${policy.thresholdValue}% 时将自动检查并启动，每次 ${policy.durationSeconds} 秒，每天最多 ${policy.dailyLimit} 次。阈值是教学参数，需要按作物校准。`,confirmLabel:'启用自动模式'})) return;
  await perform(async()=>{irrigation.value=await api('/ai/irrigation/policy','PUT',{...policy,farmId:props.farmId});editing.value=false;notice.value='策略已保存';});
}
async function propose(id) {await perform(async()=>{await api(`/ai/irrigation/plots/${id}/propose`,'POST');await refresh();notice.value='建议已生成，请核对原因、设备和时长后确认。';});}
async function act(run,action) {
  await perform(async()=>{const result=await api(`/ai/irrigation/runs/${run.id}/${action}`,'POST');await refresh();notice.value=result.status==='RUNNING'?'模拟灌溉已启动，到时自动停泵。':`当前状态：${states[result.status]||result.status}。`;});
}
function plotName(id){return irrigation.value.plots.find(p=>p.id===id)?.name||id;}
const weatherCharts=computed(()=>[
  ['TEMPERATURE','空气温度','℃'],['HUMIDITY','空气湿度','%'],['WIND_SPEED','风速','m/s']
].map(([metric,label,unit])=>{
  const rows=(report.value?.weather||[]).filter(r=>r.metric===metric);
  const values=rows.map(r=>Number(r.average)),lo=Math.min(...values),hi=Math.max(...values),range=hi-lo||1;
  return {metric,label,unit,rows,latest:values.at(-1),delta:values.length>1?(values.at(-1)-values[0]).toFixed(1):null,
    points:values.map((v,i)=>`${10+i*260/Math.max(1,values.length-1)},${72-(v-lo)*50/range}`).join(' ')};
}));
watch(()=>[props.farmId,props.revision],refresh,{immediate:true});
timer=setInterval(async()=>{if(panel.value==='irrigation' && !busy.value && props.farmId){try{irrigation.value=await api('/ai/irrigation?farmId='+props.farmId);}catch{/* next refresh shows errors */}}},10000);
onUnmounted(()=>{alive=false;generation++;clearInterval(timer);});
</script>

<template>
  <section class="farm-assistant" v-if="farmId">
    <header class="ai-hero">
      <div><small>智禾 · 农场 AI 助手</small><h2>让每一次农事，都有数据依据。</h2><p>{{report?.farmName || '正在读取农场'}} · 虚构学术演示数据</p></div>
      <span class="ai-model-state">{{status?.llm?'模型服务已配置':'规则分析模式'}}</span>
    </header>
    <nav class="ai-tabs" aria-label="助手功能">
      <button v-for="t in [['chat','农事对话'],['analysis','天气与四情'],['irrigation','灌溉管理']]" :key="t[0]" :aria-pressed="panel===t[0]" @click="panel=t[0]">{{t[1]}}</button>
      <button class="ai-refresh" :disabled="busy || sending" @click="perform(refresh)">刷新数据</button>
    </nav>
    <p v-if="error" class="error" role="alert">{{error}}</p><p v-if="notice" role="status" class="ai-notice">{{notice}}</p>
    <div v-if="panel==='chat'" class="ai-chat-layout">
      <aside class="ai-history"><button class="primary" :disabled="sending" @click="newChat">＋ 新建对话</button>
        <p class="muted">我的农事对话</p>
        <div v-if="initialLoading" class="ai-history-skeleton" aria-hidden="true"><span class="skeleton skeleton-block" v-for="n in 4" :key="n"></span></div>
        <template v-else>
          <button v-for="c in conversations" :key="c.id" :disabled="sending" :aria-current="selected===c.id?'true':undefined" @click="perform(()=>select(c.id))"><span>{{c.title}}</span><small>{{time(c.updatedAt)}}</small></button>
          <p v-if="!conversations.length" class="muted">对话会自动保存。不同农场与账号分别管理。</p>
        </template>
        <button v-if="selected" :disabled="sending" class="text-button" @click="removeChat">删除当前对话</button>
      </aside>
      <div class="ai-chat-main">
        <div class="ai-messages-wrap">
        <div ref="scroller" class="ai-messages" role="log" aria-label="农事对话记录" :aria-busy="!!run || initialLoading" @scroll="handleScroll">
          <div v-if="initialLoading" class="ai-message-skeleton" aria-hidden="true"><span class="skeleton"></span><span class="skeleton" style="width:85%"></span><span class="skeleton" style="width:60%"></span></div>
          <template v-else>
            <div v-if="!displayedMessages.length" class="ai-welcome"><span class="ai-monogram">禾</span><h3>今天想了解农场的什么？</h3><p>我会结合当前农场的种植、气象、监测和生产记录，为你梳理依据与行动建议；发送问题后会先读取当前农场资料。</p>
              <div class="ai-prompts"><button v-for="p in prompts" :key="p" :disabled="sending" @click="send(p)"><AppIcon name="send" />{{p}}</button></div>
            </div>
            <article v-for="(m,index) in displayedMessages" :key="m.id||('tmp-'+index)" class="ai-message" :class="m.role">
              <small>{{m.role==='user'?'你':'农场 AI 助手'}}<span v-if="m.mode==='rule'" class="ai-mode-badge">规则回退</span></small>
              <template v-if="m.pending">
                <AiActivityDisclosure :activities="m.run.activities" :run-status="m.run.status" @toggle="onActivityToggle" />
                <AiApprovalCard v-for="a in approvalActivities(m.run.activities)" :key="a.id" :activity="a" :writer="writer" @view-approval="onViewApproval" />
                <div class="ai-message-text">
                  <template v-if="m.run.text"><AssistantText :text="m.run.text" /><span v-if="shouldShowCaret(m.run.status,m.run.text)" class="ai-caret" aria-hidden="true"></span></template>
                  <div v-else class="ai-skeleton-lines" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton" style="width:42%"></span></div>
                </div>
                <AiStreamingStatus :status="m.run.status" :diagnostic="m.run.error" :can-retry="m.run.status==='error'" @retry="retry" @cancel="cancel" />
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
        <form class="ai-composer" @submit.prevent="send()"><label class="sr-only" for="ai-question">农事问题</label><textarea id="ai-question" v-model="question" rows="3" maxlength="2000" :disabled="sending" placeholder="询问农事、分析天气，或了解作物生长情况…" @keydown.enter.exact="e=>{if(!e.isComposing){e.preventDefault();send();}}"></textarea><div><small>Enter 发送 · Shift + Enter 换行 · {{question.length}}/2000</small><button class="primary" :disabled="sending || !question.trim()"><template v-if="!sending"><AppIcon name="send" /></template>{{sending?'正在回答…':'发送'}}</button></div></form>
        <p class="ai-footnote">发送问题时，当前农场摘要与最近对话将交由已配置的模型服务处理。回答供农事参考，聊天不会直接控制设备。</p>
      </div>
    </div>
    <div v-else-if="panel==='analysis' && initialLoading" class="ai-analysis ai-panel-skeleton" aria-hidden="true">
      <div class="ai-section-head"><span class="skeleton" style="width:220px"></span></div>
      <div class="ai-condition-grid"><article v-for="n in 4" :key="n"><span class="skeleton skeleton-block" style="height:76px"></span></article></div>
      <div class="ai-weather-grid"><article v-for="n in 3" :key="n"><span class="skeleton skeleton-block" style="height:120px"></span></article></div>
    </div>
    <div v-else-if="panel==='analysis' && report" class="ai-analysis">
      <div class="ai-section-head"><div><h3>农情四情诊断</h3><p>更新于 {{time(report.generatedAt)}} · {{report.freshMeasurements}} 条新鲜指标读数</p></div><button @click="panel='chat';send(prompts[0])" :disabled="sending">请助手解读 →</button></div>
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
    <div v-else-if="panel==='irrigation'" class="ai-irrigation">
      <div class="ai-section-head"><div><h3>先审阅，再灌溉</h3><p>默认人工确认。管理员可按地块启用自动模式，所有启动与停止均有记录。</p></div></div>
      <p class="ai-notice">当前仅执行模拟设备。每次 10–300 秒，冷却至少30分钟，每天最多6次；数据过期、有故障或没有有效在种作物时不启动。实体网关尚未接入限时停泵协议。</p>
      <div class="ai-policy-grid"><article v-for="p in irrigation.plots" :key="p.id"><h4>{{p.name}}</h4><template v-if="irrigation.policies.find(s=>s.plotId===p.id)"><p>{{irrigation.policies.find(s=>s.plotId===p.id).mode==='AUTO'?'自动模拟模式':'人工确认模式'}}</p><p>启灌阈值 {{irrigation.policies.find(s=>s.plotId===p.id).thresholdValue}}% · {{irrigation.policies.find(s=>s.plotId===p.id).durationSeconds}} 秒</p><small>{{irrigation.policies.find(s=>s.plotId===p.id).lastResult}}</small><button v-if="writer" :disabled="busy" @click="propose(p.id)">检查并生成建议</button></template><p v-else>尚未配置测点与水泵</p><button v-if="role==='ADMIN'" class="text-button" @click="editPolicy(p.id)">配置策略</button></article></div>
      <form v-if="editing && role==='ADMIN'" class="ai-policy-form" @submit.prevent="savePolicy"><h3>{{plotName(policy.plotId)}} · 灌溉策略</h3><div class="ai-form-grid">
        <label>土壤测点<select v-model="policy.sensorId" required><option value="">请选择</option><option v-for="d in sensors" :key="d.id" :value="d.id">{{d.name}}</option></select></label>
        <label>受控水泵<select v-model="policy.pumpId" required><option value="">请选择</option><option v-for="d in pumps" :key="d.id" :value="d.id">{{d.name}} · {{d.protocol==='SIMULATED'?'模拟':'实体'}}</option></select></label>
        <label>执行模式<select v-model="policy.mode"><option value="MANUAL">人工确认（默认）</option><option value="AUTO">全自动模拟</option></select></label>
        <label>低于此水分启灌（%）<input v-model.number="policy.thresholdValue" type="number" min="5" max="80" step="0.1" required /></label>
        <label>运行时长（秒）<input v-model.number="policy.durationSeconds" type="number" min="10" max="300" required /></label>
        <label>冷却间隔（分钟）<input v-model.number="policy.cooldownMinutes" type="number" min="30" max="1440" required /></label>
        <label>每天最多启动次数<input v-model.number="policy.dailyLimit" type="number" min="1" max="6" required /></label>
      </div><p>演示阈值需按作物与传感器校准；本规则不适用于水稻水位管理。保存新策略会停止原运行并使待确认建议失效。</p><div><button type="button" @click="editing=false">取消</button><button class="primary" :disabled="busy">保存策略</button></div></form>
      <h3>建议与执行记录</h3><p v-if="!irrigation.runs.length" class="muted">尚无灌溉建议。配置策略后，点击“检查并生成建议”。</p>
      <article v-for="r in irrigation.runs" :key="r.id" class="ai-run" :class="{'ai-run-highlight': r.id===highlightedRunId}"><div class="ai-section-head"><h4>{{r.plotName}} · {{states[r.status]}}</h4><small>{{time(r.createdAt)}}</small></div><p>{{r.reason}}</p><p>水泵：{{r.pumpName}} · 时长：{{r.durationSeconds}} 秒 · {{r.approvedBy?'确认人：'+r.approvedBy:'尚未下发'}}</p><small v-if="r.status==='RUNNING'">预计停泵 {{time(r.stopAt)}}</small><small v-else>{{r.resultNote}}</small><div class="ai-run-actions" v-if="writer"><button v-if="r.status==='PROPOSED'" class="primary" :disabled="busy" @click="act(r,'approve')">确认并启动模拟灌溉</button><button v-if="['PROPOSED','RUNNING'].includes(r.status)" :disabled="busy" @click="act(r,'cancel')">{{r.status==='RUNNING'?'立即停止':'取消建议'}}</button></div></article>
    </div>
  </section>
  <section v-else class="panel"><p>请选择一座农场，开始使用 AI 助手。</p></section>
</template>
