<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from 'pdfjs-dist';
const props = defineProps<{
  document: PDFDocumentProxy;
  index: number;
  active: boolean;
  width: number;
  zoom: number;
  ratio: number;
}>();
const canvas = ref<HTMLCanvasElement>();
const ratio = ref<number>();
const failed = ref(false);
const ready = ref(false);
const size = computed(() => ({
  width: `${props.width * props.zoom}px`,
  height: `${props.width * props.zoom * (ratio.value ?? props.ratio)}px`,
}));
let generation = 0;
let task: RenderTask | undefined;
let loadedPage: PDFPageProxy | undefined;
function cancel() {
  ++generation;
  task?.cancel();
}
async function render() {
  cancel();
  const current = generation;
  const previous = task;
  if (previous) await previous.promise.catch(() => {});
  task = undefined;
  if (current !== generation) return;
  if (!props.active) {
    loadedPage?.cleanup();
    return;
  }
  ready.value = false;
  failed.value = false;
  try {
    const page = await props.document.getPage(props.index);
    if (current !== generation) return;
    loadedPage = page;
    const original = page.getViewport({ scale: 1 });
    ratio.value = original.height / original.width;
    await nextTick();
    const node = canvas.value;
    if (!node || current !== generation) return;
    const scale = Math.min(
      (props.width * props.zoom) / original.width,
      Math.sqrt(8_000_000 / (original.width * original.height)),
    );
    const viewport = page.getViewport({ scale });
    node.width = Math.ceil(viewport.width);
    node.height = Math.ceil(viewport.height);
    const rendering = page.render({ canvas: node, viewport });
    task = rendering;
    await rendering.promise;
    if (current === generation) ready.value = true;
  } catch (error) {
    if (
      current === generation &&
      !(error instanceof Error && error.name === 'RenderingCancelledException')
    )
      failed.value = true;
  }
}
watch(
  () => [props.active, props.width, props.zoom],
  () => void render(),
  { immediate: true, flush: 'post' },
);
onBeforeUnmount(() => {
  cancel();
  const rendering = task;
  void (rendering?.promise ?? Promise.resolve())
    .catch(() => {})
    .then(() => {
      try {
        loadedPage?.cleanup();
      } catch {
        /* Document may already be destroyed. */
      }
    });
});
</script>
<template>
  <div class="continuous-page" :style="size" :aria-label="`文档第 ${index} 页`">
    <canvas v-if="active" ref="canvas" :class="{ pending: !ready }" />
    <div v-if="!ready || !active" class="page-placeholder">
      <template v-if="failed"
        >第 {{ index }} 页加载失败 <button @click="render">重试</button></template
      >
      <template v-else>第 {{ index }} 页<span v-if="active"> · 正在加载…</span></template>
    </div>
  </div>
</template>
<style scoped>
.continuous-page {
  position: relative;
  flex-shrink: 0;
  background: #fff;
  color: #737b88;
  box-shadow: 0 3px 18px #00000010;
  margin: 0 auto 24px;
}
canvas {
  display: block;
  width: 100%;
  height: 100%;
}
.pending {
  visibility: hidden;
}
.page-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 12px;
}
button {
  color: #1677ff;
  text-decoration: underline;
}
</style>
