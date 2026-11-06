<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef } from 'vue';
import { Alert, Button, Modal, Select, Spin, message } from 'ant-design-vue';
import { IconifyIcon } from '@vben/icons';
import { requestClient } from '#/api/request';
import PdfContinuous from './pdf-continuous.vue';
import { downloadScriptFile } from './download';
import { fileKind, formatFileSize, readableMarkdown, type ScriptAttachment } from './reader';
import {
  getDocument,
  GlobalWorkerOptions,
  version as pdfVersion,
  type PDFDocumentProxy,
} from 'pdfjs-dist';
const pdfAssets = `${import.meta.env.BASE_URL}pdfjs/${pdfVersion}/`;
GlobalWorkerOptions.workerSrc = `${pdfAssets}pdf.worker.min.js`;
const props = defineProps<{
  applicationId: number;
  files: ScriptAttachment[];
  stageLabel: string;
}>();
const visible = ref(false);
const selected = ref<number>();
const busy = ref(false);
const error = ref('');
const text = ref('');
const textReady = ref(false);
const page = ref(1);
const pages = ref(0);
const zoom = ref(1);
const pdf = shallowRef<PDFDocumentProxy>();
const paper = ref<HTMLElement>();
const downloading = ref(false);
const current = computed(() => props.files.find((f) => f.file_id === selected.value));
const html = computed(() =>
  current.value && fileKind(current.value) === 'md' ? readableMarkdown(text.value) : '',
);
let generation = 0;
let controller: AbortController | undefined;
let pending: ReturnType<typeof getDocument> | undefined;
function dispose() {
  controller?.abort();
  controller = undefined;
  void pending?.destroy();
  pending = undefined;
  void pdf.value?.loadingTask.destroy();
  pdf.value = undefined;
}
function close() {
  visible.value = false;
  ++generation;
  dispose();
  text.value = '';
  pages.value = 0;
}
async function load() {
  const file = current.value;
  if (!file || !visible.value) return;
  const id = ++generation;
  dispose();
  error.value = '';
  text.value = '';
  textReady.value = false;
  pages.value = 0;
  page.value = 1;
  zoom.value = 1;
  busy.value = true;
  const abort = new AbortController();
  controller = abort;
  try {
    const kind = fileKind(file);
    const max = ['txt', 'md'].includes(kind)
      ? 5 * 1024 * 1024
      : kind === 'pdf'
        ? 50 * 1024 * 1024
        : 30 * 1024 * 1024;
    if (file.file_size > max)
      throw new Error(`此附件超过在线预览限制（${max / 1024 / 1024} MB），可下载原文件阅读`);
    const blob = await requestClient.download('/bpm/student-bridge/file-preview', {
      params: { id: props.applicationId, fileId: file.file_id },
      timeout: 90_000,
      signal: abort.signal,
    });
    if (id !== generation) return;
    if (!(blob instanceof Blob)) throw new Error('预览响应无效');
    if (['txt', 'md'].includes(kind)) {
      text.value = await blob.text();
      if (id !== generation) return;
      textReady.value = true;
    } else {
      const data = new Uint8Array(await blob.arrayBuffer());
      if (id !== generation) return;
      const task = getDocument({
        data,
        enableXfa: false,
        stopAtErrors: true,
        cMapUrl: `${pdfAssets}cmaps/`,
        cMapPacked: true,
        standardFontDataUrl: `${pdfAssets}standard_fonts/`,
        wasmUrl: `${pdfAssets}wasm/`,
      });
      pending = task;
      const loaded = await task.promise;
      if (id !== generation) {
        void loaded.loadingTask.destroy();
        return;
      }
      pdf.value = loaded;
      pending = undefined;
      pages.value = loaded.numPages;
    }
  } catch (e) {
    if (id === generation && !abort.signal.aborted)
      error.value = e instanceof Error ? e.message : '预览生成失败，请重试或下载原文件';
  } finally {
    if (id === generation) {
      busy.value = false;
      await nextTick();
      if (paper.value) paper.value.scrollTop = 0;
    }
  }
}
function open(fileId: number) {
  selected.value = fileId;
  visible.value = true;
  void load();
}
async function download() {
  if (!current.value) return;
  downloading.value = true;
  try {
    await downloadScriptFile(props.applicationId, current.value.file_id, current.value.file_name);
  } catch (e) {
    message.error(e instanceof Error ? e.message : '下载失败');
  } finally {
    downloading.value = false;
  }
}
onBeforeUnmount(() => {
  ++generation;
  dispose();
});
defineExpose({ open });
</script>
<template>
  <Modal
    :open="visible"
    :footer="null"
    :width="'min(1440px, 98vw)'"
    :style="{ top: '2vh' }"
    wrap-class-name="script-preview-modal"
    :destroy-on-close="true"
    @cancel="close"
  >
    <template #title
      ><div class="preview-title">
        <IconifyIcon icon="lucide:book-open-text" /><span>{{ stageLabel }} · 附件阅读</span>
      </div></template
    >
    <div class="preview-shell">
      <aside class="preview-library">
        <div class="library-label">
          本次提交 <span>{{ files.length }} 份附件</span>
        </div>
        <button
          v-for="file in files"
          :key="file.file_id"
          :class="['library-file', { selected: file.file_id === selected }]"
          @click="
            selected = file.file_id;
            load();
          "
        >
          <span class="file-extension">{{ fileKind(file).toUpperCase() }}</span
          ><span class="file-info"
            ><strong>{{ file.file_name }}</strong
            ><small>{{ formatFileSize(file.file_size) }}</small></span
          >
        </button>
      </aside>
      <main class="preview-main">
        <header class="reader-toolbar">
          <div class="reader-filename">{{ current?.file_name }}</div>
          <div class="reader-actions">
            <template v-if="pages"
              ><span class="page-counter">{{ page }} / {{ pages }}</span
              ><Select
                v-model:value="zoom"
                size="small"
                :options="[
                  { label: '适合宽度', value: 1 },
                  { label: '125%', value: 1.25 },
                  { label: '150%', value: 1.5 },
                  { label: '200%', value: 2 },
                ]"
                class="zoom-select" /></template
            ><Button size="small" :loading="downloading" @click="download"
              ><IconifyIcon icon="lucide:download" />原文件</Button
            >
          </div>
        </header>
        <div ref="paper" class="reader-surface">
          <div v-if="busy" class="reader-state">
            <Spin size="large" />
            <h3>正在准备阅读内容</h3>
            <p>Office 文档正在转换，原有标题、段落和分页将被保留。</p>
          </div>
          <div v-else-if="error" class="reader-state">
            <IconifyIcon icon="lucide:file-warning" class="state-icon" /><Alert
              type="warning"
              :message="error"
            /><Button class="mt-4" @click="load">重新加载</Button>
          </div>
          <article v-else-if="textReady" class="document-paper">
            <div class="document-eyebrow">
              {{ stageLabel }} / {{ fileKind(current!).toUpperCase() }}
            </div>
            <h1 class="document-name">{{ current?.file_name }}</h1>
            <div v-if="fileKind(current!) === 'md'" class="document-content" v-html="html" />
            <div v-else class="document-content plain-document">{{ text }}</div>
          </article>
          <PdfContinuous
            v-else-if="pdf && paper"
            :document="pdf"
            :surface="paper"
            :zoom="zoom"
            @page="page = $event"
          />
        </div>
        <footer class="reader-footer">
          <span>仅供审核阅读 · 原始附件保持不变</span
          ><span v-if="pages">第 {{ page }} 页，共 {{ pages }} 页</span
          ><span v-else-if="textReady">{{ text.length.toLocaleString() }} 字符</span>
        </footer>
      </main>
    </div>
  </Modal>
</template>
<style scoped>
.preview-title {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 16px;
}
.preview-shell {
  display: grid;
  grid-template-columns: 238px minmax(0, 1fr);
  height: 83vh;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  overflow: hidden;
}
.preview-library {
  background: hsl(var(--muted) / 0.35);
  border-right: 1px solid hsl(var(--border));
  padding: 18px 10px;
  overflow: auto;
}
.library-label {
  padding: 0 10px 15px;
  font-size: 13px;
  font-weight: 600;
}
.library-label span {
  display: block;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
  margin-top: 4px;
}
.library-file {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  text-align: left;
  padding: 12px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  margin-bottom: 5px;
}
.library-file:hover {
  background: hsl(var(--muted));
}
.library-file.selected {
  background: hsl(var(--primary) / 0.09);
  border-color: hsl(var(--primary) / 0.25);
}
.file-extension {
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  border-radius: 5px;
  padding: 8px 4px;
  min-width: 36px;
  text-align: center;
}
.file-info {
  min-width: 0;
}
.file-info strong {
  display: block;
  font-size: 12px;
  font-weight: 500;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.file-info small {
  display: block;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  margin-top: 4px;
}
.preview-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.reader-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px;
  border-bottom: 1px solid hsl(var(--border));
  background: hsl(var(--background));
}
.reader-filename {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reader-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.page-counter {
  font-size: 12px;
  min-width: 48px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.zoom-select {
  width: 105px;
}
.reader-surface {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: hsl(var(--muted) / 0.6);
  padding: 32px;
}
.document-paper {
  background: #fff;
  color: #242831;
  margin: 0 auto;
  box-shadow: 0 3px 18px #00000010;
  width: fit-content;
  min-width: 0;
}
.document-paper {
  width: 100%;
  max-width: 850px;
  min-height: 100%;
  padding: 54px 62px;
}
.document-eyebrow {
  font-size: 10px;
  letter-spacing: 0.12em;
  color: #89909a;
  margin-bottom: 16px;
}
.document-name {
  font-size: 24px;
  line-height: 1.5;
  font-weight: 650;
  color: #252a34;
  margin: 0 0 30px;
  border-bottom: 1px solid #e8ebef;
  padding-bottom: 22px;
  overflow-wrap: anywhere;
}
.document-content {
  font-family: Georgia, 'Noto Serif SC', 'Songti SC', 'SimSun', serif;
  font-size: 16px;
  line-height: 1.95;
  overflow-wrap: anywhere;
}
.plain-document {
  white-space: pre-wrap;
}
.document-content :deep(h1),
.document-content :deep(h2),
.document-content :deep(h3) {
  font-family: system-ui, sans-serif;
  line-height: 1.5;
  margin: 1.6em 0 0.7em;
  font-weight: 650;
}
.document-content :deep(h1) {
  font-size: 26px;
}
.document-content :deep(h2) {
  font-size: 21px;
}
.document-content :deep(h3) {
  font-size: 18px;
}
.document-content :deep(p) {
  margin: 0.8em 0;
}
.document-content :deep(ul),
.document-content :deep(ol) {
  padding-left: 1.7em;
  margin: 0.8em 0;
}
.document-content :deep(ul) {
  list-style: disc;
}
.document-content :deep(ol) {
  list-style: decimal;
}
.document-content :deep(blockquote) {
  margin: 1em 0;
  padding: 10px 18px;
  border-left: 3px solid #aab5c5;
  background: #f6f7f9;
  color: #596170;
}
.document-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-family: system-ui, sans-serif;
  font-size: 13px;
}
.document-content :deep(td),
.document-content :deep(th) {
  padding: 9px 12px;
  border: 1px solid #dfe3e9;
}
.document-content :deep(th) {
  background: #f5f6f8;
  text-align: left;
}
.document-content :deep(pre) {
  white-space: pre-wrap;
  background: #f5f6f8;
  padding: 16px;
  border-radius: 4px;
}
.reader-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  height: 100%;
  max-width: 500px;
  margin: auto;
  text-align: center;
}
.reader-state h3 {
  font-size: 16px;
  font-weight: 500;
}
.reader-state p {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
.state-icon {
  font-size: 32px;
  color: hsl(var(--muted-foreground));
}
.reader-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 18px;
  border-top: 1px solid hsl(var(--border));
  font-size: 10px;
  color: hsl(var(--muted-foreground));
}
@media (max-width: 850px) {
  .preview-shell {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }
  .preview-library {
    display: flex;
    gap: 6px;
    max-height: 105px;
    border-right: 0;
    border-bottom: 1px solid hsl(var(--border));
    padding: 8px;
  }
  .library-label {
    display: none;
  }
  .library-file {
    min-width: 170px;
    width: 170px;
    margin: 0;
  }
  .reader-toolbar {
    flex-wrap: wrap;
    padding: 10px;
  }
  .reader-actions {
    gap: 5px;
    flex-wrap: wrap;
  }
  .reader-surface {
    padding: 15px;
  }
  .document-paper {
    padding: 25px;
  }
  .document-name {
    font-size: 20px;
  }
  .document-content {
    font-size: 15px;
  }
  .reader-footer {
    font-size: 9px;
  }
}
</style>
