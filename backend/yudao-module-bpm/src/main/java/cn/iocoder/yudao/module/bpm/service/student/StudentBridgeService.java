package cn.iocoder.yudao.module.bpm.service.student;

import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class StudentBridgeService {
    @Resource private JdbcTemplate jdbc;
    @Resource private AdminUserService users;
    @Resource private BpmProcessInstanceService processes;
    @Resource private StringRedisTemplate redis;
    @Resource private ObjectMapper mapper;
    @Value("${yudao.student-bridge.secret:${ZZH_WORKFLOW_BRIDGE_SECRET:}}") private String secret;
    @Value("${yudao.student-bridge.student-dept-id:${ZZH_WORKFLOW_STUDENT_DEPT_ID:0}}") private long deptId;
    @Value("${yudao.student-bridge.process-key:screenwriting_production}") private String processKey;
    private static final String SOURCE = "zhenzhihui";

    public record Request(String externalUserId, String applicationId, String name, String phone,
                          String school, String creative) { }

    public void authenticate(String path, String body, String timestamp, String nonce, String signature) {
        try {
            if (secret.length() < 32 || body.getBytes(StandardCharsets.UTF_8).length > 65536
                    || nonce == null || !nonce.matches("[a-f0-9]{32}") || signature == null
                    || !signature.matches("[a-f0-9]{64}") || timestamp == null
                    || !timestamp.matches("[0-9]{10}")) throw new IllegalArgumentException();
            long t = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - t) > 300) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = timestamp + "\n" + nonce + "\n" + path + "\n" + hash(body);
            if (!MessageDigest.isEqual(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(signature))) throw new IllegalArgumentException();
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("bpm:student-bridge:nonce:" + nonce,
                    "1", Duration.ofSeconds(610)))) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bridge signature");
        }
    }

    public Request parse(String body, boolean submission) {
        try {
            Request r = mapper.readValue(body, Request.class);
            if (r.externalUserId() == null || !r.externalUserId().matches("[1-9][0-9]{0,18}")
                    || r.applicationId() == null || !r.applicationId().matches("[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException();
            if (submission && (!valid(r.name(), 80) || !valid(r.school(), 200) || !valid(r.creative(), 12000)
                    || r.phone() == null || !r.phone().matches("[+0-9() -]{7,25}")))
                throw new IllegalArgumentException();
            return r;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid student application");
        }
    }
    private static boolean valid(String s, int max) {
        return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= max;
    }
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Request r) {
        if (deptId <= 0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Student department not configured");
        // A durable row lock serializes concurrent first submissions for the same external account.
        jdbc.update("INSERT INTO bpm_external_student(source, external_user_id, name, phone, school) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                SOURCE, r.externalUserId(), r.name(), r.phone(), r.school());
        Map<String,Object> student = jdbc.queryForMap("SELECT * FROM bpm_external_student WHERE source=? AND external_user_id=? FOR UPDATE", SOURCE, r.externalUserId());
        // Do not establish a repeatable-read snapshot before the identity lock: concurrent
        // first submissions must see the account committed by the preceding lock holder.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM system_dept WHERE id=? AND deleted=0 AND status=0", Integer.class, deptId) != 1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Student department unavailable");
        Long userId = student.get("user_id") == null ? null : ((Number) student.get("user_id")).longValue();
        if (userId == null) {
            String nickname = r.name().substring(0, r.name().offsetByCodePoints(0, Math.min(30, r.name().codePointCount(0,r.name().length()))));
            userId = users.createExternalStudent(new UserSaveReqVO().setUsername("zst" + hash(SOURCE + ":" + r.externalUserId()).substring(0,24))
                    .setNickname(nickname).setDeptId(deptId).setPassword(randomPassword())
                    .setRemark("外部学生同步；无后台角色；来源 " + SOURCE));
            jdbc.update("UPDATE bpm_external_student SET user_id=? WHERE id=?", userId, student.get("id"));
        }
        var user = users.getUser(userId);
        if (user == null || !Integer.valueOf(0).equals(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student account disabled");
        String payload;
        try { payload = hash(mapper.writeValueAsString(r)); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        jdbc.update("INSERT INTO bpm_external_application(source, application_id, user_id, payload_hash, name, phone, school, creative) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                SOURCE,r.applicationId(),userId,payload,r.name(),r.phone(),r.school(),r.creative());
        Map<String,Object> application = jdbc.queryForMap("SELECT * FROM bpm_external_application WHERE source=? AND application_id=? FOR UPDATE", SOURCE,r.applicationId());
        if (!payload.equals(application.get("payload_hash")) || userId.longValue() != ((Number)application.get("user_id")).longValue())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Application identity or content changed");
        String processId = (String)application.get("process_id");
        if (processId == null) {
            Map<String,Object> vars = new HashMap<>();
            vars.put("applicationId", r.applicationId()); vars.put("externalSource",SOURCE);
            vars.put("externalUserId",r.externalUserId()); vars.put("studentName",r.name());
            vars.put("studentPhone",r.phone()); vars.put("studentSchool",r.school());
            vars.put("projectName",r.school() + " · " + r.name() + "的创意申请"); vars.put("creative",r.creative());
            processId = processes.createProcessInstance(userId,new BpmProcessInstanceCreateReqDTO().setProcessDefinitionKey(processKey)
                    .setBusinessKey(SOURCE + ":student:" + r.applicationId()).setVariables(vars));
            jdbc.update("UPDATE bpm_external_application SET process_id=? WHERE id=?",processId,application.get("id"));
            jdbc.update("UPDATE bpm_external_student SET name=?,phone=?,school=? WHERE id=?",r.name(),r.phone(),r.school(),student.get("id"));
        }
        return Map.of("processId",processId,"workflowUserId",userId);
    }

    public Map<String,Object> progress(Request r) {
        var rows = jdbc.queryForList("SELECT a.process_id FROM bpm_external_application a JOIN bpm_external_student s ON a.source=s.source AND a.user_id=s.user_id WHERE a.source=? AND a.application_id=? AND s.external_user_id=?",SOURCE,r.applicationId(),r.externalUserId());
        if (rows.isEmpty() || rows.get(0).get("process_id") == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var instance = processes.getHistoricProcessInstance((String)rows.get(0).get("process_id"));
        if (instance == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String,Object> result = new HashMap<>();
        result.put("status",instance.getProcessVariables().get("PROCESS_STATUS"));
        result.put("name",instance.getName());
        result.put("end_time",instance.getEndTime() == null ? null : instance.getEndTime().getTime());
        return result;
    }
    public List<Map<String,Object>> students(int offset) {
        return jdbc.queryForList("SELECT s.*,u.status FROM bpm_external_student s JOIN system_users u ON s.user_id=u.id WHERE u.deleted=0 ORDER BY s.id DESC LIMIT 50 OFFSET ?", Math.max(0,offset));
    }
    public List<Map<String,Object>> applications(int offset) {
        return jdbc.queryForList("SELECT id,source,application_id,user_id,name,phone,school,creative,process_id,created_at FROM bpm_external_application ORDER BY id DESC LIMIT 50 OFFSET ?",Math.max(0,offset));
    }
    public void setStudentStatus(long id, int status) {
        if (status != 0 && status != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = jdbc.queryForList("SELECT user_id FROM bpm_external_student WHERE id=?",id);
        if (rows.isEmpty() || rows.get(0).get("user_id") == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        users.updateUserStatus(((Number)rows.get(0).get("user_id")).longValue(),status);
    }
}
