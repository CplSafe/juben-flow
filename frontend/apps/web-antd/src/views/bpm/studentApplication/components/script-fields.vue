<script setup lang="ts">
import { computed, ref } from 'vue';
import { Button, message } from 'ant-design-vue';
import { downloadScriptFile } from './download';
const props = defineProps<{ data?: Record<string, unknown> | null; applicationId?: number }>();
const labels: Record<string,string> = {
 applicant_name:'申请人姓名',applicant_phone:'联系电话',applicant_school:'学校 / 机构',title:'剧名',genre:'题材',synopsis:'故事创意 / 剧情简介',episode_1_plot:'第一集剧本剧情',characters:'人物、身份与性格',
 title_zh:'中文剧名',title_en:'英文剧名',covered_episodes:'一卡覆盖集数',strongest_paywall_episode:'免费段最强付费卡点（集）',synopsis_zh_full:'中文完整版剧情简介',selling_points:'核心卖点',character_bios:'人物小传',episode_outline_and_paywalls:'分集大纲与付费卡点',
 planned_episodes:'预计集数',duration_min_seconds:'单集最短时长（秒）',duration_max_seconds:'单集最长时长（秒）',first_paid_episode:'首个付费解锁集',paywall_episodes:'重点付费 / 追更集数',act_outline:'分幕大纲',world_rules:'世界观与核心规则',revision_description:'本次修改说明',originality_confirmed:'原创声明确认',originality_version:'原创声明版本',originality_confirmed_at:'声明确认时间',
 real_name:'姓名',identity_type:'身份类型',school:'学校 / 机构',grade_major:'年级 / 专业',city:'所在城市',phone:'联系电话',email:'邮箱',contact_im:'微信 / QQ',specialties:'擅长题材',agreement_version:'入驻协议版本',agreement_accepted_at:'协议同意时间',
};
const fields = computed(() => Object.entries(props.data || {}).filter(([key, value]) => labels[key] && value !== undefined && value !== null && value !== ''));
interface File { file_id:number;file_name:string;file_size:number }
const files = computed(() => Array.isArray(props.data?.attachments) ? props.data.attachments as File[] : []);
const downloading = ref<number>();
function display(key:string,value: unknown) { if (typeof value === 'boolean') return value ? '已确认' : '未确认'; if(Array.isArray(value)) return value.join('、'); const identity:Record<string,string>={student:'在校学生',creator:'自然流创作者',team:'团队编剧',freelancer:'自由职业'};return typeof value==='string'?(key==='identity_type'?(identity[value]||value):value):String(value); }
async function download(file:File) { if(!props.applicationId)return;downloading.value=file.file_id;try { await downloadScriptFile(props.applicationId,file.file_id,file.file_name); } catch(error) { message.error(error instanceof Error?error.message:'附件下载失败'); }finally{downloading.value=undefined;} }
</script>
<template>
 <dl v-if="fields.length" class="space-y-3">
  <div v-for="[key, value] in fields" :key="key"><dt class="text-sm text-muted-foreground">{{ labels[key] }}</dt><dd class="mt-1 whitespace-pre-wrap break-words">{{ display(key,value) }}</dd></div>
 </dl>
 <div v-if="files.length" class="mt-4"><h4 class="font-medium">本次提交附件</h4><div v-for="file in files" :key="file.file_id" class="mt-2 flex flex-wrap items-center gap-2"><span>{{ file.file_name }} · {{ (file.file_size / 1024 / 1024).toFixed(2) }} MB</span><Button v-if="applicationId" size="small" :loading="downloading===file.file_id" @click="download(file)">下载</Button></div></div>
</template>
