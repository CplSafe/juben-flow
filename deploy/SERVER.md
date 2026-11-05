# flow-dev 部署记录

2026-11-04 已部署至测试服务器 zhenzhihui-pro（124.174.95.225）。PRO 是测试环境；DEV 才是生产环境，详见 [ENVIRONMENTS.md](ENVIRONMENTS.md)。

- 后台：https://flow-dev.zhongdahengrui.com
- 应用目录：/data/juben-flow
- Nginx 独立站点：/etc/nginx/conf.d/flow-dev.conf
- 后端：127.0.0.1:48081 → Docker 48080；数据库和 Redis 不发布宿主端口。
- Docker Compose 项目：juben-flow，mysql:8.0、redis:7-alpine、eclipse-temurin:21-jre。
- Java 启用 local profile 和 /app/config 覆盖配置；关闭租户与 mock 登录。
- 敏感配置：服务器 .env、config/application-local.yaml，权限600，不提交Git。
- 数据备份：/data/juben-flow/backup/local-migration-20261104.sql.gz，权限600。
- MySQL及Redis使用独立命名卷，容器 unless-stopped 自动恢复。
- HTTPS由Certbot webroot签发，certbot.timer开启；续期部署hook测试后平滑重载Nginx。
- 所有来源跨域按用户要求启用；接口仍执行认证授权。
- 学生独立申请页未部署。

## 运维

在服务器 /data/juben-flow 执行 docker compose ps、docker compose logs --tail 100 backend 查看状态。操作只使用此Compose项目，避免影响现有 deploy-api/deploy-worker/deepauth。

首次部署导入本地 ruoyi-vue-pro 全量数据库，包含流程定义、人员部门、表单与审批历史。恢复或升级前必须先单独备份数据库，禁止直接重复覆盖生产数据库。

已验证公网首页200、管理员登录、SIMPLE模型列表、跨域OPTIONS、现有站点200和原容器持续运行。剩余磁盘约4GB，后续升级/备份前需扩容或经确认处理构建缓存。实际镜像/私有配置需另备份，Git源码快照本身不包含数据库和可用密钥。

## 源码发布约定

主仓库：CplSafe/juben-flow，分支main。本地修改并推送后，服务器源码位于/data/juben-flow/source，使用git pull --ff-only更新。私有.env、运行配置、数据库卷和迁移备份均在源码目录之外，不允许Git覆盖。

当前前端修复已通过本地生产构建并部署，源码同步至仓库。由于服务器磁盘剩余不足4GB，本轮不在生产机安装整套monorepo依赖，以免占满磁盘。后续使用CI或本地从对应提交构建并记录提交SHA，上传产物后验证再切换；若需要服务器现场构建，请先扩容。仅git pull不会自动更新正在运行的jar或静态文件。


## 2026-11-04 学生本人发起接入（PRO 测试环境）

- 工作流功能构建源码：c1a41f98；SQL 兼容修复：ca13d717。Go 业务接口版本：c7eb603。
- 服务器分别从两个仓库 main 执行 git pull --ff-only；本地从对应源码构建并上传校验过的产物。
- 两端共享密钥仅存在服务器私有配置；Java 从 config/student-bridge.env 注入，Go 沿用业务 .env。业务后端通过本机 127.0.0.1:48081/admin-api 调用工作流，不使用服务账号或 Python。
- 已导入学生映射和申请表，校园学生部门 ID=121。模型及当前发布定义的发起部门为114、115、116、121，指定用户名单已清空；流程图和审批人员不变。
- 服务器此版官方 SQL 的工作流根菜单 ID=1185，与旧版148不同；安装脚本按 /bpm 根路由匹配，并处理 UTF-8/旧客户端乱码修复，重复执行两次验证无重复部门和菜单。
- 新菜单：工作流程 → 学生管理、创意申请。已在公网浏览器登录验证列表和页面加载。
- Go 实际运行环境完成申请保存、本人发起、8次重复提交、冲突内容、本人查询与越权拒绝测试；Java验证发起人是对应学生且无后台角色，Sam单人通过后进入下一节点。合成测试申请已撤销，保留审批记录。
- 验证签名篡改拒绝、学生启用/停用、匿名管理拒绝、业务接口不接受开发身份头、工作流跨域、Logo、验证码关闭、默认进入工作流。
- 发布前先启动备用端口实例验证，再只替换工作流前后端和业务 API。临时实例已清理；worker、统一登录和 Nginx 没有重启或修改，原有三个站点验收均为200。
- 备份目录：/data/juben-flow/backup/student-release-20261104-152153，含两个数据库、旧Jar、旧前端和私有运行配置。业务库是 PostgreSQL17，备份使用 postgres:17-alpine 的 pg_dump，而非宿主机16版客户端。
- 发布清单及验收结果：/data/juben-flow/releases/student-c1a41f98/manifest.json、verification.json、final-check.json（仅在服务器，不提交数据库或密钥）。
- 回退：恢复备份中的app.jar和frontend、Java compose配置、业务.env；将zhenzhihui-backend:before-student-c7eb603重新标记为dev，只重建对应backend/api服务。新增表可保留；常规回退不要覆盖数据库或清除已有业务数据。
- DEV生产服务器本次未连接、未部署。后续经测试验收并收到生产发布指令后，再独立部署到 flow.zhongdahengrui.com，使用独立数据库和密钥。

## 后台自主注册关闭

执行 backend/sql/student-bridge/disable-self-registration.sql，使用官方 system.user.register-enabled=false 开关在服务端拒绝自主注册。登录页隐藏创建账号，直接打开/auth/register跳回登录页。管理员新增人员和签名学生同步继续保留；不关闭学生业务系统的登录或申请入口。

## 2026-11-04 Student script stages (PRO / test)

- Runtime source: workflow 6774077c; business API 0a8f6a5. Both main branches are pushed and pulled on PRO. Artifact hashes: /data/juben-flow/releases/stages-6774077c/manifest.json.
- Additive table bpm_external_submission archives immutable stage submissions with unique application/version, application/request key and application/task constraints. Creative stays v1, first script v2, refined script v3.
- Official SIMPLE publication inserts starter-owned StudentDraft and StudentRevision tasks between the original three reviews. Sam/Yuge remain OR-sign; later staff remain unchanged. Existing instances keep their original definition.
- Business Swagger and frontend guide: docs/features/student-application-stages.md in the business repository. The original-system frontend must consume current_task and the stage submission API; this release supplies APIs and workflow administration, not a replacement student frontend.
- Full pre-release database/runtime backup: /data/juben-flow/backup/student-release-20261104-165813. Model/form snapshots: staged-model within that directory.
- Rollback API by retagging zhenzhihui-backend:before-stages-0a8f6a5 and recreating only api. Restore backed-up Jar/frontend and recreate only backend if needed. Keep stage-capable services for instances already started on the new definition; do not drop definitions or restore entire databases over live records.
- DEV production was not contacted. Nginx, other sites, worker and identity service are outside the replacement scope.

## 2026-11-05 Four content phases (PRO / test)

- Official SIMPLE upgrade: creative review, student card, card review, student script, script review, student refinement, final review, then the original production tasks. Rejected content returns to its own student task; creative returns to StartUserNode. Staff and OR-sign remain unchanged.
- Existing instances retain their original definition and legacy draft/revision task keys. New content-stage summaries distinguish under_review, needs_changes, awaiting_submission and approved; production completion remains a separate engine status.
- Signed summaries accept at most 100 owned application IDs, use bulk engine queries and expose no form bodies or staff contact data. Business list filters/counts are computed before pagination; status counts ignore the status tab but follow other filters.
- Additive business migration adds optional title/genre; immutable submission table is reused without destructive updates. Original frontend structured profiles, attachments and withdrawal remain separate capabilities.
- Full database/config backup: /data/juben-flow/backup/student-release-20261105-105003. Model/form snapshots: four-stage-model. Release artifacts and reports: /data/juben-flow/releases/four-stage-20261105.
- Canary acceptance passed all four return/resubmit cycles (8 immutable versions/reviews), concurrent retries, scoped summaries, real business-runtime filters/counts and full production completion. Main runtime verification is recorded in the release directory after switching.
- Replace only workflow backend/static frontend and business api. Preserve worker, identity service and Nginx. Never remove the stage-capable backend while its new-definition instances remain live; do not restore full databases over new records.
