# 校园创意申请：前端对接说明

实现位置：zhenzhihui Go 后端。链路：学生前端 → Go业务后端 → Java工作流。无需Python接入服务，无需 /api/identity/exchange。

## 身份与入口

使用原 zhenzhihui 登录方式（Bearer 或既有登录Cookie），三个接口都在现有受保护 /api/v1 路由组内。用户归属由服务端登录态决定；不要传 user_id、外部身份或流程定义ID。前端不传学生ID或工作流用户ID。学生ID取当前Go登录用户。

Go后端部署地址沿用原业务API域名，不是 flow-dev 的 Java /admin-api。发布必须同时更新Go、Java和Vben，并配置共享密钥及学生部门权限。生产环境部署状态以发布记录为准。

## 提交

POST /api/v1/student-applications

请求头：Content-Type: application/json，X-Idempotency-Key: <本次提交的唯一编号>。

```json
{"name":"张同学","phone":"13800000000","school":"示例大学","creative":"我的故事创意……"}
```

四项均必填。去除首尾空格后姓名≤80字符，学校≤200字符，创意≤12000字符；电话7–25位，允许数字、+、括号、空格、短横线，当前未短信验证。姓名为填写信息，不代表学校认证姓名。暂不支持附件。

X-Idempotency-Key：8–80位英文字母/数字/下划线/短横线，建议 crypto.randomUUID()。同一次申请生成一次并保留，双击、网络失败、超时重试均复用原编号和原内容，不要每次请求生成新编号。

首次HTTP201，重复HTTP200，均采用业务后端统一响应：
```json
{"code":0,"code_string":"OK","message":"成功","data":{"id":123,"user_id":7,"name":"张同学","phone":"13800000000","school":"示例大学","creative":"我的故事创意……","state":"submitted","process_id":"流程实例ID","created_at":"2026-11-04T12:00:00Z","updated_at":"2026-11-04T12:00:01Z"}}
```

必须读取state，不要只凭HTTP201显示“已进入审批”：
- pending：申请已保存，启动结果尚未写回（并发提交/进程中断时可出现）；不要换编号重发。
- submitted：已关联工作流实例；不表示审批通过。
- needs_review：申请已保存，但流程启动失败或结果不确定，需要管理员核对；禁止自动重启流程，以免重复申请。

相同用户+相同Key最多调用一次流程发起。相同Key用于不同内容返回409 CONFLICT。不同用户的Key相互独立。服务未配置返回503 WORKFLOW_UNAVAILABLE且不创建申请。参数无效400；未登录401；系统异常500。网络失败/500后重试必须使用原Key，已持久化的记录不会重复启动。

## 我的申请列表

GET /api/v1/student-applications?limit=20&offset=0

返回data为 {"items":[申请对象],"total":1}。仅当前用户自己的数据，按id倒序；limit默认20，范围1–100，超限回退20；offset负数回退0。列表state为接入状态，不是实时审批状态。

## 我的申请详情和实时审批状态

GET /api/v1/student-applications/123

```json
{"code":0,"code_string":"OK","message":"成功","data":{"application":{"id":123,"state":"submitted","process_id":"...","name":"张同学","phone":"13800000000","school":"示例大学","creative":"..."},"progress_available":true,"workflow":{"status":1,"name":"编剧创作与制作流程","end_time":null}}}
```

不存在或不属于当前用户都返回404，不能通过编号查其他学生申请。workflow.status沿用Java流程状态（1审批中、2审批通过、3审批不通过、4已取消）；end_time为Unix毫秒或空。

工作流暂不可用或申请尚无process_id时，申请本身仍返回200，progress_available=false、workflow=null。页面展示“申请记录已保存，审批状态暂不可用”，不要当成驳回。

此版不向学生返回审批内部备注、审批人员个人资料、原始任务数据，也没有审批Webhook。查询按需获取实时状态，不会修改或重新发起流程。后续若需节点轨迹需单独增加经过筛选的展示字段。

## 前端示例

```js
// 用户进入填写时生成并保存；提交失败重试继续使用，成功后开始另一申请才更换。
const requestKey = crypto.randomUUID();
async function submitApplication(form, accessToken) {
  const response = await fetch('/api/v1/student-applications', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'X-Idempotency-Key': requestKey,
    },
    body: JSON.stringify(form),
  });
  const body = await response.json();
  if (!response.ok || body.code !== 0) throw new Error(body.message);
  return body.data; // 根据 state 展示处理结果。
}
```

按现有项目的请求封装和Cookie/CSRF约定调用即可，无需接触工作流服务凭证。正式跨域若使用自定义X-Idempotency-Key，Go现有CORS已允许该Header（与Java工作流CORS是两层不同配置）。

## 学生本人发起与后台管理

Go 根据当前登录用户ID发送签名请求。Java 以固定来源 `zhenzhihui` + Go用户ID建立唯一学生映射，第一次提交时创建工作流学生账号，分配到“校园学生”部门，使用随机密码，不复制原系统密码、不授予后台角色。以后复用该账号。填写的姓名为学生自报信息，不是实名或学校认证。

Java 调用官方 `BpmProcessInstanceService.createProcessInstance(studentUserId, DTO)`，以学生本人启动当前已发布的 `screenwriting_production` SIMPLE流程。Flowable 的发起人和START_USER_ID_均为该学生，不是服务账号。审批节点、Sam与宇哥的或签规则、最后抄送保持现有模型配置。

Java 在同一事务内同步学生、保存申请快照、启动Flowable并记录关联。唯一键与行锁防止重复注册和重复发起。相同来源+申请ID若换了学生或表单内容，拒绝请求；相同内容重放返回同一流程。学生停用后同步不会自动重新启用。

后台增加两个入口：

- 工作流 → 学生管理：姓名、电话、学校、原系统ID、工作流用户ID、启用/停用。
- 工作流 → 创意申请：查看提交表单快照，进入原流程详情查看审批记录。

权限分别为 `bpm:student:query`、`bpm:student:update`、`bpm:student-application:query`。学生不会自动拥有这些权限；管理员按既有角色分配。审批详情仍使用原BPM权限校验。

## 发布配置

Go：

```text
ZZH_WORKFLOW_URL=https://flow-dev.zhongdahengrui.com/admin-api
ZZH_WORKFLOW_BRIDGE_SECRET=<至少32字节随机共享密钥>
```

Java：

```yaml
yudao:
  student-bridge:
    secret: ${ZZH_WORKFLOW_BRIDGE_SECRET:}
    student-dept-id: ${ZZH_WORKFLOW_STUDENT_DEPT_ID:0}
    process-key: screenwriting_production
```

密钥只放两端私有环境配置，不给前端；它是后端集成身份，不是工作流服务账户。不再使用 `ZZH_WORKFLOW_USERNAME/PASSWORD/PROCESS_KEY`。

1. 执行工作流仓库 `backend/sql/student-bridge/install.sql`（原Java源码为 `sql/student-bridge/install.sql`），只新增表、学生部门和菜单，不删除已有数据。记录返回的部门ID并配置到Java；各环境ID不同。
2. 此桥接面向当前已关闭多租户的部署。SIMPLE模型的可发起权限切换到部门方式，清空指定用户列表，保留现有工作人员部门并增加校园学生，再发布。官方引擎中非空用户列表优先于部门列表；桥接不绕过该权限检查。修改前备份模型配置。
3. 更新Java后端、Vben前端与Go后端。Go迁移创建独立的student_applications表；Java创建bpm_external_student和bpm_external_application。先验证Java验签与发起权限，再开放Go提交入口。
4. 旧Python服务不再部署；原历史数据保留，新请求不使用授权链接或 `/api/identity/exchange`。

URL未配置则Go返回503，不回退Python；配置了URL但密钥缺失或过短则Go启动失败。仅允许HTTPS或本机HTTP，不跟随重定向，不向学生返回远端敏感错误正文。

needs_review/pending暂由管理员按来源和applicationId核对Java是否已有流程，再修复Go关联。本版没有自动恢复接口；前端不得换新Key自动重发。

## Swagger

Go三条学生接口已纳入现有Swagger的`StudentApplications`分组：`/swagger/index.html`、`/swagger/doc.json`，沿用部署环境的Swagger开关及路由前缀。Java桥接和管理接口带OpenAPI注解，见 `/v3/api-docs` 的“管理后台 - 学生与创意申请”。前端同学只使用Go的三个接口。

## 后端间协议（前端无需实现）

POST `/admin-api/bpm/student-bridge/submit`：externalUserId、applicationId为十进制字符串，另含name、phone、school、creative。Java决定流程key，不允许请求指定任意流程。

POST `/admin-api/bpm/student-bridge/progress`：externalUserId、applicationId。Java联表校验归属，不接受任意processId查询。

请求头：`X-Bridge-Timestamp`为Unix秒，`X-Bridge-Nonce`为16随机字节的小写hex，`X-Bridge-Signature`为HMAC-SHA256的小写hex。

签名原文：`timestamp + "\n" + nonce + "\n" + logicalPath + "\n" + hex(SHA256(body))`。

logicalPath为 `/bpm/student-bridge/submit` 或 `/bpm/student-bridge/progress`，不含/admin-api；body为实际发送的UTF-8 JSON字节。允许300秒时间偏差，Redis消费nonce并保留610秒，重复、篡改、过期或Redis不可用均拒绝。重试用新nonce，但申请ID和内容保持原值。密钥和签名不可发送给浏览器。

## 验证

常规测试不访问生产；显式 `ZZH_WORKFLOW_LIVE_TEST=1` 可执行真实Java联调，会使用标为【测试】的固定学生和申请，请在专用测试环境运行。

- Go：学生归属、并发幂等、冲突内容、签名协议、跳转拒绝、匿名访问和状态查询。
- Java：签名防篡改/过期/重放、学生创建、实际发起人、重复请求、内容冲突、停用用户、越权查询。
- Vben：学生列表/停用与创意申请/审批详情入口连接真实管理接口，无模拟数据。

审批短信沿用官方通知逻辑。当前本地审批人员未配置手机号，短信通知会报手机号缺失；流程和待办已创建。此接入不启用或配置付费短信。

## 分阶段创作

新版详情返回学生当前待办和不可变提交版本，新增阶段提交接口。见[分阶段前端对接](student-application-stages.md)。
