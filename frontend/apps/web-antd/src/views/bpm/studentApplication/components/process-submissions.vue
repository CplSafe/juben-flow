<script setup lang="ts">
import { ref, watch } from 'vue';
import { Alert, Button, Collapse, CollapsePanel } from 'ant-design-vue';
import { requestClient } from '#/api/request';
import ScriptFields from './script-fields.vue';
const props = defineProps<{ processId: string }>();
interface Detail { application:{id:number;creator_json?:string};workflow:{versions:Array<{version:number;stage:string;content:string;data?:Record<string,unknown>}>;withdrawals?:Array<{task_id:string;stage:string;reason:string;version:number;withdrawn_at:number}>} }
const detail=ref<Detail>();const failed=ref(false);const loading=ref(false);let generation=0;
const names:Record<string,string>={creative:'创意',card:'一卡',script:'剧本',final:'完善剧本',draft:'一卡（旧流程）',revision:'完善剧本（旧流程）'};
async function load(){const current=++generation;detail.value=undefined;loading.value=true;failed.value=false;try{const value=await requestClient.get<Detail>('/bpm/student-bridge/process-detail',{params:{processId:props.processId}});if(current===generation)detail.value=value;}catch{if(current===generation)failed.value=true;}finally{if(current===generation)loading.value=false;}}
watch(()=>props.processId,load,{immediate:true});
</script>
<template>
 <section class="mb-6 rounded-lg border p-4"><h3 class="mb-3 font-medium">创作表单及附件</h3><p v-if="loading">正在读取提交版本…</p><Alert v-if="failed" type="error" message="提交记录读取失败"><template #action><Button @click="load">重试</Button></template></Alert>
  <ScriptFields v-if="detail?.application.creator_json" :data="JSON.parse(detail.application.creator_json)"/><Collapse v-if="detail" :default-active-key="detail.workflow.versions.slice(-1).map(v=>v.version)"><CollapsePanel v-for="v in detail.workflow.versions" :key="v.version" :header="`v${v.version} · ${names[v.stage]||v.stage}`"><ScriptFields v-if="v.data" :data="v.data" :application-id="detail.application.id"/><p v-else class="whitespace-pre-wrap break-words">{{ v.content }}</p></CollapsePanel></Collapse>
  <div v-for="w in detail?.workflow.withdrawals||[]" :key="w.task_id" class="mt-3 text-sm">学生撤回 v{{ w.version }} · {{ names[w.stage]||w.stage }} · {{ new Date(w.withdrawn_at).toLocaleString('zh-CN') }}<p class="whitespace-pre-wrap break-words">{{ w.reason || '未填写原因' }}</p></div>
 </section>
</template>
