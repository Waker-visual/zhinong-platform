<script setup>
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue';
import { api } from '../api';
import { confirmAction as confirm } from '../ui/confirm';
import './assistant.css';
import AssistantText from './AssistantText.vue';
import { diagnosticLabels } from './diagnostics';
const props = defineProps({ farmId: String, role: String, revision: Number });
const report = ref(null), conversations = ref([]), messages = ref([]), selected = ref(''), question = ref('');
const panel = ref('chat'), busy = ref(false), sending = ref(false), error = ref(''), notice = ref(''), scroller = ref(null), status = ref(null);
const irrigation = ref({ plots: [], devices: [], policies: [], runs: [] });
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
}
async function select(id) {
  if(sending.value) return;
  selected.value=id; error.value='';
  const rows=await api(`/ai/conversations/${id}/messages`);
  if(alive && selected.value===id) {messages.value=rows; await scroll();}
}
async function scroll() {await nextTick();scroller.value?.scrollTo({top:scroller.value.scrollHeight,behavior:'smooth'});}
async function newChat() {if(sending.value) return;selected.value='';messages.value=[];question.value='';pendingRequest=null;}
async function removeChat() {
  if(!selected.value || sending.value) return;
  if(!await confirm({title:'删除当前对话？',message:'只删除当前账号的这段对话，灌溉审计记录会保留。',confirmLabel:'删除对话',danger:true})) return;
  await perform(async()=>{await api('/ai/conversations/'+selected.value,'DELETE'); await newChat(); await refresh();});
}
async function send(text=question.value) {
  text=text.trim(); if(!text || sending.value || text.length>2000) return;
  sending.value=true; error.value=''; question.value='';
  try {
    if(!selected.value) {const c=await api('/ai/conversations','POST',{farmId:props.farmId});selected.value=c.id;}
    const id=selected.value;
    if(!pendingRequest || pendingRequest.text!==text || pendingRequest.id!==id) pendingRequest={text,id,requestId:crypto.randomUUID()};
    const outgoing={role:'user',content:text,temporary:true};messages.value.push(outgoing);await scroll();
    await api(`/ai/conversations/${id}/messages`,'POST',{question:text,requestId:pendingRequest.requestId},{timeoutMs:70000});
    pendingRequest=null;
    if(!alive) return;
    messages.value=await api(`/ai/conversations/${id}/messages`); conversations.value=await api('/ai/conversations?farmId='+props.farmId);await scroll();
  } catch(e) { if(alive) {error.value=e.message;question.value=text;messages.value=messages.value.filter(m=>!m.temporary);} }
  finally {sending.value=false;}
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
        <button v-for="c in conversations" :key="c.id" :disabled="sending" :aria-current="selected===c.id?'true':undefined" @click="perform(()=>select(c.id))"><span>{{c.title}}</span><small>{{time(c.updatedAt)}}</small></button>
        <p v-if="!conversations.length" class="muted">对话会自动保存。不同农场与账号分别管理。</p>
        <button v-if="selected" :disabled="sending" class="text-button" @click="removeChat">删除当前对话</button>
      </aside>
      <div class="ai-chat-main">
        <div ref="scroller" class="ai-messages" role="log" aria-label="农事对话记录" :aria-busy="sending">
          <div v-if="!messages.length" class="ai-welcome"><span class="ai-monogram">禾</span><h3>今天想了解农场的什么？</h3><p>我会结合当前农场的种植、气象、监测和生产记录，为你梳理依据与行动建议。</p>
            <div class="ai-prompts"><button v-for="p in prompts" :key="p" :disabled="sending" @click="send(p)">{{p}} ↗</button></div>
          </div>
          <article v-for="(m,index) in messages" :key="m.id||index" class="ai-message" :class="m.role"><small>{{m.role==='user'?'你':'农场 AI 助手'}}<span v-if="m.mode==='rule'"> · 规则回退</span></small><div class="ai-message-text"><AssistantText v-if="m.role==='assistant'" :text="m.content"/><template v-else>{{m.content}}</template></div><small v-if="m.diagnostic && m.diagnostic!=='OK'">{{diagnosticLabels[m.diagnostic] || '模型暂不可用'}}</small></article>
          <div v-if="sending" class="ai-thinking" role="status">正在读取当前农场资料并整理建议…</div>
        </div>
        <form class="ai-composer" @submit.prevent="send()"><label class="sr-only" for="ai-question">农事问题</label><textarea id="ai-question" v-model="question" rows="3" maxlength="2000" :disabled="sending" placeholder="询问农事、分析天气，或了解作物生长情况…" @keydown.enter.exact="e=>{if(!e.isComposing){e.preventDefault();send();}}"></textarea><div><small>Enter 发送 · Shift + Enter 换行 · {{question.length}}/2000</small><button class="primary" :disabled="sending || !question.trim()">{{sending?'正在回答…':'发送 ↑'}}</button></div></form>
        <p class="ai-footnote">发送问题时，当前农场摘要与最近对话将交由已配置的模型服务处理。回答供农事参考，聊天不会直接控制设备。</p>
      </div>
    </div>
    <div v-else-if="panel==='analysis' && report" class="ai-analysis">
      <div class="ai-section-head"><div><h3>农情四情诊断</h3><p>更新于 {{time(report.generatedAt)}} · {{report.freshMeasurements}} 条新鲜指标读数</p></div><button @click="panel='chat';send(prompts[0])" :disabled="sending">请助手解读 →</button></div>
      <div class="ai-condition-grid"><article v-for="c in report.conditions" :key="c.code"><div><h3>{{c.name}}</h3><span>{{c.state}}</span></div><p>{{c.advice}}</p></article></div>
      <div v-if="report.cautions?.length" class="ai-notice"><p v-for="item in report.cautions" :key="item">{{item}}</p><small>{{report.thresholdNotice}}</small></div>
      <p class="ai-footnote">当前待执行/进行中任务 {{report.tasks?.pending}} 项，其中逾期 {{report.tasks?.overdue}} 项。虫情近7天有 {{report.pestHistory?.length}} 天记录，可在对话中要求进一步解读。</p>
      <div class="ai-section-head"><div><h3>近7天气象趋势</h3><p>{{report.weatherNotice}}</p></div></div>
      <div class="ai-weather-grid"><article v-for="w in weatherCharts" :key="w.metric"><span>{{w.label}} · 日均值</span><h3>{{w.latest ?? '—'}} <small>{{w.unit}}</small></h3><p v-if="w.delta!==null">首末日均值变化 {{Number(w.delta)>0?'+':''}}{{w.delta}} {{w.unit}}</p><p v-else>暂无足够观测数据</p><svg v-if="w.rows.length" viewBox="0 0 280 90" role="img" :aria-label="w.label+'近7天日均值趋势'"><polyline :points="w.points" fill="none" stroke="currentColor" stroke-width="2.5" /></svg><small v-if="w.rows.length">{{String(w.rows[0].date).slice(0,10)}} — {{String(w.rows.at(-1).date).slice(0,10)}}</small></article></div>
      <details><summary>查看气象数据明细与来源</summary><div class="ai-table-wrap"><table><thead><tr><th>日期</th><th>指标</th><th>均值</th><th>最小</th><th>最大</th><th>样本</th></tr></thead><tbody><tr v-for="w in report.weather" :key="w.date+w.metric"><td>{{String(w.date).slice(0,10)}}</td><td>{{({TEMPERATURE:'温度 ℃',HUMIDITY:'湿度 %',WIND_SPEED:'风速 m/s',RAINFALL:'累计雨量读数 mm'})[w.metric]}}</td><td>{{w.average}}</td><td>{{w.min}}</td><td>{{w.max}}</td><td>{{w.samples}}</td></tr></tbody></table></div><p>{{report.sources.join('；')}}</p></details>
      <div class="ai-section-head"><h3>当前在种作物与观测</h3></div>
      <p v-if="!report.crops.length">没有有效日期内的在种计划，无法判断当前生育阶段。</p>
      <div class="ai-crops"><article v-for="c in report.crops" :key="c.plotId+c.startDate"><h4>{{c.plotName}} · {{c.crop}}</h4><p>{{c.variety}} · {{c.areaMu}} 亩</p><small>{{c.startDate}} 至 {{c.endDate}}（计划日期）</small></article></div>
      <div class="ai-table-wrap"><table><thead><tr><th>测点</th><th>地块</th><th>指标</th><th>最新值</th><th>状态</th><th>观测时间</th></tr></thead><tbody><tr v-for="s in report.sensors" :key="s.deviceId+s.metric"><td>{{s.deviceName}}</td><td>{{s.plotName||'农场级'}}</td><td>{{({SOIL_MOISTURE:'土壤水分 %',TEMPERATURE:'温度 ℃',HUMIDITY:'湿度 %',WIND_SPEED:'风速 m/s',RAINFALL:'累计雨量 mm',PEST_COUNT:'虫情计数'})[s.metric]}}</td><td>{{s.value}}</td><td>{{s.fresh?'新鲜':'已过期'}}</td><td>{{time(s.time)}}</td></tr></tbody></table></div>
      <p class="ai-footnote">历史生产共 {{report.production.records}} 条，累计 {{report.production.yieldKg}} kg。跨年度累计不能视作单季亩产；图表及规则不代表经过验证的作物模型。</p>
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
      <article v-for="r in irrigation.runs" :key="r.id" class="ai-run"><div class="ai-section-head"><h4>{{r.plotName}} · {{states[r.status]}}</h4><small>{{time(r.createdAt)}}</small></div><p>{{r.reason}}</p><p>水泵：{{r.pumpName}} · 时长：{{r.durationSeconds}} 秒 · {{r.approvedBy?'确认人：'+r.approvedBy:'尚未下发'}}</p><small v-if="r.status==='RUNNING'">预计停泵 {{time(r.stopAt)}}</small><small v-else>{{r.resultNote}}</small><div class="ai-run-actions" v-if="writer"><button v-if="r.status==='PROPOSED'" class="primary" :disabled="busy" @click="act(r,'approve')">确认并启动模拟灌溉</button><button v-if="['PROPOSED','RUNNING'].includes(r.status)" :disabled="busy" @click="act(r,'cancel')">{{r.status==='RUNNING'?'立即停止':'取消建议'}}</button></div></article>
    </div>
  </section>
  <section v-else class="panel"><p>请选择一座农场，开始使用 AI 助手。</p></section>
</template>
