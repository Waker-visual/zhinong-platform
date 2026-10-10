<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue';
import { api } from '../api';
import { confirmAction } from '../ui/confirm';
import { irrigationApproveConfirm } from './agent-events.js';
import IrrigationMapPreview from './IrrigationMapPreview.vue';
const props=defineProps({farmId:String,role:String,data:Object,highlightedRunId:String});
const emit=defineEmits(['updated','farm']);
let alive=true;onUnmounted(()=>{alive=false;});
const busy=ref(false),error=ref(''),notice=ref(''),editing=ref(false),zoneId=ref(''),duration=ref(60);
const policy=reactive({});
const writer=computed(()=>['ADMIN','OPERATOR'].includes(props.role));
const zones=computed(()=>props.data.zones||[]),jobs=computed(()=>props.data.jobs||[]);
const selectedZone=computed(()=>zones.value.find(z=>z.id===zoneId.value));
const zonePump=computed(()=>props.data.devices.find(d=>d.id===selectedZone.value?.pumpId));
const selectedJobs=computed(()=>jobs.value.filter(j=>j.parameters.zoneId===zoneId.value));
const pumpBusy=computed(()=>jobs.value.some(j=>j.deviceId===selectedZone.value?.pumpId&&j.status==='RUNNING')||props.data.runs.some(r=>r.pumpId===selectedZone.value?.pumpId&&r.status==='RUNNING'));
const sensors=computed(()=>props.data.devices.filter(d=>d.deviceType!=='PUMP'&&d.plotId===policy.plotId));
const policyZones=computed(()=>zones.value.filter(z=>z.plotId===policy.plotId));
const pumps=computed(()=>props.data.devices.filter(d=>d.deviceType==='PUMP'));
const states={PROPOSED:'等待人工确认',RUNNING:'运行中',COMPLETED:'已结束',STOPPED:'已停止',CANCELLED:'已取消',EXPIRED:'已过期'};
const time=v=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—';
const volume=v=>v==null?'未接入':Number(v).toFixed(3)+' m³';
const plotName=id=>props.data.plots.find(p=>p.id===id)?.name||'未关联';
const policyFor=id=>props.data.policies.find(p=>p.plotId===id);
const zonesFor=id=>zones.value.filter(z=>z.plotId===id);
const sensorsFor=id=>props.data.devices.filter(d=>d.deviceType!=='PUMP'&&d.plotId===id);
const jobFor=id=>jobs.value.find(j=>j.id===id);
watch(()=>props.farmId,()=>{editing.value=false;zoneId.value='';error.value='';notice.value='';});
watch(zones,list=>{if(!list.some(z=>z.id===zoneId.value))zoneId.value=list[0]?.id||'';},{immediate:true});
async function perform(work) {
  if(busy.value)return;busy.value=true;error.value='';notice.value='';const farm=props.farmId;
  try {await work(farm);}catch(e){if(farm===props.farmId)error.value=e.message;}finally{busy.value=false;}
}
async function refresh(farm) {
  const data=await api('/ai/irrigation?farmId='+farm);
  if(alive&&farm===props.farmId)emit('updated',data);
}
function editPolicy(plotId) {
  const saved=policyFor(plotId);
  const defaults={plotId,sensorId:'',pumpId:'',zoneId:'',mode:'MANUAL',thresholdValue:30,durationSeconds:60,cooldownMinutes:120,dailyLimit:3,revision:0};
  Object.assign(policy,defaults);
  for(const key of Object.keys(defaults))if(saved?.[key]!=null)policy[key]=saved[key];
  if(!policy.sensorId)policy.sensorId=sensors.value[0]?.id||'';
  if(!policy.zoneId)policy.zoneId=policyZones.value[0]?.id||'';
  selectPolicyZone();
  if(!policy.pumpId)policy.pumpId=pumps.value[0]?.id||'';
  editing.value=true;
}
function selectPolicyZone(){const z=zones.value.find(z=>z.id===policy.zoneId);if(z){policy.pumpId=z.pumpId;zoneId.value=z.id;}}
async function savePolicy(){
  if(policy.mode==='AUTO'&&!await confirmAction({title:'启用此覆盖区的自动模拟灌溉？',message:`仅作用于所选覆盖区；低于 ${policy.thresholdValue}% 时检查启动，每次 ${policy.durationSeconds} 秒，每天最多 ${policy.dailyLimit} 次。阈值需按作物校准。`,confirmLabel:'启用自动模式'}))return;
  if(!alive)return;
  await perform(async farm=>{const data=await api('/ai/irrigation/policy','PUT',{...policy,farmId:farm});if(alive&&farm===props.farmId){emit('updated',data);editing.value=false;notice.value='策略已保存，覆盖区与水泵关联已同步。';}});
}
async function propose(id){await perform(async farm=>{try{await api(`/ai/irrigation/plots/${id}/propose`,'POST');notice.value='建议已生成，请核对范围、设备和时长后确认。';}finally{await refresh(farm);}});}
async function act(run,action){
  if(action==='approve' && !await confirmAction(irrigationApproveConfirm(run))) return;
  if(!alive)return;
  await perform(async farm=>{await api(`/ai/irrigation/runs/${run.id}/${action}`,'POST');await refresh(farm);notice.value='执行状态已更新；已绑定的任务同时显示在地图与用水记录中。';});
}
async function startWater(){
  const z=selectedZone.value;if(!z||busy.value)return;
  const farm=props.farmId,seconds=Number(duration.value);
  if(!await confirmAction({title:'下发分区定时灌溉？',message:`${plotName(z.plotId)} / ${z.name}\n水泵：${zonePump.value?.name}\n运行 ${seconds} 秒，模拟执行。此操作由人工决定，不依据土壤阈值自动启停。`,confirmLabel:'确认下发'}))return;
  if(!alive||farm!==props.farmId)return;
  await perform(async f=>{await api(`/farms/${f}/field-map/irrigation`,'POST',{zoneId:z.id,durationSeconds:seconds,requestId:crypto.randomUUID()});await refresh(f);notice.value='任务已下发，到时自动停泵；地图和本页共用同一条记录。';});
}
async function stopJob(job){await perform(async farm=>{await api(`/farms/${farm}/field-map/jobs/${job.id}/actions`,'POST',{action:'STOP'});await refresh(farm);notice.value='停止结果已同步。';});}
</script>
<template>
  <div class="ai-irrigation">
    <div class="ai-section-head"><div><h3>先审阅，再灌溉</h3><p>经营地块 → 地图小田块 → 灌溉覆盖区。设备、任务和用水记录与农场地图共用。</p></div><button @click="emit('farm',farmId)">打开农场地图 →</button></div>
    <p class="ai-notice">“演示分区”来自农场档案的经营地块，不是 AI 自动划界。A / B / C 小田块按影像估绘，再关联管线、节点和水泵。已有管线表示配置了覆盖范围，不等于已启用 AI 策略或已经灌溉。</p>
    <p v-if="error" class="error" role="alert">{{error}}</p><p v-if="notice" class="ai-notice" role="status">{{notice}}</p>
    <div class="ai-water-summary">
      <div><strong>{{data.plots.length}}</strong><span>经营地块</span></div><div><strong>{{zones.length}}</strong><span>地图覆盖区</span></div>
      <div><strong>{{data.waterTotals?.runs||0}}</strong><span>已下发任务</span></div><div><strong>{{volume(data.waterTotals?.estimatedM3??0)}}</strong><span>累计估算用水 · 实测{{volume(data.waterTotals?.measuredM3)}}</span></div>
    </div>
    <div class="ai-policy-grid">
      <article v-for="p in data.plots" :key="p.id">
        <h4>{{p.name}} <span class="muted">· {{p.activeCrops?.join('、')||p.crop}}</span></h4>
        <p>{{zonesFor(p.id).length}} 个地图覆盖区 · {{sensorsFor(p.id).length}} 个土壤测点</p>
        <div class="ai-zone-chips"><button v-for="z in zonesFor(p.id)" :key="z.id" :aria-pressed="zoneId===z.id" @click="zoneId=z.id">{{z.name.replace(' 灌溉分区','')}}</button></div>
        <p v-for="d in sensorsFor(p.id)" :key="d.id">{{d.name}}<br><strong>{{d.soilMoisture?Number(d.soilMoisture.value).toFixed(1)+'%':'无读数'}}</strong> · {{d.moistureFresh?'读数有效':'读数缺失或过期'}}<small>{{time(d.soilMoisture?.time)}}</small></p>
        <template v-if="policyFor(p.id)"><p>{{policyFor(p.id).mode==='AUTO'?'自动模拟模式':'人工确认模式'}} · 阈值 {{policyFor(p.id).thresholdValue}}% · {{policyFor(p.id).durationSeconds}} 秒</p><small>作用范围：{{zones.find(z=>z.id===policyFor(p.id).zoneId)?.name||'旧策略未绑定地图覆盖区'}}</small></template>
        <p v-else>AI 策略待配置；设备与地图数据已接入。</p>
        <p class="ai-water-diagnosis">{{p.diagnosis}}</p>
        <button v-if="writer&&policyFor(p.id)" :disabled="busy||!p.readyForProposal" @click="propose(p.id)">检查并生成建议</button>
        <button v-if="role==='ADMIN'" class="text-button" :disabled="busy" @click="editPolicy(p.id)">配置策略</button>
      </article>
    </div>
    <form v-if="editing&&role==='ADMIN'" class="ai-policy-form" @submit.prevent="savePolicy">
      <h3>{{plotName(policy.plotId)}} · 灌溉策略</h3><div class="ai-form-grid">
        <label>地图灌溉覆盖区<select v-model="policy.zoneId" :required="policyZones.length>0" @change="selectPolicyZone"><option value="">{{policyZones.length?'请选择':'尚无地图覆盖区（旧版泵控制）'}}</option><option v-for="z in policyZones" :key="z.id" :value="z.id">{{z.name}}</option></select></label>
        <label>土壤测点<select v-model="policy.sensorId" required><option value="">请选择</option><option v-for="d in sensors" :key="d.id" :value="d.id">{{d.name}}</option></select></label>
        <label>受控水泵<select v-model="policy.pumpId" required :disabled="!!policy.zoneId"><option value="">请选择</option><option v-for="d in pumps" :key="d.id" :value="d.id">{{d.name}}</option></select></label>
        <label>执行模式<select v-model="policy.mode"><option value="MANUAL">人工确认（默认）</option><option value="AUTO">全自动模拟</option></select></label>
        <label>低于此水分启灌（%）<input v-model.number="policy.thresholdValue" type="number" min="5" max="80" step="0.1" required /></label>
        <label>运行时长（秒）<input v-model.number="policy.durationSeconds" type="number" min="10" max="300" required /></label>
        <label>冷却间隔（分钟）<input v-model.number="policy.cooldownMinutes" type="number" min="30" max="1440" required /></label>
        <label>每天最多启动次数<input v-model.number="policy.dailyLimit" type="number" min="1" max="6" required /></label>
      </div><p>每个经营地块配置一项策略，只作用于选中的一个覆盖区；测点代表经营地块，不代表每个小田块都有独立传感器。水稻需水位策略，本墒情规则不适用。保存会使旧建议失效并停止原任务；初始参数仅供配置参考。</p>
      <div><button type="button" @click="editing=false">取消</button><button class="primary" :disabled="busy">保存策略</button></div>
    </form>
    <section class="ai-water-operation" aria-label="地图分区灌溉">
      <div><h3>地图分区与人工灌溉</h3><IrrigationMapPreview :zones="zones" :selected="zoneId" @select="zoneId=$event"/><p v-if="!zones.length">尚未配置地图灌溉覆盖区，请先在农场地图中补齐范围与设备关联。</p></div>
      <form v-if="selectedZone" @submit.prevent="startWater">
        <label>选择灌溉覆盖区<select v-model="zoneId"><option v-for="z in zones" :key="z.id" :value="z.id">{{plotName(z.plotId)}} / {{z.name}}</option></select></label>
        <p>关联水泵：{{zonePump?.name||'未找到可用水泵'}}</p><p>反馈：{{zonePump?.pumpRunning?(Number(zonePump.pumpRunning.value)===1?'运行中':'已停机'):'暂无'}} · {{zonePump?.freshness==='FRESH'?'在线':'反馈过期或不可用'}}</p>
        <p>{{selectedZone.pipeline.length}} 个管线坐标 · {{selectedZone.nodes.length}} 个节点 · 覆盖边界与地图相同</p>
        <label>人工灌溉时长（秒）<input v-model.number="duration" type="number" min="10" max="300" required /></label>
        <p class="muted">模拟执行，10–300 秒后自动停泵。共享水泵同一时刻只接受一项任务；人工下发不使用 AI 墒情阈值。</p>
        <p v-if="pumpBusy" role="status">此水泵正在执行任务，可在用水记录中查看和停止。</p>
        <button v-if="writer" class="primary" :disabled="busy||pumpBusy||!zonePump?.controlEnabled||zonePump?.freshness!=='FRESH'">确认分区并下发</button>
        <p>本覆盖区最近 {{selectedJobs.length}} 条任务 · 最新结果：{{selectedJobs[0]?states[selectedJobs[0].status]:'尚未执行，不补造历史记录'}}</p>
      </form>
    </section>
    <h3>AI 建议与审批记录</h3><p v-if="!data.runs.length" class="muted">尚无 AI 建议。保存适用的策略并满足启灌条件后生成；人工地图任务在下方用水记录中显示。</p>
    <article v-for="r in data.runs" :key="r.id" class="ai-run" :class="{'ai-run-highlight':r.id===highlightedRunId}">
      <div class="ai-section-head"><h4>{{r.plotName}} · {{states[r.status]}}</h4><small>{{time(r.createdAt)}}</small></div><p>{{r.reason}}</p>
      <p>范围：{{r.zoneName||'旧版未绑定覆盖区'}} · 水泵：{{r.pumpName}} · {{r.durationSeconds}} 秒</p><small>{{r.resultNote||'待审核'}} {{r.status==='RUNNING'?' · 预计停泵 '+time(r.stopAt):''}}</small>
      <p v-if="r.mapJobId">共享任务：{{r.mapJobId.slice(0,8)}} · {{states[jobFor(r.mapJobId)?.status]||'详情见地图记录'}}<button @click="zoneId=r.zoneId">定位覆盖区</button></p>
      <div v-if="writer" class="ai-run-actions"><button v-if="r.status==='PROPOSED'" class="primary" :disabled="busy" @click="act(r,'approve')">确认并启动模拟灌溉</button><button v-if="['PROPOSED','RUNNING'].includes(r.status)" :disabled="busy" @click="act(r,'cancel')">{{r.status==='RUNNING'?'立即停止':'取消建议'}}</button></div>
    </article>
    <h3>用水与执行记录</h3><p class="muted">地图、手机和已绑定覆盖区的 AI 任务共用记录。显示最近100条；累计用水统计全部分区任务。估算用水按模拟流量积分，未接入实测水表。</p>
    <p v-if="!jobs.length">尚无分区灌溉执行记录。选择覆盖区下发后，进度与用水会在此更新。</p>
    <div v-else class="ai-table-wrap"><table class="ai-water-history"><thead><tr><th>覆盖区 / 来源</th><th>状态 / 进度</th><th>起止时间</th><th>估算用水</th><th>实测用水</th><th>结果 / 操作</th></tr></thead><tbody>
      <tr v-for="j in jobs" :key="j.id" :class="{'ai-water-selected':j.parameters.zoneId===zoneId}"><td><button @click="zoneId=j.parameters.zoneId">{{j.title}}</button><small>{{plotName(j.plotId)}} · {{j.aiRunId?'AI 策略':'人工下发'}} · {{j.id.slice(0,8)}}</small></td><td>{{states[j.status]}} · {{Math.round(j.progress)}}%<progress :value="j.progress" max="100" :aria-label="j.title+'进度'" /></td><td>{{time(j.startedAt)}}<br>{{time(j.finishedAt)}}</td><td>{{volume(j.estimatedM3)}}</td><td>{{volume(j.measuredM3)}}</td><td>{{j.resultNote}}<button v-if="writer&&j.status==='RUNNING'" :disabled="busy" @click="stopJob(j)">停止任务</button></td></tr>
    </tbody></table></div>
    <p class="ai-footnote">AI 启动仍要求读数有效、唯一有效在种计划、设备无故障，并遵循冷却与每日次数限制（计入人工地图任务）。实体网关的限时停泵、水位控制和阀门控制协议尚未接入。</p>
  </div>
</template>
