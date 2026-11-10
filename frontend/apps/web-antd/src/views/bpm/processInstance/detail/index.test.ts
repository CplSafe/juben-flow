import { createApp, nextTick } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import Detail from './index.vue';

const deps = vi.hoisted(() => ({ push: vi.fn(), detail: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: deps.push }) }));
vi.mock('@vben/common-ui', () => ({
  Page: { render: () => null },
  useVbenModal: () => [{ render: () => null }, {}],
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: {},
  SvgBpmApproveIcon: {},
  SvgBpmCancelIcon: {},
  SvgBpmRejectIcon: {},
  SvgBpmRunningIcon: {},
}));
vi.mock('ant-design-vue', () => ({
  Avatar: {},
  Card: {},
  Col: {},
  Row: {},
  TabPane: {},
  Tabs: {},
  message: { error: vi.fn() },
}));
vi.mock('#/api/bpm/processInstance', () => ({
  getApprovalDetail: deps.detail,
  getProcessInstanceBpmnModelView: vi.fn().mockResolvedValue({}),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/components/dict-tag/dict-tag.vue', () => ({ default: {} }));
vi.mock('#/components/form-create', () => ({ setConfAndFields2: vi.fn() }));
vi.mock('#/utils', () => ({ registerComponent: vi.fn() }));
vi.mock('./modules/bpm-viewer.vue', () => ({ default: {} }));
vi.mock('./modules/comment-list.vue', () => ({ default: {} }));
vi.mock('./modules/operation-button.vue', () => ({ default: {} }));
vi.mock('./modules/process-print.vue', () => ({ default: {} }));
vi.mock('./modules/simple-bpm-viewer.vue', () => ({ default: {} }));
vi.mock('./modules/task-list.vue', () => ({ default: {} }));
vi.mock('./modules/time-line.vue', () => ({ default: {} }));
vi.mock('../../studentApplication/components/process-submissions.vue', () => ({
  default: {},
}));
let app: ReturnType<typeof createApp>;
afterEach(() => {
  app?.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});
it('refreshes retained detail and navigates to pending tasks after completion', async () => {
  deps.detail.mockResolvedValue({
    processDefinition: { formType: 20 },
    processInstance: { id: 'process-1', status: 1 },
    activityNodes: [],
  });
  deps.push.mockResolvedValue(undefined);
  const host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail, { id: 'process-1' });
  app.directive('loading', {});
  const vm = app.mount(host);
  await nextTick();
  await Promise.resolve();
  await nextTick();
  expect(deps.detail).toHaveBeenCalledOnce();
  await (vm.$.setupState as Record<string, any>).handleTaskCompleted();
  expect(deps.detail).toHaveBeenCalledTimes(2);
  expect(deps.push).toHaveBeenCalledWith('/bpm/task/todo');
});
