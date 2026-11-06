<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import PdfPage from './pdf-page.vue';
const props = defineProps<{ document: PDFDocumentProxy; surface: HTMLElement; zoom: number }>();
const emit = defineEmits<{ page: [number] }>();
const root = ref<HTMLElement>();
const width = ref(800);
const ratio = ref(1.414);
const failed = ref(false);
const active = ref<number[]>([]);
const nearby = new Set<number>();
let observer: IntersectionObserver | undefined;
let resize: ResizeObserver | undefined;
let frame = 0;
let disposed = false;
function update() {
  frame = 0;
  if (!root.value) return;
  const top = props.surface.getBoundingClientRect().top;
  const candidates = [...nearby]
    .map((index) => ({ index, element: root.value!.children[index - 1] as HTMLElement }))
    .map((item) => ({
      ...item,
      distance: Math.abs(item.element.getBoundingClientRect().top - top - 24),
    }))
    .sort((a, b) => a.distance - b.distance);
  // Keep a bounded canvas working set, including the next page before it is needed.
  active.value = candidates.slice(0, 4).map((item) => item.index);
  const current = candidates[0];
  if (current) emit('page', current.index);
}
function schedule() {
  if (!frame) frame = requestAnimationFrame(update);
}
onMounted(async () => {
  try {
    width.value = Math.max(280, props.surface.clientWidth - 80);
    const first = await props.document.getPage(1);
    if (disposed) return;
    const viewport = first.getViewport({ scale: 1 });
    ratio.value = viewport.height / viewport.width;
    await nextTick();
    if (disposed || !root.value) return;
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const index = Number((entry.target as HTMLElement).dataset.page);
          if (entry.isIntersecting) nearby.add(index);
          else nearby.delete(index);
        }
        schedule();
      },
      { root: props.surface, rootMargin: '600px 0px' },
    );
    for (const node of root.value.children) observer.observe(node);
    resize = new ResizeObserver(() => {
      width.value = Math.max(280, props.surface.clientWidth - 80);
      schedule();
    });
    resize.observe(props.surface);
    props.surface.addEventListener('scroll', schedule, { passive: true });
  } catch {
    if (!disposed) failed.value = true;
  }
});
onBeforeUnmount(() => {
  disposed = true;
  observer?.disconnect();
  resize?.disconnect();
  props.surface.removeEventListener('scroll', schedule);
  cancelAnimationFrame(frame);
});
</script>
<template>
  <p v-if="failed" role="alert">文档加载失败，请重新打开附件或下载原文件。</p>
  <div ref="root" class="continuous-document">
    <div v-for="index in document.numPages" :key="index" :data-page="index">
      <PdfPage
        :document="document"
        :index="index"
        :active="active.includes(index)"
        :width="width"
        :zoom="zoom"
        :ratio="ratio"
      />
    </div>
  </div>
</template>
