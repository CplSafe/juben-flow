<script setup lang="ts">
import { computed, ref } from 'vue';
import { IconifyIcon } from '@vben/icons';
import AttachmentPreview from './attachment-preview.vue';
import { readableMarkdown, formatFileSize, fileKind, type ScriptAttachment } from './reader';
import { Button, message } from 'ant-design-vue';
import { downloadScriptFile } from './download';
const props = defineProps<{ data?: Record<string, unknown> | null; applicationId?: number }>();
const labels: Record<string, string> = {
  applicant_name: '申请人姓名',
  applicant_phone: '联系电话',
  applicant_school: '学校 / 机构',
  title: '剧名',
  genre: '题材',
  synopsis: '故事创意 / 剧情简介',
  episode_1_plot: '第一集剧本剧情',
  characters: '人物、身份与性格',
  title_zh: '中文剧名',
  title_en: '英文剧名',
  covered_episodes: '一卡覆盖集数',
  strongest_paywall_episode: '免费段最强付费卡点（集）',
  synopsis_zh_full: '中文完整版剧情简介',
  selling_points: '核心卖点',
  character_bios: '人物小传',
  episode_outline_and_paywalls: '分集大纲与付费卡点',
  planned_episodes: '预计集数',
  duration_min_seconds: '单集最短时长（秒）',
  duration_max_seconds: '单集最长时长（秒）',
  first_paid_episode: '首个付费解锁集',
  paywall_episodes: '重点付费 / 追更集数',
  act_outline: '分幕大纲',
  world_rules: '世界观与核心规则',
  revision_description: '本次修改说明',
  originality_confirmed: '原创声明确认',
  originality_version: '原创声明版本',
  originality_confirmed_at: '声明确认时间',
  real_name: '姓名',
  identity_type: '身份类型',
  school: '学校 / 机构',
  grade_major: '年级 / 专业',
  city: '所在城市',
  phone: '联系电话',
  email: '邮箱',
  contact_im: '微信 / QQ',
  specialties: '擅长题材',
  agreement_version: '入驻协议版本',
  agreement_accepted_at: '协议同意时间',
};
const fields = computed(() =>
  Object.entries(props.data || {}).filter(
    ([key, value]) => labels[key] && value !== undefined && value !== null && value !== '',
  ),
);
type File = ScriptAttachment;
const preview = ref<InstanceType<typeof AttachmentPreview>>();
const bodyKeys = new Set([
  'synopsis',
  'episode_1_plot',
  'characters',
  'synopsis_zh_full',
  'selling_points',
  'character_bios',
  'episode_outline_and_paywalls',
  'act_outline',
  'world_rules',
  'revision_description',
]);
const metadata = computed(() => fields.value.filter(([key]) => !bodyKeys.has(key)));
const body = computed(() => fields.value.filter(([key]) => bodyKeys.has(key)));
const stageLabel = computed(() =>
  props.data?.synopsis
    ? '创意'
    : props.data?.synopsis_zh_full
      ? '一卡'
      : props.data?.act_outline
        ? '剧本'
        : '完善稿',
);
const files = computed(() =>
  Array.isArray(props.data?.attachments) ? (props.data.attachments as File[]) : [],
);
const downloading = ref<number>();
function display(key: string, value: unknown) {
  if (typeof value === 'boolean') return value ? '已确认' : '未确认';
  if (Array.isArray(value)) return value.join('、');
  const identity: Record<string, string> = {
    student: '在校学生',
    creator: '自然流创作者',
    team: '团队编剧',
    freelancer: '自由职业',
  };
  return typeof value === 'string'
    ? key === 'identity_type'
      ? identity[value] || value
      : value
    : String(value);
}
async function download(file: File) {
  if (!props.applicationId) return;
  downloading.value = file.file_id;
  try {
    await downloadScriptFile(props.applicationId, file.file_id, file.file_name);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '附件下载失败');
  } finally {
    downloading.value = undefined;
  }
}
</script>
<template>
  <dl v-if="metadata.length" class="script-metadata">
    <div v-for="[key, value] in metadata" :key="key">
      <dt>{{ labels[key] }}</dt>
      <dd>{{ display(key, value) }}</dd>
    </div>
  </dl>
  <section v-for="[key, value] in body" :key="key" class="script-section">
    <h4>{{ labels[key] }}</h4>
    <div class="script-prose" v-html="readableMarkdown(String(value))" />
  </section>
  <section v-if="files.length" class="attachment-section">
    <div class="attachment-heading">
      <h4>提交附件</h4>
      <span>{{ files.length }} 份 · 可在线阅读</span>
    </div>
    <div class="attachment-grid">
      <div v-for="file in files" :key="file.file_id" class="attachment-card">
        <div class="attachment-icon"><IconifyIcon icon="lucide:file-text" /></div>
        <div class="attachment-info">
          <strong>{{ file.file_name }}</strong
          ><small>{{ fileKind(file).toUpperCase() }} · {{ formatFileSize(file.file_size) }}</small>
        </div>
        <div class="attachment-actions">
          <Button
            v-if="applicationId"
            size="small"
            type="primary"
            ghost
            @click="preview?.open(file.file_id)"
            ><IconifyIcon icon="lucide:book-open" />预览</Button
          ><Button
            v-if="applicationId"
            size="small"
            type="text"
            :loading="downloading === file.file_id"
            @click="download(file)"
            >下载</Button
          >
        </div>
      </div>
    </div>
  </section>
  <AttachmentPreview
    v-if="applicationId"
    ref="preview"
    :application-id="applicationId"
    :files="files"
    :stage-label="stageLabel"
  />
</template>
<style scoped>
.script-metadata {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 32px;
  padding: 20px 22px;
  background: hsl(var(--muted) / 0.3);
  border: 1px solid hsl(var(--border));
  border-radius: 7px;
  margin-bottom: 24px;
}
.script-metadata dt {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  margin-bottom: 5px;
}
.script-metadata dd {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.script-section {
  margin: 26px 0;
}
.script-section h4 {
  font-size: 14px;
  font-weight: 650;
  padding-left: 10px;
  border-left: 3px solid hsl(var(--primary));
  margin-bottom: 15px;
}
.script-prose {
  font-size: 14px;
  line-height: 1.9;
  overflow-wrap: anywhere;
  max-width: 86ch;
}
.script-prose :deep(p) {
  margin: 0.7em 0;
}
.script-prose :deep(h1),
.script-prose :deep(h2),
.script-prose :deep(h3) {
  font-size: 1.15em;
  font-weight: 600;
  margin: 1.4em 0 0.7em;
}
.script-prose :deep(ul) {
  list-style: disc;
  padding-left: 1.6em;
}
.script-prose :deep(ol) {
  list-style: decimal;
  padding-left: 1.6em;
}
.script-prose :deep(blockquote) {
  border-left: 3px solid hsl(var(--border));
  padding: 8px 16px;
  background: hsl(var(--muted) / 0.3);
  margin: 12px 0;
}
.script-prose :deep(table) {
  border-collapse: collapse;
  width: 100%;
}
.script-prose :deep(td),
.script-prose :deep(th) {
  border: 1px solid hsl(var(--border));
  padding: 8px 12px;
}
.script-prose :deep(pre) {
  white-space: pre-wrap;
  padding: 12px;
  background: hsl(var(--muted));
  border-radius: 5px;
}
.attachment-section {
  border-top: 1px solid hsl(var(--border));
  padding-top: 20px;
  margin-top: 24px;
}
.attachment-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.attachment-heading h4 {
  font-size: 13px;
  font-weight: 600;
}
.attachment-heading span {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}
.attachment-grid {
  display: grid;
  gap: 9px;
}
.attachment-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border: 1px solid hsl(var(--border));
  border-radius: 7px;
  background: hsl(var(--background));
}
.attachment-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 40px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 0.07);
  border-radius: 5px;
  font-size: 19px;
  flex-shrink: 0;
}
.attachment-info {
  flex: 1;
  min-width: 0;
}
.attachment-info strong {
  display: block;
  font-size: 12px;
  font-weight: 500;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.attachment-info small {
  display: block;
  font-size: 10px;
  color: hsl(var(--muted-foreground));
  margin-top: 3px;
}
.attachment-actions {
  display: flex;
  gap: 5px;
  flex-shrink: 0;
}
@media (max-width: 640px) {
  .script-metadata {
    grid-template-columns: 1fr;
    padding: 16px;
  }
  .attachment-card {
    flex-wrap: wrap;
  }
  .attachment-actions {
    margin-left: 46px;
  }
}
</style>
