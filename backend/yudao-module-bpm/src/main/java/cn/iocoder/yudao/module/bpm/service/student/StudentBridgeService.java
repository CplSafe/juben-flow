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
    @Resource private StudentStageService stages;
    @Value("${yudao.student-bridge.secret:${ZZH_WORKFLOW_BRIDGE_SECRET:}}") private String secret;
    @Value("${yudao.student-bridge.student-dept-id:${ZZH_WORKFLOW_STUDENT_DEPT_ID:0}}") private long deptId;
    @Value("${yudao.student-bridge.process-key:screenwriting_production}") private String processKey;
    private static final String SOURCE = "zhenzhihui";

    public record Request(String externalUserId, String applicationId, String name, String phone,
                          String school, String creative,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String title,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.fasterxml.jackson.databind.JsonNode data,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.fasterxml.jackson.databind.JsonNode creator) {
        public Request(String externalUserId,String applicationId,String name,String phone,String school,String creative,String title) {
            this(externalUserId,applicationId,name,phone,school,creative,title,null,null);
        }
        public Request(String externalUserId,String applicationId,String name,String phone,String school,String creative) {
            this(externalUserId,applicationId,name,phone,school,creative,null,null,null);
        }
    }

    public void authenticate(String path, String body, String timestamp, String nonce, String signature) {
        try {
            if (secret.length() < 32 || body.getBytes(StandardCharsets.UTF_8).length > 262144
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
            if (submission && (!valid(r.name(), 80) || !validSchool(r.school(), r.creator()) || !valid(r.creative(), 12000)
                    || r.phone() == null || !r.phone().matches("[+0-9() -]{7,25}")))
                throw new IllegalArgumentException();
            if (r.title()!=null && r.title().codePointCount(0,r.title().length())>200) throw new IllegalArgumentException();
            if (r.data()!=null && !r.data().isNull() && (!r.data().isObject() || r.data().toString().getBytes(StandardCharsets.UTF_8).length>131072)) throw new IllegalArgumentException();
            if (r.creator()!=null && !r.creator().isNull() && (!r.creator().isObject() || r.creator().toString().length()>65536)) throw new IllegalArgumentException();
            return r;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid student application");
        }
    }
    // Only authenticated bridge payloads with a graduate profile may omit school/company.
    private static boolean validSchool(String school, com.fasterxml.jackson.databind.JsonNode profile) {
        boolean graduated = profile != null && "graduated".equals(profile.path("student_status").asText());
        return school != null && school.codePointCount(0, school.length()) <= 200
                && (graduated || !school.isBlank());
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

    private Long ensureIdentity(Request r) {
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
        return userId;
    }

    public record CreatorRequest(String externalUserId, long revision, com.fasterxml.jackson.databind.JsonNode profile) { }
    public CreatorRequest parseCreator(String body) {
        try {
            var r=mapper.readValue(body,CreatorRequest.class);var p=r.profile();
            if (r.externalUserId()==null || !r.externalUserId().matches("[1-9][0-9]{0,18}") || r.revision()<1 || p==null || !p.isObject()
                    || body.getBytes(StandardCharsets.UTF_8).length>65536 || !valid(p.path("real_name").asText(),80)
                    || !validSchool(p.path("school").asText(), p) || !p.path("phone").asText().matches("[+0-9() -]{7,25}")) throw new IllegalArgumentException();
            return r;
        } catch(Exception e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid creator profile");}
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> syncCreator(CreatorRequest r) {
        if (deptId<=0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Student department not configured");
        var p=r.profile();Long userId=ensureIdentity(new Request(r.externalUserId(),"1",p.path("real_name").asText(),p.path("phone").asText(),p.path("school").asText(),"profile"));
        var row=jdbc.queryForMap("SELECT * FROM bpm_external_student WHERE source=? AND external_user_id=? FOR UPDATE",SOURCE,r.externalUserId());
        long current=((Number)row.getOrDefault("creator_revision",0L)).longValue();
        var canonical=p.deepCopy();if(canonical.isObject()){((com.fasterxml.jackson.databind.node.ObjectNode)canonical).remove("workflow_sync_state");((com.fasterxml.jackson.databind.node.ObjectNode)canonical).remove("updated_at");}
        String json=canonical.toString();
        if (r.revision()==current && !Objects.equals(row.get("creator_json"),json)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Profile revision conflict");
        if (r.revision()>current) {
            jdbc.update("UPDATE bpm_external_student SET creator_revision=?,creator_json=?,name=?,phone=?,school=? WHERE id=?",r.revision(),json,p.path("real_name").asText(),p.path("phone").asText(),p.path("school").asText(),row.get("id"));
            String nickname=p.path("real_name").asText();nickname=nickname.substring(0,nickname.offsetByCodePoints(0,Math.min(30,nickname.codePointCount(0,nickname.length()))));
            jdbc.update("UPDATE system_users SET nickname=? WHERE id=? AND deleted=0",nickname,userId);
        }
        return Map.of("workflowUserId",userId,"revision",Math.max(current,r.revision()));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Request r) {
        if (deptId <= 0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Student department not configured");
        Long userId=ensureIdentity(r);
        if(r.creator()!=null&&!r.creator().isNull()) {
            if(!r.externalUserId().equals(r.creator().path("user_id").asText()) || r.creator().path("revision").asLong()<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid creator snapshot");
            syncCreator(new CreatorRequest(r.externalUserId(),r.creator().path("revision").asLong(),r.creator()));
        }
        String payload;
        try { payload = hash(mapper.writeValueAsString(r)); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        jdbc.update("INSERT INTO bpm_external_application(source, application_id, user_id, payload_hash, name, phone, school, creative) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                SOURCE,r.applicationId(),userId,payload,r.name(),r.phone(),r.school(),r.creative());
        Map<String,Object> application = jdbc.queryForMap("SELECT * FROM bpm_external_application WHERE source=? AND application_id=? FOR UPDATE", SOURCE,r.applicationId());
        if (!payload.equals(application.get("payload_hash")) || userId.longValue() != ((Number)application.get("user_id")).longValue())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Application identity or content changed");
        String processId = (String)application.get("process_id");
        if (processId==null && (r.data()==null || r.data().isNull() || r.creator()==null || r.creator().isNull())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"New applications require creator profile and structured data");
        if(processId==null) StudentStageService.validateStructuredSnapshot(r.data());
        if (processId==null) jdbc.update("UPDATE bpm_external_application SET data_json=?,creator_json=? WHERE id=?",r.data()==null||r.data().isNull()?null:r.data().toString(),r.creator()==null||r.creator().isNull()?null:r.creator().toString(),application.get("id"));
        if (processId == null) {
            Map<String,Object> vars = new HashMap<>();
            vars.put("applicationId", r.applicationId()); vars.put("externalSource",SOURCE);
            vars.put("externalUserId",r.externalUserId()); vars.put("studentName",r.name());
            vars.put("studentPhone",r.phone()); vars.put("studentSchool",r.school());
            if(r.data()!=null && !r.data().isNull()) vars.put("studentForm",r.data().toString());
            if(r.title()!=null&&!r.title().isBlank())vars.put("studentTitle",r.title());
            if(r.data()!=null&&r.data().isObject()&&r.data().hasNonNull("genre"))vars.put("studentGenre",r.data().path("genre").asText());
            vars.put("projectName",r.title()!=null && !r.title().isBlank()?r.title():r.school() + " · " + r.name() + "的创意申请"); vars.put("creative",r.creative());
            processId = processes.createProcessInstance(userId,new BpmProcessInstanceCreateReqDTO().setProcessDefinitionKey(processKey)
                    .setBusinessKey(SOURCE + ":student:" + r.applicationId()).setVariables(vars));
            jdbc.update("UPDATE bpm_external_application SET process_id=? WHERE id=?",processId,application.get("id"));
            jdbc.update("UPDATE bpm_external_student SET name=?,phone=?,school=? WHERE user_id=? AND source='zhenzhihui' AND creator_revision=0",r.name(),r.phone(),r.school(),userId);
        }
        return Map.of("processId",processId,"workflowUserId",userId);
    }

    public Map<String,Object> progress(Request r) {
        return stages.progress(r.externalUserId(), r.applicationId());
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
