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
