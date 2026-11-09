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


## 2026-11-05 Structured creator intake (PRO / test)

- Business code 686ff9e; workflow code 327b3feb plus frontend download timeout fix 9bbde6bb. Both main branches pushed and pulled on PRO. No production server changes.
- Creator GET/PUT synchronizes actual student identities without launching flows; applications retain profile snapshots. New applications require structured stage data and 1-30 verified document attachments. Legacy UUID replays and plaintext in-flight workflows remain supported.
- Card episode counts are positive integers without prototype range limits. Script paywall_episodes contains concrete distinct episode numbers, not a count. Final originality timestamps are server-generated and renewed on resubmission.
- Additive workflow SQL structured-intake.sql and business migration 202611050002 preserve all existing records. Withdrawal returns an owned active content review to the matching student task, with separate history and unchanged prior versions.
- Reviewer documents require explicit administrative permission or participation in the bound process, plus membership in immutable submission snapshots. Reverse download proxy uses the existing private bridge secret and replay-protected signatures; configure ZZH_STUDENT_BUSINESS_URL for Java to reach the business API. Browser requests never receive that secret or private object keys.
- Release /data/juben-flow/releases/intake-20261105 contains manifest.json, verification.json, go-main.json and reviewer-main.json. Backup /data/juben-flow/backup/intake-20261105-122840 contains workflow SQL, business dump, configuration and previous runtime files.
- Main and isolated acceptance: real S3 document upload, four content stages, 7 immutable versions, 3 withdrawals with retry, 4 content approvals, 4 concurrent stage retries, list/status counts, reviewer download byte equality, attachment membership, original-session enforcement and public Swagger. Nginx configuration, worker and identity service unchanged.
- Deploy only workflow backend/static frontend and business API. Rollback must preserve structured-capable services for records already created; never restore whole databases over later user records or drop additive tables.


## 2026-11-05 Graded card and director assignment (PRO / test)

- Business code 797f4f0 plus summary compatibility fix 1d54954; workflow code 04f08405. Both main repositories pushed and pulled on PRO; DEV production untouched.
- Official SIMPLE version5: CreativeReview -> StudentDraft -> GradedCardReview (A/S required) -> StudentScript (card refinement) -> CardRefinementReview -> EndEvent. Sam/Yuge OR-sign retained. New instances use this definition; legacy definitions and instances stay intact. Version5 completes at script, with final not_applicable and no production tasks.
- A/S decisions are immutable and tied to the exact card version/reviewer. Both new content review nodes validate the actual enabled Sam/Yuge actor and serialize with withdrawal. Terminal refinement requires renewed originality consent and a current card grade.
- Student management includes per-script producer assignment with an enabled internal-staff selector, expectedRevision conflict checking and immutable history. Only bpm:student:assign holders (director role granted to fangdao, plus normal super-admin access) may assign; students and ordinary reviewers cannot assign. Assignment creates no workflow or new author identity.
- Apply graded-card.sql before the new backend starts. SQL grants do not invalidate persistent Redis permission caches: refresh the affected user's role cache and affected menu permission caches, then re-login the director. On this PRO installation only, refreshed user_role_ids:142, menu_role_ids:5013/5015/5016 and permission_menu_ids:bpm:student:assign; do not reuse numeric IDs blindly in another environment and never flush unrelated caches.
- Backup: /data/juben-flow/backup/graded-card-20261105-130151, including full workflow SQL, business dump, runtime config/Jar/frontend and graded-model original model/form snapshots. Release: /data/juben-flow/releases/graded-card-20261105; manifest.json and verification.json record exact runtime hashes and results.
- Acceptance: old four-stage process completed unchanged; Sam S and Yuge A each completed the new process with 5 versions, 3 reviews, 2 withdrawal/re-submit cycles and 4 concurrent retries per stage. Missing/invalid grade rejected, current grade returned to original system, Fangdao assignments and history succeeded, ordinary reviewer and student producer rejected, stale revision rejected. Actual Vben student drawer displays rating/current producer/history. Public Swagger contains grade/assignment metadata; Nginx, worker and identity service unchanged.
- Keep graded-card-capable services while version5 instances/data exist. Do not roll back by restoring entire databases or deleting definitions/history. The external student frontend should use flow_version=5 and current_task rather than assuming a fourth final stage.


## 2026-11-05 Private attachment reader (PRO / test)

- Deploy only workflow Java/static frontend and student-preview. Business API, worker, identity service, MySQL/Redis services and Nginx remain unchanged. DEV production is outside scope. No schema changes.
- Merge deploy/preview.compose.yaml into the workflow compose project and add backend networks [default, student_preview]. Preserve existing student-bridge override, mounts and environment. Converter uses a pinned LibreOffice-only Gotenberg digest, an internal network, no published ports, denied outbound document downloads, 768 MiB / 1 CPU resource limits and a one-item queue. Never point this URL at a public/demo converter.
- ZZH_STUDENT_PREVIEW_URL defaults to http://student-preview:3000. New authenticated file-preview route reuses the original download ACL, immutable version membership and signed reverse proxy. Browser never receives the shared integration secret or private object URL.
- Build frontend with its app build command; scripts/prepare-pdf.mjs copies matching PDF.js CMaps, fonts and WASM from the locked dependency into generated public/pdfjs/<pdfjs-version>/. Worker is served as .js to use existing Nginx JavaScript MIME handling without changing site configuration. Serve those static assets alongside the matching worker bundle. Do not delete previous hashed JS/CSS assets while old browser sessions may reference them.
- Preview limits: TXT/MD 5 MiB, PDF 50 MiB, Office 30 MiB; one active server preview and bounded read deadlines, no stored conversion cache. Original downloads remain supported up to their existing upload limit. PDF canvas renders one bounded page at a time and destroys workers/cancels requests on switch/close.
- Re-run update-card-review-model.py through the existing official publisher: only StudentScript display label changes for existing graded model; archive definitions and running/history rows remain intact. Old instance UI maps the former label for display.
- Release artifacts/verification: /data/juben-flow/releases/preview-20261105; full workflow/config/compose/site baseline backup path is recorded in backup-path. Only labeled synthetic applications are used for preview QA. Rollback workflow Jar/static assets with matching backups; do not restore whole databases over new student records.


## 2026-11-06 Production and TEST fullscreen reader

- Workflow production is live on DEV (115.190.63.215): https://flow.zhongdahengrui.com. PRO remains TEST: https://flow-dev.zhongdahengrui.com.
- Vben application commit b167f540 was built separately on both servers and passed 9 reader tests on each. Java was built on DEV and passed 20 student/workflow tests. TEST Java application code was already current (last backend source change 5f316797), so only its static frontend was replaced.
- At the explicit owner request, the complete TEST workflow snapshot was imported into the new production MySQL database: users/passwords, departments, students, models/forms, tasks, submissions and approval history. Login sessions were invalidated; registration remains disabled. Do not repeat this import over later production records.
- Five linked applications and eleven referenced documents were migrated additively to the business system, matching both student owners to existing production DeepAuth identities. Each document was copied to an independent production object key and SHA-256 verified. Existing profiles, login accounts, passwords and credits were preserved. The business backend repo deploy/workflow-agent-production.md records the integration identity rebinding and rollback details.
- Production live verification includes the original Sam password login, participation-based process detail, denial of reviewer global management access, and fullscreen/continuous scrolling of a migrated 95-page DOCX to page 2. No approval decision or paid generation was made. HTTP redirects to valid HTTPS; other sites remain available.
- Runtime and reports: /data/juben-flow and /data/zzh-releases/production-20261106. The workflow SQL snapshot SHA-256 is 61285410bd1d4fef3a691aa806720fca7a368be13ff31b0e2e994c15e11cee58. All runtime secrets remain outside Git.

## 2026-11-09 Creator classification and optional graduate school (PRO / TEST)

- Workflow Java commit `289b698f6254f923c1cb8aca0b2f52d69c2a485a`, built on TEST; 21 student-related tests passed. Active Jar SHA-256: `e259a864659d8c024c3c93d5f37839bd54aa2515d07ff59fca6adc13ec1fed84`.
- Business API commit `58941357ab0ff279206ecb0e2367b17fca426264`, also built on TEST. Startup applied additive PostgreSQL migration `202611090001` for student_status/creator_type/province; existing city is retained. No workflow SQL migration or model change was needed; JSON profiles/snapshots retain new fields.
- Signed stale-revision live creator requests accepted graduate with empty school and rejected student with empty school; stored profile content/revision were verified unchanged. Five existing process queries and foreign-owner rejection passed. Six identity combinations and an empty-school application were verified against real PostgreSQL inside a rolled-back transaction with a stub engine; no new live process was created for this release.
- Private backup/build/verification directory: `/data/zzh-releases/creator-profile-20261109/`. Old Jar is `workflow-before.jar`. Only workflow backend and business API restarted; worker/auth/databases/converter/frontend/Nginx unchanged. PRODUCTION was not contacted. Do not restore full databases over later records or roll back to required-school workflow validation after accepting real graduate applications.

### 2026-11-09 Existing-profile startup conflict hotfix (TEST only)

- Fix `81b8e4544f57aa62b19b0bbd8b89a4ffd3b41a7d`: canonical JSON profile comparison accepts legacy reader defaults/key ordering at the same revision, while preserving real value conflicts, revision locks and request idempotency. Regression reproduced the exact 409 before the fix; 22 student/notification tests passed locally afterwards.
- Source was fast-forwarded from the pushed Git increment and the changed class compiled on TEST against exact active runtime dependencies, then repacked into the same Boot Jar (one BPM class changed; archive checked). No full server Maven rebuild or dependency change. Active Jar SHA-256 `2e43f6b0195430c2875d0a970fbdea5d868849b1592a3de97a688372ffe2ab36`.
- Actual applications 59/60 recovered under their existing IDs/immutable form snapshots. Original student initiator and Sam/Yuge CreativeReview tasks verified, exactly one engine instance per application after repeated requests. Only their empty-process needs_review business rows were guardedly marked submitted; profile content unchanged. No approvals or paid calls performed.
- Private backup/reproduction/recovery reports: `/data/zzh-releases/creator-start-fix-20261109/`; previous active artifact `rollback.jar`. Only workflow backend restarted; business API, worker, authentication, MySQL/Redis/converter, frontends, Nginx and PRODUCTION untouched. No SQL migration for the fix. Preserve running-process data; never restore entire databases as a runtime rollback.
