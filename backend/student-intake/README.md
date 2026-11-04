# 学生创意申请接入（本地试运行）

- 申请页：http://localhost:5670/
- 管理记录页：http://localhost:5670/admin（帧智汇超级管理员登录）
- 真实流程：screenwriting_production，SIMPLE 设计器；姓名、电话、学校和创意保存在独立申请记录并写入流程变量。
- 运行副本与 SQLite 数据：~/.local/share/zzh-student-intake/；LaunchAgent：local.zhenzhihui.intake。
- local-secrets.json 为本地集成凭证，已忽略 Git。不要放进前端或提供给学生。

## 外部系统接入

合作系统后端 POST /api/identity/exchange，Authorization: Bearer <INTAKE_CLIENT_SECRET>，JSON：
{"source":"school-system","externalUserId":"stable-user-id","name":"姓名"}
返回 applicationUrl；让用户浏览器跳转该地址。授权码五分钟有效且仅能消费一次，填写会话一小时有效。当前单个集成凭证只供一个可信合作后端使用，source 和 externalUserId 由可信后端确认，不接受学生自行声明身份。

POST /api/session 用 code 换 session；POST /api/apply 带 Bearer session，提交 name、phone、school、creative。同一会话只创建一条申请，防止网络重试重复发起。流程启动结果不明确时标记 needs_review，禁止自动重试，管理员需要核对流程后恢复。

GET /api/applications 及 /api/application/{id} 需要超级管理员 Bearer token；详情包含申请记录、流程实例及审批任务。电话、学校、来源身份不对匿名调用开放。

## 验证与限制

已真实验证身份交换、授权码重放拒绝、申请提交启动流程、重复提交幂等、管理员记录与流程详情。前一轮已验证该 SIMPLE 流程或签和全部节点及最终抄送。

当前为本机试运行接入服务，尚未公网部署。上线前需 HTTPS 反向代理、来源方独立凭证及轮换、持久化数据库备份、限流、监控、个人信息告知与保留期限。此版后台记录页独立运行，尚未嵌入 Vben 菜单；列表最多显示最近200条。审批详情暂以结构化数据展示。
