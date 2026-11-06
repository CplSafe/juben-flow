# Deployment environments

Owner-confirmed on 2026-11-04; names are historical.

- `zhenzhihui-pro` (`124.174.95.225`) is TEST. Workflow: https://flow-dev.zhongdahengrui.com.
- `zhenzhihui-dev` (`115.190.63.215`) is PRODUCTION. Workflow: https://flow.zhongdahengrui.com.
- Release to TEST first. Do not connect to or deploy PRODUCTION until test acceptance and an explicit production-release request.
- Preserve other sites, Nginx, databases and secrets. See deploy/ENVIRONMENTS.md and deploy/SERVER.md for the verified release and rollback details.
