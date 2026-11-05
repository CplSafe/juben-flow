SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_card_grade (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, application_ref BIGINT NOT NULL,
 version INT NOT NULL, task_id VARCHAR(128) CHARACTER SET ascii NOT NULL,
 grade VARCHAR(1) CHARACTER SET ascii NOT NULL, reviewer_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_grade_version(application_ref,version), UNIQUE KEY uk_grade_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_production_assignment (
 application_ref BIGINT NOT NULL PRIMARY KEY, producer_user_id BIGINT NOT NULL,
 revision BIGINT NOT NULL, assigned_by BIGINT NOT NULL, remark VARCHAR(500) NOT NULL DEFAULT '',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY idx_assignment_producer(producer_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_production_assignment_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, application_ref BIGINT NOT NULL,
 producer_user_id BIGINT NOT NULL, revision BIGINT NOT NULL, assigned_by BIGINT NOT NULL,
 remark VARCHAR(500) NOT NULL DEFAULT '', created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_assignment_revision(application_ref,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater)
 SELECT '剧本制剧人分配','bpm:student:assign',3,2,id,'',0,1,1,1,'1','1'
 FROM system_menu m WHERE m.permission='bpm:student:query' AND m.deleted=0
 AND NOT EXISTS(SELECT 1 FROM (SELECT permission,deleted FROM system_menu) x WHERE x.permission='bpm:student:assign' AND x.deleted=0);
INSERT INTO system_role(name,code,sort,data_scope,status,type,remark,creator,updater)
 SELECT '剧本分配负责人','script_assignment_director',80,1,0,2,'方导管理学生剧本与制剧人分配','1','1'
 WHERE NOT EXISTS(SELECT 1 FROM system_role WHERE code='script_assignment_director' AND deleted=0);
INSERT INTO system_user_role(user_id,role_id,creator,updater)
 SELECT u.id,r.id,'1','1' FROM system_users u JOIN system_role r ON r.code='script_assignment_director' AND r.deleted=0
 WHERE u.username='fangdao' AND u.deleted=0 AND NOT EXISTS(SELECT 1 FROM system_user_role ur WHERE ur.user_id=u.id AND ur.role_id=r.id AND ur.deleted=0);
INSERT INTO system_role_menu(role_id,menu_id,creator,updater)
 SELECT r.id,m.id,'1','1' FROM system_role r JOIN system_menu m ON m.permission IN ('bpm:student:query','bpm:student-application:query','bpm:student:assign') AND m.deleted=0
 WHERE r.code='script_assignment_director' AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.deleted=0);
