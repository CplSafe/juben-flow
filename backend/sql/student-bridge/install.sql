-- Non-destructive, repeatable migration. Tenant support must remain disabled for this bridge.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_student (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_user_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NULL,
 name VARCHAR(80) NOT NULL, phone VARCHAR(25) NOT NULL, school VARCHAR(200) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_source_student(source,external_user_id), UNIQUE KEY uk_student_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_application (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL, payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 name VARCHAR(80) NOT NULL, phone VARCHAR(25) NOT NULL, school VARCHAR(200) NOT NULL,
 creative MEDIUMTEXT NOT NULL, process_id VARCHAR(128) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_source_application(source,application_id), KEY idx_application_student(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Repair only this module's department if an earlier client imported UTF-8 as latin1.
UPDATE system_dept SET name='校园学生' WHERE deleted=0 AND name=CONVERT(CONVERT(CAST(_utf8mb4'校园学生' AS BINARY) USING latin1) USING utf8mb4);
INSERT INTO system_dept(name,parent_id,sort,status,tenant_id)
 SELECT '校园学生',0,100,0,0 WHERE NOT EXISTS(SELECT 1 FROM system_dept WHERE name='校园学生' AND deleted=0);
-- BPM root IDs differ between official SQL releases; resolve by stable root route.
SET @student_bpm_root = (SELECT id FROM system_menu WHERE path='/bpm' AND parent_id=0 AND deleted=0 ORDER BY id LIMIT 1);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,component,component_name)
 SELECT '学生管理','bpm:student:query',2,80,@student_bpm_root,'students','bpm/student/index','BpmStudents'
 WHERE @student_bpm_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='bpm:student:query' AND deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path)
 SELECT '学生状态管理','bpm:student:update',3,1,id,'' FROM system_menu m WHERE m.permission='bpm:student:query' AND m.deleted=0
 AND NOT EXISTS(SELECT 1 FROM (SELECT permission,deleted FROM system_menu) x WHERE x.permission='bpm:student:update' AND x.deleted=0);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,component,component_name)
 SELECT '创意申请','bpm:student-application:query',2,81,@student_bpm_root,'student-applications','bpm/studentApplication/index','BpmStudentApplications'
 WHERE @student_bpm_root IS NOT NULL AND NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='bpm:student-application:query' AND deleted=0);
-- Also repair a prior installation that used a root ID from another SQL release.
UPDATE system_menu SET parent_id=@student_bpm_root WHERE deleted=0 AND permission IN ('bpm:student:query','bpm:student-application:query') AND @student_bpm_root IS NOT NULL;
UPDATE system_menu SET name=CASE permission WHEN 'bpm:student:query' THEN '学生管理' WHEN 'bpm:student:update' THEN '学生状态管理' WHEN 'bpm:student-application:query' THEN '创意申请' END WHERE deleted=0 AND permission IN ('bpm:student:query','bpm:student:update','bpm:student-application:query');
SELECT @student_bpm_root AS workflow_menu_root_id;
SELECT id AS student_dept_id FROM system_dept WHERE name='校园学生' AND deleted=0;
-- Set yudao.student-bridge.student-dept-id to the returned ID, secret to a random shared secret >=32 bytes.
-- In SIMPLE model start permissions use department mode, add the student department AND existing staff departments,
-- then publish. A nonempty user whitelist takes precedence over the department list in the upstream engine.
