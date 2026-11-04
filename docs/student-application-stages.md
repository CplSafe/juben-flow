# 学生分阶段创作：前端对接与发布说明

沿用原系统登录，学生页面只调用 zhenzhihui Go API。学生不登录工作流后台，不持有集成密钥，也不能自主注册后台账号。

## 流程

创意提交 → 创意审核（Sam/宇哥任一人）→ 学生填写一卡（剧本初稿）→ 一卡初稿审核（任一人）→ 学生完善同一份剧本 → 完善剧本审核（任一人）→ 原导演审定、分镜、制作、剪辑、发行、抄送。

SIMPLE 中新增 StudentDraft 和 StudentRevision 两个办理节点，候选人策略“发起人自己”，不自动跳过。之后的审批人员保持原配置。只在真正轮到学生办理时才能提交；完成填写会先归档内容，再办理该学生自己的任务，二者在 Java/Flowable 的同一事务里提交或回滚。

新发起的申请使用新版定义。旧版本在途实例保持原定义，不强行迁移或丢失旧记录。驳回仍沿用现有模型的结束/退回配置，不将“审批不通过”伪装成下一阶段。

## 1. 首次提交创意

沿用 POST `/api/v1/student-applications`，请求仍为姓名、电话、学校、creative，带登录凭证和 X-Idempotency-Key。详情见 [基础接口](student-applications-integration.md)。姓名等身份字段的填写不等于实名验证；账号归属只取已登录用户。

## 2. 读取申请详情、当前阶段、上一稿和历史

GET `/api/v1/student-applications/{id}`。仅当前学生本人可访问。

```json
{
  "code": 0,
  "data": {
    "application": {"id": 123, "state": "submitted", "creative": "创意内容"},
    "progress_available": true,
    "workflow": {
      "status": 1,
      "name": "编剧创作与制作流程",
      "stage": "revision",
      "current_task": {
        "task_id": "当前学生待办ID",
        "stage": "revision",
        "name": "学生完善剧本",
        "content": "上一稿剧本内容，供本次完善"
      },
      "versions": [
        {"version": 1, "stage": "creative", "content": "创意内容", "task_id": "", "submitted_at": 1793760000000},
        {"version": 2, "stage": "draft", "content": "一卡初稿内容", "task_id": "已完成的初稿待办ID", "submitted_at": 1793760100000}
      ],
      "reviews": [
        {"task_id": "审批任务ID", "node_key": "OutlineReview", "name": "一卡初稿审核：Sam / 宇哥", "status": 2, "reason": "请补充人物对白", "version": 2, "finished_at": 1793760200000}
      ]
    }
  }
}
```

| stage | 页面行为 |
|---|---|
| waiting_review | 展示审核中/后续制作中，关闭学生提交按钮 |
| draft | 展示一卡初稿编辑器；首次 content 为空 |
| revision | 展示完善剧本编辑器，使用 current_task.content 带出上一稿 |
| finished | 展示流程最终状态及归档记录，不允许继续提交 |

只在 `current_task` 非空时开放提交；任务ID必须来自本次详情响应，不自行拼装或复用前一节点的ID。学生停用或任务挂起时不开放待办。workflow.status 沿用1进行中、2通过、3不通过、4取消；stage不代替最终审批结果。

`progress_available=false` 时仍可展示已保存的申请，提示暂时无法获取进度并稍后重试，不展示可提交表单。列表的 application.state 仍表示接入状态，不能当成审批状态。

versions 按提交顺序返回，v1是首次创意快照，v2是一卡初稿，v3是完善稿；若管理员配置退回并再次提交，继续递增，不覆盖旧版本。所有时间为 Unix 毫秒。reviews 返回三轮内容审核中已完成的通过/不通过/退回记录（2/3/5），并关联其审核的提交版本；或签中自动取消的另一位审核任务不显示为额外意见。不返回审核人员手机号、邮箱等个人资料。完整后续制作办理和审批轨迹在工作流后台原流程详情中查看。

## 3. 提交当前阶段

POST `/api/v1/student-applications/{id}/stages`

请求头：原登录凭证、Content-Type: application/json、`X-Idempotency-Key: <本次提交唯一编号>`。

```json
{"task_id":"详情返回的当前学生待办ID","stage":"revision","content":"基于上一稿完善后的完整剧本"}
```

stage仅允许draft或revision，content去除首尾空白后必填，最多20000个Unicode字符。提交完整稿件，不是文本差异。任务ID、阶段、申请归属和实际办理人由服务端再次校验；即使修改前端也不能跳阶段、提交他人的待办或代替审核人员审批。学生只能改本阶段正文，不能改流程变量、审批人员或初始身份信息。

成功和相同请求重放都返回HTTP200：

```json
{"code":0,"data":{"version":3,"stage":"revision","content":"本次完善后的完整剧本","task_id":"本次已办理的学生待办ID","submitted_at":1793760300000}}
```

收到成功后重新GET详情，通常进入waiting_review。不得自动把初稿继续作为完善稿提交；下一次轮到学生时，应让学生继续编辑并主动提交。

| HTTP | 含义与处理 |
|---|---|
| 400 | 内容、阶段、任务ID或提交编号无效，提示修正 |
| 401 | 原系统未登录，沿用现有登录流程 |
| 403 | 学生工作流账号停用/禁止提交，联系管理员 |
| 404 | 申请不存在或不属于当前学生 |
| 409 | 阶段/任务已变化，或同一提交编号用于不同内容，刷新详情 |
| 503 | 工作流不可用或响应丢失，保留原编号和原正文重试 |

每次主动提交生成一个UUID，同一申请同一待办的网络重试复用同一个编号、任务ID和正文；不要在重试时生成新编号，也不要用同一编号发送修改后的稿件。幂等范围为同一申请。即使审批已经进入下一节点，重放已成功的原请求仍返回原版本，不创建新版本、不再次推进。并发点击也只产生一个提交版本。

```js
// 页面加载或轮询时：只按后端返回的 current_task 开放编辑。
const data = await api.get(`/api/v1/student-applications/${applicationId}`);
const task = data.workflow?.current_task;
const form = { content: task?.content ?? '' };
// 开始本次主动提交时生成，并持久化到本次提交状态；网络重试不改变。
const submission = {
  key: crypto.randomUUID(),
  body: { task_id: task.task_id, stage: task.stage, content: form.content },
};
await api.post(`/api/v1/student-applications/${applicationId}/stages`, submission.body, {
  headers: { 'X-Idempotency-Key': submission.key },
});
// 成功后重新读取详情；直到下次出现 current_task 才显示新的编辑入口。
```

使用你们原有请求封装解包统一响应。上例仅说明调用顺序，必须先判断progress_available、current_task、登录/错误状态。编辑中草稿不等于正式提交；本版服务器在主动提交时归档版本，未提供自动保存草稿接口。

## 后台

工作流程 → 创意申请 → 查看申请：原创意快照、当前阶段、全部提交版本、对应审核意见；可继续进入完整审批记录/流程图。查看权限沿用 `bpm:student-application:query`，不会自动授给学生。审批表单保留初稿scriptDraft及当前稿script，并将学生提交字段设为只读；制作成果等后续办理字段仍沿用原配置。

## 后端部署与接口

1. 备份 Java/业务数据库、SIMPLE模型和表单。执行工作流仓库 `backend/sql/student-bridge/staged-submissions.sql`，新增不可变提交表，不删除旧数据。
2. 更新 Java 后端和后台页面，Go后端仍使用原共享密钥。Java HMAC请求体上限改为256KiB，stage-submit只接受固定阶段正文和实际任务ID。
3. 调用官方SIMPLE API发布新版：`backend/sql/student-bridge/update-staged-model.py --base-url <.../admin-api> --model-id <当前ID> --backup-dir <私有新目录> --apply`。管理员凭证仅通过WORKFLOW_ADMIN_USER/PASSWORD环境变量提供，脚本先保存模型/表单快照。默认不带--apply只预览；已存在两个正确学生节点时不重复发布。
4. Go `POST /stages` 转发签名 `POST /admin-api/bpm/student-bridge/stage-submit`，身份取已登录用户，applicationId取Go申请ID，requestKey取幂等头。Java按来源+用户+申请核对归属，版本与Flowable办理在同一事务里完成。Java后台详情为GET `/admin-api/bpm/student-bridge/application-detail?id=<Java申请记录ID>`，学生前端不调用该管理接口。
5. 没有后台手机号的学生/工作人员跳过短信通知，避免通知缺失阻断审批完成；已有后台手机号仍沿用原通知逻辑。不将学生自填电话当成已绑定的后台手机号。
6. PRO为测试服务器；DEV为生产服务器，本批不触碰DEV。备份、源码及验收结果记录见各仓库deploy说明。

Go Swagger增加StageInput、StudentTask、SubmissionVersion、Review及阶段提交路由。Java OpenAPI包含stage-submit和后台版本详情。

## 验证

- Go：阶段参数校验、本人归属、越权、已登录HTTP提交、远端状态码、race及全量make verify。
- Java：当前学生待办/阶段/挂起/停用校验、相同请求重放、内容冲突、归档与本人办理；无手机号通知回归。
- SIMPLE转换单元测试：两个学生节点保留，发起人自己策略36，不自动跳过，原审核或签和后续节点不变。
- test_staged_live.py：显式指定localhost或PRO测试域名、私有密钥/人员凭证；验证两轮学生提交、八路并发重放、上一稿带出、三个不可变版本及对应审核记录，再走完导演至发行的原后续节点。仅创建带【测试】标记的合成申请，异常时清理本次流程。
