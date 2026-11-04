# 帧智汇校园创意申请——前后端对接文档

版本：本地联调 v0.1，2026-10-31。本文对应当前 student-intake/server.py，不代表已公网部署。

## 1. 推荐方式与职责

推荐：合作系统登录 → 点击申请 → 合作后端确认当前用户 → 换取一次性链接 → 浏览器跳到帧智汇申请页 → 填写并提交 → 现有 SIMPLE 流程 → 帧智汇后台查询。

合作前端仅负责跳转；合作后端负责认证本系统用户并交换身份。申请表单由帧智汇托管。学校学生无须拥有帧智汇后台账号。

业务身份使用 source + externalUserId 组合，必须稳定且唯一。姓名只是显示/填写信息，不能代替用户ID。当前表单姓名可编辑，代表申请人填写的姓名，不等于学校实名认证结果。

## 2. 联调配置

| 配置 | 当前值/说明 |
|---|---|
| 接口 Base URL | http://localhost:5670，仅本机可访问 |
| 申请页 | /，必须携带身份交换返回的 fragment 授权码 |
| 帧智汇管理页 | /admin，仅帧智汇超管登录 |
| 来源系统 source | 双方约定，例如 school-system |
| 服务端凭证 | INTAKE_CLIENT_SECRET，线下安全交付，不在本文附真实值 |
| 公网入口 | 待部署 HTTPS 反向代理后确定，设置 INTAKE_PUBLIC_URL 为学生可访问的域名 |

注意：对方电脑上的 localhost 指向对方自己，不能直接访问我们的本机。当前监听127.0.0.1；跨机器联调需先部署可达入口，或约定隧道。当前仅配置单一可信合作后端凭证，尚无多来源独立客户端管理。

## 3. 合作后端必须接入的接口

POST /api/identity/exchange

请求头：Content-Type: application/json；Authorization: Bearer <服务端凭证>。

请求体：
```json
{"source":"school-system","externalUserId":"student-10001","name":"张同学"}
```

| 字段 | 必填 | 限制 |
|---|---|---|
| source | 是 | 去除首尾空格后1–80字符，双方约定固定值 |
| externalUserId | 是 | 去除首尾空格后1–160字符，来自后端当前登录用户 |
| name | 否 | 显示姓名，最多保留80字符 |

成功：HTTP 200，响应直接为对象，没有统一 code/data 外壳。
```json
{"code":"<一次性随机授权码>","expiresIn":300,"applicationUrl":"https://apply.example.com/#code=<一次性随机授权码>"}
```

每次点击都实时交换新链接。授权码有效5分钟，成功交换填写会话后立即作废。不要把链接做成长期二维码，不要在跳转前调用 /api/session 做健康检查，否则会提前消耗授权码。此前本机演示码延长到24小时是临时调试处理，不是接口默认策略。

curl 示例（仅从服务器执行）：
```sh
curl --request POST "$INTAKE_BASE_URL/api/identity/exchange" \
  --header "Authorization: Bearer $INTAKE_CLIENT_SECRET" \
  --header 'Content-Type: application/json' \
  --data '{"source":"school-system","externalUserId":"student-10001","name":"张同学"}'
```

合作后端伪代码：
```text
POST /api/student-creative-entry（合作系统自己的接口）
1. 从已验证登录会话获取 currentUser；未登录则拒绝。
2. 由服务器构造 source、externalUserId、name；不接受前端传入的身份替换。
3. 后端携带集成凭证调用帧智汇 /api/identity/exchange。
4. 成功后返回 applicationUrl 给前端；设置 Cache-Control: no-store。
5. 错误时返回明确失败，不缓存/复用旧授权链接。
```

## 4. 合作前端接入

页面放一个“提交校园创意”按钮，点击调用合作后端接口后顶层跳转：
```js
async function openCreativeApplication() {
  const response = await fetch('/api/student-creative-entry', {
    method: 'POST',
    credentials: 'include'
    // 按合作系统已有约定补充CSRF令牌。
  });
  if (!response.ok) throw new Error('暂时无法打开申请，请稍后重试');
  const { applicationUrl } = await response.json();
  window.location.assign(applicationUrl);
}
```

按钮请求期间禁用，避免重复点击。服务端凭证只放合作后端，不放JavaScript、URL或浏览器存储。优先顶层跳转；当前未承诺 iframe 跨站存储兼容性。

## 5. 帧智汇托管页面内部接口（合作方通常不用调用）

### POST /api/session

请求：{"code":"<一次性授权码>"}。无需额外Authorization。
成功HTTP200：{"session":"<填写会话令牌>","name":"张同学"}。
会话1小时有效；当前网页存在sessionStorage。过期/已使用的code返回403。新设备或新浏览器应从合作系统重新交换进入，不要反复打开已消费的旧链接。

### POST /api/apply

Authorization: Bearer <填写会话令牌>；JSON：
```json
{"name":"张同学","phone":"13800000000","school":"示例大学","creative":"我的故事创意……"}
```

四项均必填。姓名≤80字符、学校≤200字符、电话7–25字符（当前仅长度校验，未做短信核验）、创意≤12000字符。请求体最大65536字节（UTF-8字节数与字符数不同）。当前不支持附件上传。

首次成功HTTP201：{"id":"<申请编号>","state":"submitted"}。
同一会话重复提交HTTP200：返回原申请id和state，不覆盖原内容、不重复发起流程；并发请求可能看到pending。
流程启动失败或结果不确定HTTP202：{"id":"<申请编号>","state":"needs_review","message":"申请已保存，流程启动需管理员确认，请勿重复提交"}。
202不能展示成“审批已开始”。管理员人工核对；当前无自动重试/恢复接口。

幂等范围是填写会话：重新交换新链接得到新会话后可以产生新申请，尚无跨会话业务幂等键。

## 6. 状态、错误与查询边界

申请接入状态：pending=已保存正在启动；submitted=已启动流程；needs_review=启动需人工核对。
submitted不是“审批通过”，审批最终状态需查看关联流程实例。

常见错误响应：{"error":"说明"}。
- 400：缺字段、内容不合法、JSON错误。
- 403：集成凭证错误，或授权码/填写会话无效。
- 413：请求体过大。
- 502：依赖服务不可用；不可据此判断提交肯定未成功。提交重试需复用同一会话。

当前对合作系统没有申请查询API、审批回调Webhook、自动返回原系统地址，也没有对学生开放的进度查询页。不要使用集成凭证调用管理员查询接口。

帧智汇内部：GET /api/applications（最近200条）；GET /api/application/{申请编号}。这两个接口需要帧智汇超级管理员Bearer token。详情包含来源系统、外部用户ID、姓名电话学校创意、process_id、流程实例及审批任务。没有向合作前端开放CORS；推荐托管跳转，若需对方自建表单应另行设计同源后端代理和会话归属。

## 7. 双方联调验收

1. 使用合作系统真实登录用户点击按钮，学校后端取正确用户ID。
2. 新链接进入表单，填写四项后提交，取得申请编号。
3. 帧智汇管理页能查到source、externalUserId、姓名、电话、学校、创意。
4. Sam或宇哥待办出现，任一人通过后往下走；其余节点按当前模型办理。
5. 管理详情能查询同一申请的审批记录。
6. 验证错误凭证403、授权码重复消费403、缺字段400、同会话重复提交返回原编号。

已完成本地API联调验证：身份交换、code重放拒绝、提交启动、同会话重复提交、管理员申请详情。尚未与任何学校生产系统进行联合验收。

## 8. 上线前双方必须确定

可访问HTTPS域名、固定source及身份ID规则、凭证安全交付与轮换、姓名是否要求学校实名锁定、申请人的告知内容、是否允许重复报名、数据保留与删除规则。公网发布还需限流、持久化与备份、监控，以及审批启动异常恢复方案。
