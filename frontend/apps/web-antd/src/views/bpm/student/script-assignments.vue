<script setup lang="ts">
import { ref, watch } from 'vue';
import { Alert, Button, Input, Popconfirm, Select, Space, Table, Tag, message } from 'ant-design-vue';
import { requestClient } from '#/api/request';
const props = defineProps<{studentId:number}>();
interface Assignment {revision:number;producer_user_id?:number;producer_name?:string;remark?:string}
interface Script {id:number;title:string;card_grade:string;can_assign:boolean;production_assignment:Assignment;selected?:number;remark?:string}
interface History {revision:number;producer_name:string;director_name:string;remark:string;assigned_at:number}
const rows=ref<Script[]>([]);const producers=ref<Array<{label:string;value:number}>>([]);
const loading=ref(false);const failed=ref(false);const saving=ref<number>();const offset=ref(0);const history=ref<History[]>([]);const historyTitle=ref('');const historyApplication=ref<number>();const historyLoading=ref(false);let generation=0;let historyGeneration=0;
const columns=[{title:'剧名',dataIndex:'title',width:200},{title:'一卡评级',key:'grade',width:90},{title:'当前制剧人',key:'producer',width:140},{title:'分配制剧人',key:'selection',width:200},{title:'分配说明',key:'remark',width:220},{title:'操作',key:'action',width:160}];
async function load(){const current=++generation;loading.value=true;failed.value=false;try{const [scripts,options]=await Promise.all([requestClient.get<Script[]>('/bpm/student-bridge/student-scripts',{params:{studentId:props.studentId,offset:offset.value}}),requestClient.get<Array<{id:number;nickname:string;username:string}>>('/bpm/student-bridge/producers')]);if(current!==generation)return;rows.value=scripts.map(row=>({...row,selected:row.production_assignment.producer_user_id,remark:row.production_assignment.remark||''}));producers.value=options.map(user=>({value:user.id,label:`${user.nickname}（${user.username}）`}));}catch{if(current===generation)failed.value=true;}finally{if(current===generation)loading.value=false;}}
async function save(row:Script){saving.value=row.id;try{await requestClient.put('/bpm/student-bridge/production-assignment',{applicationId:row.id,producerUserId:row.selected,expectedRevision:row.production_assignment.revision,remark:row.remark});message.success('分配已保存，未启动制剧流程');await load();if(historyApplication.value===row.id)await showHistory(row);}finally{saving.value=undefined;}}
async function showHistory(row:Script){const current=++historyGeneration;historyTitle.value=row.title;historyApplication.value=row.id;history.value=[];historyLoading.value=true;try{const value=await requestClient.get<History[]>('/bpm/student-bridge/production-assignment-history',{params:{applicationId:row.id}});if(current===historyGeneration)history.value=value;}finally{if(current===historyGeneration)historyLoading.value=false;}}
async function page(delta:number){offset.value=Math.max(0,offset.value+delta);await load();}
watch(()=>props.studentId,()=>{offset.value=0;historyTitle.value='';++historyGeneration;void load();},{immediate:true});
</script>
<template>
 <Alert type="info" message="仅审核通过并结束的剧本可以分配。分配对象为启用的后台工作人员，不改变学生本人发起人，不启动制剧流程。" class="mb-4"/>
 <Button class="mb-3" :loading="loading" @click="load">刷新</Button><Alert v-if="failed" type="error" message="剧本或制剧人读取失败，请重试" class="mb-3"/>
 <Table :columns="columns" :data-source="rows" :loading="loading" :pagination="false" row-key="id" :scroll="{x:1000}">
  <template #bodyCell="{column,record}">
   <template v-if="column.key==='grade'"><Tag v-if="record.card_grade">{{record.card_grade}}</Tag><span v-else>未评级</span></template>
   <template v-if="column.key==='producer'">{{record.production_assignment.producer_name||'未分配'}}</template>
   <template v-if="column.key==='selection'"><Select v-model:value="record.selected" :options="producers" show-search option-filter-prop="label" placeholder="请选择制剧人" class="w-full" :disabled="!record.can_assign||!!saving"/></template>
   <template v-if="column.key==='remark'"><Input v-model:value="record.remark" :maxlength="500" :disabled="!record.can_assign||!!saving" placeholder="可选，最多500字"/></template>
   <template v-if="column.key==='action'"><Popconfirm title="确认保存此剧本的制剧人分配？" @confirm="save(record)"><Button type="link" :loading="saving===record.id" :disabled="!record.can_assign||!record.selected||!!saving">保存分配</Button></Popconfirm><span v-if="!record.can_assign" class="text-xs">审核未完成</span><Button type="link" @click="showHistory(record)">分配记录</Button></template>
  </template>
 </Table>
 <Space class="mt-3"><Button :disabled="offset===0||loading" @click="page(-50)">上一页</Button><span>第{{offset/50+1}}页</span><Button :disabled="rows.length<50||loading" @click="page(50)">下一页</Button></Space>
 <section v-if="historyTitle" class="mt-6"><h3>{{historyTitle}} · 分配记录</h3><p v-if="historyLoading">正在读取…</p><p v-else-if="!history.length">暂无分配记录</p><div v-for="item in history" :key="item.revision" class="border-b py-3"><p>v{{item.revision}} · {{item.producer_name}} · 方导/操作人：{{item.director_name}} · {{new Date(item.assigned_at).toLocaleString('zh-CN')}}</p><p class="whitespace-pre-wrap break-words">{{item.remark}}</p></div></section>
</template>
