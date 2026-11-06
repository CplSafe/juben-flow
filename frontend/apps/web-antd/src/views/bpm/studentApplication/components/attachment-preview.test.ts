import { createApp, h, nextTick } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import AttachmentPreview from './attachment-preview.vue';
vi.mock('#/api/request', () => ({
  requestClient: { download: vi.fn(() => new Promise(() => {})) },
}));
vi.mock('./download', () => ({ downloadScriptFile: vi.fn() }));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  version: 'test',
  getDocument: vi.fn(),
}));
vi.mock('./pdf-continuous.vue', () => ({ default: { render: () => null } }));
vi.mock('ant-design-vue', () => ({
  Modal: {
    props: ['open', 'width', 'wrapClassName', 'keyboard'],
    setup:
      (p: any, { slots }: any) =>
      () =>
        p.open
          ? h(
              'section',
              { 'data-width': p.width, 'data-keyboard': String(p.keyboard) },
              slots.default?.(),
            )
          : null,
  },
  Button: {
    setup:
      (_: any, { slots }: any) =>
      () =>
        h('button', slots.default?.()),
  },
  Alert: {},
  Select: {},
  Spin: {},
  message: { error: vi.fn() },
}));
let app: ReturnType<typeof createApp>;
afterEach(() => {
  app?.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});
it('expands reading without remounting the surface, Escape restores it and unmount removes the keyboard handler', async () => {
  const host = document.createElement('div');
  document.body.append(host);
  app = createApp(AttachmentPreview, {
    applicationId: 1,
    files: [{ file_id: 1, file_name: '剧本.pdf', file_size: 100 }],
    stageLabel: '剧本',
  });
  const preview = app.mount(host) as any;
  preview.open(1);
  await nextTick();
  const surface = host.querySelector('.reader-surface');
  const toggle = () =>
    [...host.querySelectorAll('button')].find((b) =>
      /全屏/.test(b.textContent ?? ''),
    )!;
  toggle().click();
  await nextTick();
  expect(host.querySelector('section')?.getAttribute('data-width')).toBe(
    '100vw',
  );
  expect(toggle().textContent).toContain('退出全屏');
  expect(host.querySelector('.reader-surface')).toBe(surface);
  const escape = new KeyboardEvent('keydown', {
    key: 'Escape',
    cancelable: true,
    bubbles: true,
  });
  document.dispatchEvent(escape);
  await nextTick();
  expect(escape.defaultPrevented).toBe(true);
  expect(toggle().textContent).toContain('全屏阅读');
  expect(host.querySelector('.is-fullscreen')).toBeNull();
  toggle().click();
  await nextTick();
  app.unmount();
  const afterClose = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
  document.dispatchEvent(afterClose);
  expect(afterClose.defaultPrevented).toBe(false);
});
