# Server environments

Confirmed by the project owner on 2026-11-04. Connection names are historical and do not indicate the environment.

| FinalShell connection | IP | Actual use | Workflow domain |
|---|---|---|---|
| zhenzhihui-pro | 124.174.95.225 | TEST | https://flow-dev.zhongdahengrui.com |
| zhenzhihui-dev | 115.190.63.215 | PRODUCTION | https://flow.zhongdahengrui.com |

Deploy to PRO/test first. Deploy to DEV/production only after test acceptance and an explicit production-release request. Keep databases, student mappings, integration secrets, Redis, files and domains separate. Never copy the test database over production during a normal release.
