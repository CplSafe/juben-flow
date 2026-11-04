<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { Alert, Button, Space, Table, Tag, message } from 'ant-design-vue';
import { requestClient } from '#/api/request';

interface Row {
 id: number; user_id: number; external_user_id?: string; name: string; phone: string;
 school: string; status?: number; creative?: string; process_id?: string; application_id?: string;
}

const rows = ref<Row[]>([]);
const loading = ref(false);
const failure = ref(false);
const offset = ref(0);

const changing = ref<number>();
const columns = [
 { title: '姓名', dataIndex: 'name', width: 140 },
 { title: '手机号', dataIndex: 'phone', width: 160 },
 { title: '学校', dataIndex: 'school', width: 240 },
 { title: '工作流用户ID', dataIndex: 'user_id', width: 140 },
 { title: '外部学生ID', dataIndex: 'external_user_id', width: 160 }, { title: '状态', key: 'status', width: 100 },
 { title: '操作', key: 'action', width: 160 },
];
async function load() {
 loading.value = true; failure.value = false;
 try { rows.value = await requestClient.get<Row[]>('/bpm/student-bridge/students', { params: { offset: offset.value } }); }
 catch { failure.value = true; }
 finally { loading.value = false; }
}
async function page(delta: number) { offset.value = Math.max(0, offset.value + delta); await load(); }
async function changeStatus(id: number) {
 const row = rows.value.find((item) => item.id === id);
 if (!row) return;
 changing.value = row.id;
 try {
 await requestClient.put('/bpm/student-bridge/student-status', null, { params: { id: row.id, status: row.status === 0 ? 1 : 0 } });
 message.success('学生状态已更新'); await load();
 } finally { changing.value = undefined; }
}
onMounted(load);
</script>
<template>
 <Page title="学生管理">
  <Space class="mb-4"><Button :loading="loading" @click="load">刷新</Button></Space>
  <Alert v-if="failure" type="error" message="加载失败，请重试" class="mb-4" />
  <Table :columns="columns" :data-source="rows" :loading="loading" :pagination="false" row-key="id" :scroll="{ x: 900 }">
   <template #bodyCell="{ column, record }">
    <template v-if="column.key === 'status'"><Tag :color="record.status === 0 ? 'green' : 'default'">{{ record.status === 0 ? '启用' : '停用' }}</Tag></template>
    <template v-if="column.key === 'action'">
     <Button v-access:code="['bpm:student:update']" type="link" :loading="changing === record.id" @click="changeStatus(record.id)">{{ record.status === 0 ? '停用' : '启用' }}</Button>
    </template>
   </template>
  </Table>
  <Space class="mt-4"><Button :disabled="offset === 0 || loading" @click="page(-50)">上一页</Button><span>第 {{ offset / 50 + 1 }} 页</span><Button :disabled="rows.length < 50 || loading" @click="page(50)">下一页</Button></Space>
 </Page>
</template>
