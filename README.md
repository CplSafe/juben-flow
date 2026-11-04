# 帧智汇 · 剧本工作流

源码快照，包含本地使用中的 Yudao 后端、Vben 前端及学生创意申请接入服务。

## 目录

- `backend/`：ruoyi-vue-pro Java 后端，启用工作流、AI、即时通讯，关闭多租户。
- `frontend/`：Vben monorepo，实际使用 `apps/web-antd`。
- `backend/student-intake/`：学生姓名、电话、学校、创意申请页和外部系统身份接入。
- `backend/student-intake/INTEGRATION.md`：合作系统前后端对接文档。

## 本地启动

后端按 `backend/README.md` 配置 Java、Maven、MySQL、Redis。配置中的凭证已脱敏，部署时通过私有配置或环境变量填写；`${CONFIGURE_SECRET}`、`CONFIGURE_TEST_VALUE` 和 `REPLACE_WITH_TEST_API_KEY` 均为待配置占位符，不是可用凭证；上游测试/示例中的硬编码密钥亦已替换。

前端使用 Node22 和 pnpm11，在 frontend 下执行 `pnpm install`，随后 `pnpm -F @vben/web-antd run dev`。

学生接入服务为标准库 Python 服务，配置 `INTAKE_USER`、`INTAKE_PASSWORD`、`INTAKE_CLIENT_SECRET` 后运行 `backend/student-intake/server.py`。公网部署需另配域名、HTTPS、服务管理和备份，不能直接依赖本机临时隧道。

## 数据与保密范围

此仓库不包含任何本地 MySQL 导出、SQLite 申请数据、账号密码清单、授权码或隧道配置。不包含 SQL 数据文件、打包产物和私钥容器。部门、人员、动态表单和 SIMPLE 模型存储在数据库中，不能仅通过本仓库恢复，部署时需单独安全迁移数据库。外部身份演示链接不是正式登录入口。

保留各上游 LICENSE 与说明文件；项目基于 YunaiV/ruoyi-vue-pro 和 yudaocode/yudao-ui-admin-vben，相关许可分别见 backend/LICENSE、frontend/LICENSE。
