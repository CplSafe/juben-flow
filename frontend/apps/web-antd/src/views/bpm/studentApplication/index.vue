<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Page } from '@vben/common-ui';
import { Alert, Button, Collapse, CollapsePanel, Drawer, Space, Table, Tag } from 'ant-design-vue';
import { requestClient } from '#/api/request';

interface Row {
 id: number; user_id: number; external_user_id?: string; name: string; phone: string;
 school: string; status?: number; creative?: string; process_id?: string; application_id?: string;
}
interface Version { version: number; stage: string; content: string; task_id: string; submitted_at: number }
interface Review { task_id: string; name: string; status: number; reason?: string; version: number; finished_at: number }
interface Workflow { current_stage: string; review_status: string; stage_statuses?: Record<string,string>; status: number; stage: string; versions: Version[]; reviews: Review[] }
defineOptions({ name: 'BpmStudentApplications' });
const stageNames: Record<string, string> = { card:'一卡', script:'剧本', final:'完善剧本', production:'后续制作', creative: '创意', draft: '一卡初稿', revision: '完善剧本', waiting_review: '等待后台审核', finished: '流程已结束' };
const statusNames: Record<string,string> = { under_review:'审核中', needs_changes:'需修改', approved:'已通过', awaiting_submission:'待填写', rejected:'未通过', cancelled:'已取消', not_started:'未开始', not_applicable:'旧流程无此阶段' };
const router = useRouter();
const rows = ref<Row[]>([]);
const loading = ref(false);
const failure = ref(false);
const offset = ref(0);
const detail = ref<Row>();
const workflow = ref<Workflow>();
const detailLoading = ref(false);
const detailFailure = ref(false);
let detailRequest = 0;

const columns = [
 { title: '姓名', dataIndex: 'name', width: 140 },
 { title: '手机号', dataIndex: 'phone', width: 160 },
 { title: '学校', dataIndex: 'school', width: 240 },
 { title: '工作流用户ID', dataIndex: 'user_id', width: 140 },
 { title: '申请编号', dataIndex: 'application_id', width: 140 },
 { title: '操作', key: 'action', width: 160 },
];
async function load() {
 loading.value = true; failure.value = false;
 try { rows.value = await requestClient.get<Row[]>('/bpm/student-bridge/applications', { params: { offset: offset.value } }); }
 catch { failure.value = true; }
 finally { loading.value = false; }
}
async function page(delta: number) { offset.value = Math.max(0, offset.value + delta); await load(); }
async function selectDetail(id: number) {
 const request = ++detailRequest;
 detail.value = rows.value.find((item) => item.id === id);
 workflow.value = undefined; detailLoading.value = true; detailFailure.value = false;
 try {
  const response = await requestClient.get<{ workflow: Workflow }>('/bpm/student-bridge/application-detail', { params: { id } });
  if (request === detailRequest) workflow.value = response.workflow;
 } catch { if (request === detailRequest) detailFailure.value = true; }
 finally { if (request === detailRequest) detailLoading.value = false; }
}
function closeDetail() { ++detailRequest; detail.value = undefined; workflow.value = undefined; }
function openProcess(row: Row) {
 closeDetail();
 router.push({ name: 'BpmProcessInstanceDetail', query: { id: row.process_id } });
}
onMounted(load);
</script>
<template>
 <Page title="创意申请">
  <Space class="mb-4"><Button :loading="loading" @click="load">刷新</Button></Space>
  <Alert v-if="failure" type="error" message="加载失败，请重试" class="mb-4" />
  <Table :columns="columns" :data-source="rows" :loading="loading" :pagination="false" row-key="id" :scroll="{ x: 900 }">
   <template #bodyCell="{ column, record }">

    <template v-if="column.key === 'action'">
     <Button type="link" @click="selectDetail(record.id)">查看申请</Button>
    </template>
   </template>
  </Table>
  <Space class="mt-4"><Button :disabled="offset === 0 || loading" @click="page(-50)">上一页</Button><span>第 {{ offset / 50 + 1 }} 页</span><Button :disabled="rows.length < 50 || loading" @click="page(50)">下一页</Button></Space>
  <Drawer :open="!!detail" title="创意申请及提交版本" width="min(760px, 100vw)" @close="closeDetail">
   <template v-if="detail">
    <p>{{ detail.name }} · {{ detail.school }} · {{ detail.phone }}</p>
    <p class="mt-4 whitespace-pre-wrap break-words">{{ detail.creative }}</p>
    <p v-if="detailLoading" class="mt-4">正在加载版本及审批记录…</p>
    <Alert v-if="detailFailure" class="mt-4" type="error" message="版本及审批记录加载失败">
     <template #action><Button size="small" @click="selectDetail(detail.id)">重试</Button></template>
    </Alert>
    <template v-if="workflow">
     <Tag class="mt-4">{{ stageNames[workflow.current_stage] || stageNames[workflow.stage] || workflow.stage }}</Tag>
     <Tag v-if="workflow.review_status">{{ statusNames[workflow.review_status] || workflow.review_status }}</Tag>
     <div v-if="workflow.stage_statuses" class="mt-3"><Tag v-for="(status, stage) in workflow.stage_statuses" :key="stage">{{ stageNames[stage] || stage }}：{{ statusNames[status] || status }}</Tag></div>
     <h3 class="mb-3 mt-6 font-medium">历次提交版本</h3>
     <Collapse :default-active-key="workflow.versions.slice(-1).map((version) => version.version)">
      <CollapsePanel v-for="version in workflow.versions" :key="version.version" :header="`v${version.version} · ${stageNames[version.stage] || version.stage} · ${new Date(version.submitted_at).toLocaleString('zh-CN')}`">
       <p class="whitespace-pre-wrap break-words">{{ version.content }}</p>
      </CollapsePanel>
     </Collapse>
     <h3 class="mb-3 mt-6 font-medium">审批记录</h3>
     <p v-if="!workflow.reviews.length">暂无审核记录</p>
     <div v-for="review in workflow.reviews" :key="review.task_id" class="mb-4 border-b pb-3">
      <p>{{ review.name }} · v{{ review.version }} · {{ review.status === 2 ? '通过' : review.status === 3 ? '不通过' : '退回' }}</p>
      <p class="mt-1 whitespace-pre-wrap break-words">{{ review.reason || '未填写审批意见' }}</p>
      <p class="mt-1 text-sm text-muted-foreground">{{ new Date(review.finished_at).toLocaleString('zh-CN') }}</p>
     </div>
    </template>
    <Button v-if="detail.process_id" class="mt-4" @click="openProcess(detail)">查看完整审批记录及流程图</Button>
   </template>
  </Drawer>
 </Page>
</template>
