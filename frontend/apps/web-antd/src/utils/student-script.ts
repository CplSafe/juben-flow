/** Display alias for existing immutable workflow definitions; historical records stay unchanged. */
export function studentTaskName(value: unknown) {
  const name = typeof value === 'string' ? value : String(value ?? '');
  return name === '学生完善一卡剧本' ? '学生完善剧本' : name;
}
