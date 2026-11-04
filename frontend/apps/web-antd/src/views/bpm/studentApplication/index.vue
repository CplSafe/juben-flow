<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Page } from '@vben/common-ui';
import { Alert, Button, Drawer, Space, Table, Tag } from 'ant-design-vue';
import { requestClient } from '#/api/request';

interface Row {
 id: number; user_id: number; external_user_id?: string; name: string; phone: string;
 school: string; status?: number; creative?: string; process_id?: string; application_id?: string;
}
const router = useRouter();
const rows = ref<Row[]>([]);
const loading = ref(false);
const failure = ref(false);
const offset = ref(0);
const detail = ref<Row>();

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
function selectDetail(id: number) { detail.value = rows.value.find((item) => item.id === id); }
function openProcess(row: Row) {
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
    <template v-if="column.key === 'status'"><Tag :color="record.status === 0 ? 'green' : 'default'">{{ record.status === 0 ? '启用' : '停用' }}</Tag></template>
    <template v-if="column.key === 'action'">
     <Button type="link" @click="selectDetail(record.id)">查看申请</Button>
    </template>
   </template>
  </Table>
  <Space class="mt-4"><Button :disabled="offset === 0 || loading" @click="page(-50)">上一页</Button><span>第 {{ offset / 50 + 1 }} 页</span><Button :disabled="rows.length < 50 || loading" @click="page(50)">下一页</Button></Space>
  <Drawer :open="!!detail" title="创意申请" width="640" @close="detail = undefined">
   <template v-if="detail"><p>{{ detail.name }} · {{ detail.school }} · {{ detail.phone }}</p><p class="mt-4 whitespace-pre-wrap break-words">{{ detail.creative }}</p><Button v-if="detail.process_id" class="mt-4" @click="openProcess(detail)">查看审批记录</Button></template>
  </Drawer>
 </Page>
</template>
