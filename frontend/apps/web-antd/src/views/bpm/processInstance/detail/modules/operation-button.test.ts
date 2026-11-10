import { createApp } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Operations from './operation-button.vue';

const api = vi.hoisted(() => ({
  approveTask: vi.fn(),
  rejectTask: vi.fn(),
  transferTask: vi.fn(),
  delegateTask: vi.fn(),
  returnTask: vi.fn(),
  createComment: vi.fn(),
}));
vi.mock('#/api/bpm/task', () => ({
  ...api,
  copyTask: vi.fn(),
  getTaskListByReturn: vi.fn(),
  signCreateTask: vi.fn(),
  signDeleteTask: vi.fn(),
}));
vi.mock('#/api/bpm/comment', () => ({ createComment: api.createComment }));
vi.mock('#/api/bpm/processInstance', () => ({
  cancelProcessInstanceByStartUser: vi.fn(),
  getNextApprovalNodes: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [{ render: () => null }, {}],
}));
vi.mock('@vben/stores', () => ({
  useUserStore: () => ({ userInfo: { id: 1 } }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('#/components/form-create', () => ({ setConfAndFields2: vi.fn() }));
vi.mock('#/components/upload', () => ({ FileUpload: { render: () => null } }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./signature.vue', () => ({ default: { render: () => null } }));
vi.mock('./time-line.vue', () => ({ default: { render: () => null } }));
vi.mock('@form-create/ant-design-vue', () => ({
  default: { render: () => null },
}));
vi.mock('ant-design-vue', async () => {
  const { defineComponent, h } = await import('vue');
  const Stub = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: Stub,
    Button: Stub,
    Card: Stub,
    Form: Stub,
    FormItem: Stub,
    Image: Stub,
    Popover: Stub,
    Select: Stub,
    SelectOption: Stub,
    Space: Stub,
    Textarea: Stub,
    message: { success: vi.fn(), warning: vi.fn() },
  };
});

let app: ReturnType<typeof createApp>;
afterEach(() => {
  app?.unmount();
  document.body.innerHTML = '';
  vi.resetAllMocks();
});
function mount() {
  const completed = vi.fn(),
    success = vi.fn();
  app = createApp(Operations, {
    normalForm: {},
    normalFormApi: {},
    processDefinition: {},
    processInstance: { id: 'process-1' },
    userOptions: [],
    writableFields: [],
    onCompleted: completed,
    onSuccess: success,
  });
  app.directive('loading', {});
  const host = document.createElement('div');
  document.body.append(host);
  const vm = app.mount(host);
  // Exercise the actual SFC handlers and awaited API boundary, without opening UI popovers.
  const state = vm.$.setupState as Record<string, any>;
  state.runningTask = { id: 'task-1', status: 1, children: [] };
  return { state, completed, success };
}
const form = () => ({
  validate: vi.fn().mockResolvedValue(undefined),
  resetFields: vi.fn(),
});

describe('task operation completion', () => {
  it.each([true, false])(
    'emits completion only after audit API succeeds, pass=%s',
    async (pass) => {
      const { state, completed, success } = mount();
      await state.handleAudit(pass, form());
      expect(pass ? api.approveTask : api.rejectTask).toHaveBeenCalledOnce();
      expect(completed).toHaveBeenCalledOnce();
      expect(success).not.toHaveBeenCalled();
    },
  );
  it('keeps the detail page on a failed approval or failed validation', async () => {
    const { state, completed } = mount();
    api.approveTask.mockRejectedValueOnce(new Error('request failed'));
    await expect(state.handleAudit(true, form())).rejects.toThrow(
      'request failed',
    );
    expect(completed).not.toHaveBeenCalled();
    await expect(
      state.handleAudit(true, {
        validate: () => Promise.reject(new Error('invalid')),
      }),
    ).rejects.toThrow('invalid');
    expect(completed).not.toHaveBeenCalled();
  });
  it.each([
    ['handleTransfer', 'transferFormRef', 'transferTask'],
    ['handleDelegate', 'delegateFormRef', 'delegateTask'],
    ['handleReturn', 'returnFormRef', 'returnTask'],
  ] as const)('returns after successful %s', async (handler, ref, method) => {
    const { state, completed, success } = mount();
    state[ref] = form();
    await state[handler]();
    expect(api[method]).toHaveBeenCalledOnce();
    expect(completed).toHaveBeenCalledOnce();
    expect(success).not.toHaveBeenCalled();
  });

  it('comments refresh detail without completing the task', async () => {
    const { state, completed, success } = mount();
    state.commentForm.message = 'test comment';
    state.commentFormRef = form();
    await state.handleComment();
    expect(api.createComment).toHaveBeenCalledOnce();
    expect(success).toHaveBeenCalledOnce();
    expect(completed).not.toHaveBeenCalled();
  });
});
