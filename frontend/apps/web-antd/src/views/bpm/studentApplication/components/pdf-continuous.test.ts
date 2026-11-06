import { createApp, h, nextTick } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import PdfContinuous from './pdf-continuous.vue';
vi.mock('./pdf-page.vue', () => ({
  default: {
    props: ['index', 'active'],
    setup: (props: any) => () =>
      h('span', { 'data-active': String(props.active) }, String(props.index)),
  },
}));
let callback: IntersectionObserverCallback;
const disconnect = vi.fn();
vi.stubGlobal(
  'IntersectionObserver',
  class {
    constructor(fn: IntersectionObserverCallback) {
      callback = fn;
    }
    observe() {}
    disconnect = disconnect;
  },
);
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    disconnect = disconnect;
  },
);
const apps: Array<ReturnType<typeof createApp>> = [];
afterEach(() => {
  apps.splice(0).forEach((app) => app.unmount());
  document.body.innerHTML = '';
  vi.clearAllMocks();
});
it('lays out every page, loads nearby pages automatically and bounds active canvases', async () => {
  const surface = document.createElement('div');
  document.body.append(surface);
  const host = document.createElement('div');
  surface.append(host);
  const pdf = {
    numPages: 30,
    getPage: vi.fn().mockResolvedValue({ getViewport: () => ({ width: 600, height: 800 }) }),
  };
  const page = vi.fn();
  const app = createApp({
    render: () => h(PdfContinuous, { document: pdf as any, surface, zoom: 1, onPage: page }),
  });
  apps.push(app);
  app.mount(host);
  await new Promise((resolve) => setTimeout(resolve, 10));
  await nextTick();
  const wrappers = [...host.querySelectorAll('[data-page]')];
  expect(wrappers).toHaveLength(30);
  callback(
    wrappers.slice(0, 8).map((target) => ({ target, isIntersecting: true })) as any,
    {} as any,
  );
  await new Promise((resolve) => setTimeout(resolve, 40));
  await nextTick();
  expect(host.querySelectorAll('[data-active="true"]')).toHaveLength(4);
  // Scrolling to later pages requires no next-page button or parent page change.
  callback(
    wrappers.map((target, index) => ({ target, isIntersecting: index >= 15 && index < 18 })) as any,
    {} as any,
  );
  await new Promise((resolve) => setTimeout(resolve, 40));
  await nextTick();
  expect(
    [...host.querySelectorAll('[data-active="true"]')].map((node) => node.textContent),
  ).toEqual(['16', '17', '18']);
  expect(page).toHaveBeenLastCalledWith(16);
  app.unmount();
  apps.pop();
  expect(disconnect).toHaveBeenCalledTimes(2);
});
it('does not install observers after the document is closed during initial page loading', async () => {
  let resolvePage: (page: any) => void = () => {};
  const documentProxy = {
    numPages: 2,
    getPage: () =>
      new Promise((resolve) => {
        resolvePage = resolve;
      }),
  };
  const surface = document.createElement('div');
  document.body.append(surface);
  const host = document.createElement('div');
  surface.append(host);
  const app = createApp({
    render: () => h(PdfContinuous, { document: documentProxy as any, surface, zoom: 1 }),
  });
  app.mount(host);
  app.unmount();
  resolvePage({ getViewport: () => ({ width: 600, height: 800 }) });
  await nextTick();
  await nextTick();
  expect(disconnect).not.toHaveBeenCalled();
});
