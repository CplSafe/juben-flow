import { requestClient } from '#/api/request';
let active = false;
export async function downloadScriptFile(applicationId: number, fileId: number, name: string) {
  if (active) throw new Error('请等待当前附件下载完成');
  active = true;
  try {
    const blob = await requestClient.download('/bpm/student-bridge/file-download', { params: { id: applicationId, fileId } });
    if (!(blob instanceof Blob)) throw new Error('附件下载响应无效');
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } finally { active = false; }
}
