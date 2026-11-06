import { createApp } from 'vue';
import { describe, expect, it, vi } from 'vitest';
vi.mock('@vben-core/composables', () => ({
  useSimpleLocale: () => ({ setSimpleLocale: vi.fn() }),
}));
import { i18n, setupI18n } from './i18n';
describe('shared UI translations', () => {
  it('loads Chinese login and logout messages using the actual locale modules', async () => {
    await setupI18n(createApp({}), { defaultLocale: 'zh-CN' });
    expect(i18n.global.t('common.prompt')).toBe('提示');
    expect(i18n.global.t('common.cancel')).toBe('取消');
    expect(i18n.global.t('common.confirm')).toBe('确认');
    expect(i18n.global.t('ui.widgets.logoutTip')).toBe('是否退出登录？');
  });
});
