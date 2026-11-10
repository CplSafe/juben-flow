import type { VxeTableGridOptions } from '#/adapter/vxe-table';

/** Read labelled form values without rendering labels or interpreting their content as HTML. */
export function projectSummaryValue(summary: unknown, label: string): string {
  if (!Array.isArray(summary)) return '-';
  const entry = summary.find((item) => item?.key === label);
  return typeof entry?.value === 'string' && entry.value.trim()
    ? entry.value
    : '-';
}

export function projectSummaryColumns(
  nested = false,
): VxeTableGridOptions['columns'] {
  const summary = (row: {
    processInstance?: { summary?: unknown };
    summary?: unknown;
  }) => (nested ? row.processInstance?.summary : row.summary);
  return [
    {
      field: 'projectName',
      title: '项目名称',
      minWidth: 200,
      showOverflow: 'tooltip',
      formatter: ({ row }) => projectSummaryValue(summary(row), '项目名称'),
    },
    {
      field: 'projectDescription',
      title: '项目说明',
      minWidth: 320,
      showOverflow: 'tooltip',
      formatter: ({ row }) => projectSummaryValue(summary(row), '创意说明'),
    },
  ];
}
