-- Keep normal admin-created staff accounts and signed student provisioning.
-- This is the official system-module self-registration switch, checked server-side.
SET NAMES utf8mb4;
UPDATE infra_config SET value='false' WHERE config_key='system.user.register-enabled' AND deleted=0;
INSERT INTO infra_config(category,type,name,config_key,value,visible,remark)
 SELECT '用户管理',1,'用户管理-注册开关','system.user.register-enabled','false',b'0','后台人员仅由管理员新增，外部学生使用签名接入同步'
 WHERE NOT EXISTS(SELECT 1 FROM infra_config WHERE config_key='system.user.register-enabled' AND deleted=0);
SELECT config_key,value FROM infra_config WHERE config_key='system.user.register-enabled' AND deleted=0;
