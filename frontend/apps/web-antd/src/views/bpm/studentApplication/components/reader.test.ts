// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { readableMarkdown } from './reader';
import { studentTaskName } from '../../../../utils/student-script';
describe('private script reading', () => {
  it('retains headings lists emphasis and table layout', () => {
    const html = readableMarkdown(
      '# 第一幕\n\n**人物**\n\n- 主角\n- 配角\n\n|集数|剧情|\n|---|---|\n|1|相遇|',
    );
    expect(html).toContain('<h1>第一幕</h1>');
    expect(html).toContain('<strong>人物</strong>');
    expect(html).toContain('<table>');
    expect(html).toContain('<li>主角</li>');
  });
  it('does not run markup or load remote attachment images', () => {
    const host = document.createElement('div');
    host.innerHTML = readableMarkdown(
      '<script>alert(1)</script>\n\n![tracker](https://example.test/private?id=1)\n\n[bad](javascript:alert(1))\n\n<iframe src="https://example.test"></iframe>',
    );
    expect(host.querySelector('script,iframe,img')).toBeNull();
    expect(host.querySelector('[href^="javascript:"]')).toBeNull();
  });
  it('opens explicit links safely without leaving the review page', () => {
    const host = document.createElement('div');
    host.innerHTML = readableMarkdown('[参考](https://example.test)');
    expect(host.querySelector('a')?.getAttribute('target')).toBe('_blank');
    expect(host.querySelector('a')?.getAttribute('rel')).toBe('noopener noreferrer');
  });
  it('renames only the known display label without modifying audit records', () => {
    expect(studentTaskName('学生完善一卡剧本')).toBe('学生完善剧本');
    expect(studentTaskName('学生填写一卡')).toBe('学生填写一卡');
  });
});
