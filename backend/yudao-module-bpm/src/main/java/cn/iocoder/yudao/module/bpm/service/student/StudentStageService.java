package cn.iocoder.yudao.module.bpm.service.student;

import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.FlowableUtils;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import cn.iocoder.yudao.module.bpm.service.task.BpmTaskService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.DelegationState;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.util.*;
import static cn.iocoder.yudao.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.*;

/** Owns student task completion and immutable submissions in the Flowable transaction. */
@Service
public class StudentStageService {
    @Resource private JdbcTemplate jdbc;
    @Resource private ObjectMapper mapper;
    @Resource private TaskService taskService;
    @Resource private HistoryService historyService;
    @Resource private BpmTaskService bpmTasks;
    @Resource private BpmProcessInstanceService processes;
    @Resource private AdminUserService users;
    private static final Map<String,String> TASK_KEYS = Map.of("draft","StudentDraft","revision","StudentRevision");
    private static final Set<String> REVIEW_KEYS = Set.of("CreativeReview","OutlineReview","ScriptReview");

    public record Submission(String externalUserId, String applicationId, String requestKey,
                             String taskId, String stage, String content) { }

    public Submission parse(String body) {
        try {
            Submission r = mapper.readValue(body,Submission.class);
            if (r.externalUserId()==null || !r.externalUserId().matches("[1-9][0-9]{0,18}")
                    || r.applicationId()==null || !r.applicationId().matches("[1-9][0-9]{0,18}")
                    || r.requestKey()==null || !r.requestKey().matches("[a-zA-Z0-9_-]{8,80}")
                    || r.taskId()==null || r.taskId().isBlank() || r.taskId().length()>128
                    || r.stage()==null || !TASK_KEYS.containsKey(r.stage()) || r.content()==null
                    || r.content().isBlank() || r.content().codePointCount(0,r.content().length())>20000)
                throw new IllegalArgumentException();
            return r;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid stage submission"); }
    }

    private Map<String,Object> owned(String external, String application, boolean lock) {
        // Match initial provisioning's identity -> application lock order. Locking reads
        // also avoid taking a stale repeatable-read snapshot before a concurrent commit.
        var identity=jdbc.queryForList("SELECT user_id FROM bpm_external_student WHERE source='zhenzhihui' AND external_user_id=?"+(lock?" FOR UPDATE":""),external);
        if (identity.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var rows=jdbc.queryForList("SELECT a.*,CAST(UNIX_TIMESTAMP(a.created_at)*1000 AS UNSIGNED) AS submitted_at FROM bpm_external_application a WHERE a.source='zhenzhihui' AND a.application_id=? AND a.user_id=?"+(lock?" FOR UPDATE":""),application,identity.get(0).get("user_id"));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private static boolean writable(Task task, long userId, String processId) {
        return task!=null && !task.isSuspended() && task.getDelegationState()!=DelegationState.PENDING
                && task.getParentTaskId()==null && Objects.equals(task.getProcessInstanceId(),processId)
                && String.valueOf(userId).equals(task.getAssignee()) && TASK_KEYS.containsValue(task.getTaskDefinitionKey());
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> submit(Submission r) {
        Map<String,Object> app=owned(r.externalUserId(),r.applicationId(),true);
        long userId=((Number)app.get("user_id")).longValue();
        var user=users.getUser(userId);
        if (user==null || !Integer.valueOf(0).equals(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Student account disabled");
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(r))); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        var replay=jdbc.queryForList("SELECT *,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS submitted_at FROM bpm_external_submission WHERE application_ref=? AND request_key=?",app.get("id"),r.requestKey());
        if (!replay.isEmpty()) {
            if (!digest.equals(replay.get(0).get("payload_hash")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Submission key reused with different content");
            return version(replay.get(0));
        }
        String processId=(String)app.get("process_id");
        Task task=taskService.createTaskQuery().taskId(r.taskId()).singleResult();
        if (!writable(task,userId,processId) || !TASK_KEYS.get(r.stage()).equals(task.getTaskDefinitionKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stage is not currently assigned to this student");
        int number=jdbc.queryForObject("SELECT COALESCE(MAX(version),1)+1 FROM bpm_external_submission WHERE application_ref=?",Integer.class,app.get("id"));
        jdbc.update("INSERT INTO bpm_external_submission(application_ref,version,stage,task_id,request_key,payload_hash,content) VALUES(?,?,?,?,?,?,?)",
                app.get("id"),number,r.stage(),r.taskId(),r.requestKey(),digest,r.content());
        Map<String,Object> vars=new HashMap<>();
        vars.put("script",r.content());
        if ("draft".equals(r.stage())) vars.put("scriptDraft",r.content());
        // The task and immutable version commit together; no administrator/service-account impersonation.
        FlowableUtils.executeAuthenticatedUserId(userId,()->{
            bpmTasks.approveTask(userId,new BpmTaskApproveReqVO().setId(r.taskId())
                    .setReason("学生提交"+("draft".equals(r.stage())?"一卡初稿":"完善剧本")+"，版本 v"+number).setVariables(vars));
            return null;
        });
        return version(jdbc.queryForMap("SELECT *,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS submitted_at FROM bpm_external_submission WHERE application_ref=? AND request_key=?",app.get("id"),r.requestKey()));
    }

    private static Map<String,Object> version(Map<String,Object> row) {
        return Map.of("version",((Number)row.get("version")).intValue(),"stage",row.get("stage"),"content",row.get("content"),
                "task_id",row.get("task_id"),"submitted_at",((Number)row.get("submitted_at")).longValue());
    }
    private List<Map<String,Object>> versions(Map<String,Object> app) {
        List<Map<String,Object>> out=new ArrayList<>();
        out.add(Map.of("version",1,"stage","creative","content",app.get("creative"),"task_id","",
                "submitted_at",((Number)app.get("submitted_at")).longValue()));
        for (var row:jdbc.queryForList("SELECT *,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS submitted_at FROM bpm_external_submission WHERE application_ref=? ORDER BY version",app.get("id"))) out.add(version(row));
        return out;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> progress(String external, String application) {
        var app=owned(external,application,false);
        String processId=(String)app.get("process_id");
        var instance=processId==null?null:processes.getHistoricProcessInstance(processId);
        if (instance==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long userId=((Number)app.get("user_id")).longValue();
        var user=users.getUser(userId);
        List<Map<String,Object>> snapshots=versions(app);
        Map<String,Object> result=new HashMap<>();
        result.put("status",instance.getProcessVariables().get(PROCESS_INSTANCE_VARIABLE_STATUS));
        result.put("name",instance.getName());result.put("end_time",instance.getEndTime()==null?null:instance.getEndTime().getTime());
        result.put("current_task",null);result.put("stage",instance.getEndTime()==null?"waiting_review":"finished");
        if (instance.getEndTime()==null && user!=null && Integer.valueOf(0).equals(user.getStatus())) {
            for (Task task:taskService.createTaskQuery().processInstanceId(processId).list()) {
                String stage=TASK_KEYS.entrySet().stream().filter(e->e.getValue().equals(task.getTaskDefinitionKey())).map(Map.Entry::getKey).findFirst().orElse(null);
                if (stage!=null && writable(task,userId,processId)) {
                    result.put("stage",stage);
                    result.put("current_task",Map.of("task_id",task.getId(),"stage",stage,"name",task.getName(),"content",snapshots.get(snapshots.size()-1).get("stage").equals("creative")?"":snapshots.get(snapshots.size()-1).get("content")));
                    break;
                }
            }
        }
        List<Map<String,Object>> reviews=new ArrayList<>();
        for (var task:historyService.createHistoricTaskInstanceQuery().processInstanceId(processId).finished().includeTaskLocalVariables().orderByHistoricTaskInstanceEndTime().asc().list()) {
            Object status=task.getTaskLocalVariables().get(TASK_VARIABLE_STATUS);
            if (!REVIEW_KEYS.contains(task.getTaskDefinitionKey()) || !(status instanceof Number n) || !Set.of(2,3,5).contains(n.intValue())) continue;
            int number=1;
            for (var v:snapshots) if (((Number)v.get("submitted_at")).longValue()<=task.getCreateTime().getTime()) number=((Number)v.get("version")).intValue();
            Map<String,Object> review=new HashMap<>();review.put("task_id",task.getId());review.put("node_key",task.getTaskDefinitionKey());review.put("name",task.getName());
            review.put("status",status);review.put("reason",task.getTaskLocalVariables().get(TASK_VARIABLE_REASON));review.put("version",number);review.put("finished_at",task.getEndTime().getTime());reviews.add(review);
        }
        result.put("versions",snapshots);result.put("reviews",reviews);
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> adminDetail(long id) {
        var app=jdbc.queryForList("SELECT a.*,s.external_user_id FROM bpm_external_application a JOIN bpm_external_student s ON a.source=s.source AND a.user_id=s.user_id WHERE a.id=?",id);
        if (app.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String,Object> result=new HashMap<>();result.put("application",app.get(0));
        result.put("workflow",progress((String)app.get(0).get("external_user_id"),(String)app.get(0).get("application_id")));
        return result;
    }
}
