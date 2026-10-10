<script setup>
import { computed } from 'vue';
const props=defineProps({zones:{type:Array,default:()=>[]},selected:String});
defineEmits(['select']);
const shapes=computed(()=>{
  const all=props.zones.flatMap(z=>[...(z.coverage||[]),...(z.pipeline||[]),...(z.nodes||[]).map(n=>n.point)]);
  if(!all.length)return [];
  const latitude=all.reduce((s,p)=>s+p[0],0)/all.length;
  const x=p=>p[1]*Math.cos(latitude*Math.PI/180),y=p=>-p[0];
  const left=Math.min(...all.map(x)),top=Math.min(...all.map(y));
  const width=Math.max(...all.map(x))-left,height=Math.max(...all.map(y))-top;
  const scale=Math.min(560/(width||1),260/(height||1));
  const point=p=>[20+(560-width*scale)/2+(x(p)-left)*scale,20+(260-height*scale)/2+(y(p)-top)*scale];
  return props.zones.map(z=>{
    const ring=z.coverage.map(point);
    return {...z,ring:ring.map(p=>p.join(',')).join(' '),line:z.pipeline.map(p=>point(p).join(',')).join(' '),nodes:z.nodes.map(n=>({...n,xy:point(n.point)})),center:[ring.reduce((s,p)=>s+p[0],0)/ring.length,ring.reduce((s,p)=>s+p[1],0)/ring.length]};
  });
});
</script>
<template>
  <figure class="ai-water-map" v-if="shapes.length">
    <svg viewBox="0 0 600 300" role="group" aria-label="灌溉覆盖区与管线示意，可选择分区">
      <g v-for="z in shapes" :key="z.id" :class="{selected:z.id===selected}">
        <polygon :points="z.ring" tabindex="0" role="button" :aria-label="'选择'+z.name" :aria-pressed="z.id===selected" @click="$emit('select',z.id)" @keydown.enter.prevent="$emit('select',z.id)" @keydown.space.prevent="$emit('select',z.id)" />
        <polyline :points="z.line" />
        <circle v-for="(n,i) in z.nodes" :key="i" :cx="n.xy[0]" :cy="n.xy[1]" r="3"><title>{{n.name}}</title></circle>
        <text :x="z.center[0]" :y="z.center[1]">{{z.name.replace(' 灌溉分区','')}}</text>
      </g>
    </svg>
    <figcaption>蓝线：管线 · 圆点：节点 · 浅蓝区域：覆盖范围<br>沿用地图坐标的估绘/模拟布局示意，非实测管网；节点尚未独立控制。</figcaption>
  </figure>
</template>
