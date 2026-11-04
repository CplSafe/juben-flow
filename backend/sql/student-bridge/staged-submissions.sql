SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS bpm_external_submission (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 application_ref BIGINT NOT NULL,
 version INT NOT NULL,
 stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 content MEDIUMTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_application_version(application_ref,version),
 UNIQUE KEY uk_application_request(application_ref,request_key),
 UNIQUE KEY uk_application_task(application_ref,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
