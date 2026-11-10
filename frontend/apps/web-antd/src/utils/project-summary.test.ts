import { describe, expect, it } from 'vitest';
import { projectSummaryColumns, projectSummaryValue } from './project-summary';

type Formatter = (params: { row: unknown }) => string;

const summary = [
  { key: '创意说明', value: '创意正文\n保留第二行' },
  { key: '项目名称', value: '海棠计划' },
  { key: '故事大纲', value: '不应混入项目名称或说明' },
];

describe('project summary columns', () => {
  it.each([false, true])(
    'separates name and description for nested=%s',
    (nested) => {
      const row = nested ? { processInstance: { summary } } : { summary };
      const columns = projectSummaryColumns(nested)!;
      expect(columns.map((column) => column.title)).toEqual([
        '项目名称',
        '项目说明',
      ]);
      const values = columns.map((column) =>
        (column.formatter as Formatter)({ row }),
      );
      expect(values).toEqual(['海棠计划', '创意正文\n保留第二行']);
      expect(columns[0]!.field).not.toBe(columns[1]!.field);
      expect(JSON.stringify(row)).toContain('故事大纲');
    },
  );

  it('handles absent, empty, malformed and missing fields without mixing values', () => {
    for (const value of [
      undefined,
      null,
      [],
      {},
      [null],
      [{ key: '项目名称', value: '' }],
    ]) {
      expect(projectSummaryValue(value, '项目名称')).toBe('-');
    }
    expect(projectSummaryValue(summary, '不存在')).toBe('-');
    for (const column of projectSummaryColumns(true)!) {
      expect((column.formatter as Formatter)({ row: {} })).toBe('-');
    }
  });

  it('preserves user text and does not strip words inside the actual value', () => {
    expect(
      projectSummaryValue(
        [{ key: '项目名称', value: '项目名称：角色的秘密' }],
        '项目名称',
      ),
    ).toBe('项目名称：角色的秘密');
    expect(
      projectSummaryValue(
        [{ key: '创意说明', value: '<script>example</script>' }],
        '创意说明',
      ),
    ).toBe('<script>example</script>');
  });
});
