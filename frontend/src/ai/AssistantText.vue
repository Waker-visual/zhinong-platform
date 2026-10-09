<script setup>
import { computed } from 'vue';
const props=defineProps({text:String});
// Deliberately render only emphasis; all text remains escaped by Vue, including HTML and URLs.
const parts=computed(()=>String(props.text||'').split(/(\*\*[^*\n]+\*\*)/g));
</script>
<template><template v-for="(part,index) in parts" :key="index"><strong v-if="part.startsWith('**') && part.endsWith('**')">{{part.slice(2,-2)}}</strong><template v-else>{{part}}</template></template></template>
