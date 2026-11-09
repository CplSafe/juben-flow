import { createApp, defineComponent, h, KeepAlive, nextTick, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import Todo from './index.vue';

const { fetchPage } = vi.hoisted(() => ({ fetchPage: vi.fn() }));
vi.mock('#/api/bpm/task', () => ({ getTaskTodoPage: fetchPage }));
vi.mock('#/router', () => ({ router: { push: vi.fn() } }));
vi.mock('./data', () => ({ useGridColumns: () => [], useGridFormSchema: () => [] }));
vi.mock('@vben/common-ui', () => ({ Page: { setup: (_: unknown, { slots }: any) => () => slots.default?.() }, DocAlert: {} }));
vi.mock('#/adapter/vxe-table', async () => {
 const { defineComponent, h, onMounted, ref } = await import('vue');
 return {
  ACTION_ICON: {}, TableAction: {},
  useVbenVxeGrid: (options: any) => {
   const data = ref<unknown>();
   const query = async () => { data.value = await options.gridOptions.proxyConfig.ajax.query({ page: { currentPage: 3, pageSize: 20 } }, { name: 'filter' }); };
   // Vxe's normal first mount query; cached activation does not remount it.
   onMounted(query);
   return [defineComponent(() => () => h('div', { id: 'rows' }, JSON.stringify(data.value))), { query }];
  },
 };
});
let app: ReturnType<typeof createApp>;
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks(); });
async function settle() { await nextTick(); await Promise.resolve(); await nextTick(); }
it('reloads a cached todo list when returning without resetting its page or filters', async () => {
 fetchPage.mockResolvedValue({ list: [{ id: 'old-task' }], total: 1 });
 const active = ref(true);
 const Other = defineComponent(() => () => h('div', 'other menu'));
 const host = document.createElement('div'); document.body.append(host);
 app = createApp({ setup: () => () => h(KeepAlive, null, { default: () => active.value ? h(Todo) : h(Other) }) });
 app.mount(host); await settle();
 expect(fetchPage).toHaveBeenCalledTimes(1);
 expect(host.textContent).toContain('old-task');
 active.value = false; await settle();
 fetchPage.mockResolvedValue({ list: [{ id: 'new-task' }], total: 1 });
 active.value = true; await settle();
 expect(fetchPage).toHaveBeenCalledTimes(2);
 expect(fetchPage).toHaveBeenLastCalledWith({ pageNo: 3, pageSize: 20, name: 'filter' });
 expect(host.textContent).toContain('new-task');
});
