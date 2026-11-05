SET NAMES utf8mb4;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bpm_external_student' AND COLUMN_NAME='creator_json')=0, 'ALTER TABLE bpm_external_student ADD COLUMN creator_json MEDIUMTEXT NULL', 'SELECT 1'); PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bpm_external_student' AND COLUMN_NAME='creator_revision')=0, 'ALTER TABLE bpm_external_student ADD COLUMN creator_revision BIGINT NOT NULL DEFAULT 0', 'SELECT 1'); PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bpm_external_application' AND COLUMN_NAME='data_json')=0, 'ALTER TABLE bpm_external_application ADD COLUMN data_json MEDIUMTEXT NULL', 'SELECT 1'); PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bpm_external_application' AND COLUMN_NAME='creator_json')=0, 'ALTER TABLE bpm_external_application ADD COLUMN creator_json MEDIUMTEXT NULL', 'SELECT 1'); PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bpm_external_submission' AND COLUMN_NAME='data_json')=0, 'ALTER TABLE bpm_external_submission ADD COLUMN data_json MEDIUMTEXT NULL', 'SELECT 1'); PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
CREATE TABLE IF NOT EXISTS bpm_external_withdrawal (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 application_ref BIGINT NOT NULL,
 request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 task_id VARCHAR(128) CHARACTER SET ascii NOT NULL,
 stage VARCHAR(16) CHARACTER SET ascii NOT NULL,
 version INT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_withdraw_request(application_ref,request_key),
 UNIQUE KEY uk_withdraw_task(application_ref,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
