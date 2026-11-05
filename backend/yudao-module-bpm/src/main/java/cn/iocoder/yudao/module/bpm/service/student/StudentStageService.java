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
    @Resource private org.flowable.engine.RepositoryService repositoryService;
    @Resource private BpmTaskService bpmTasks;
    @Resource private BpmProcessInstanceService processes;
    @Resource private AdminUserService users;
    private static final Map<String,String> TASK_KEYS = Map.of("creative","StartUserNode","card","StudentDraft","script","StudentScript","final","StudentRevision","draft","StudentDraft","revision","StudentRevision");
    private static final Set<String> REVIEW_KEYS = Set.of("CreativeReview","OutlineReview","ScriptReview","FinalReview");

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
                    || r.content().isBlank() || r.content().codePointCount(0,r.content().length())>20000
                    || ("creative".equals(r.stage()) && r.content().codePointCount(0,r.content().length())>12000))
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
        String field = switch (r.stage()) { case "creative" -> "creative"; case "card", "draft" -> "scriptDraft"; default -> "script"; };
        vars.put(field,r.content());
        if ("draft".equals(r.stage())) vars.put("script",r.content());
        // The task and immutable version commit together; no administrator/service-account impersonation.
        FlowableUtils.executeAuthenticatedUserId(userId,()->{
            bpmTasks.approveTask(userId,new BpmTaskApproveReqVO().setId(r.taskId())
                    .setReason("学生提交"+r.stage()+"，版本 v"+number).setVariables(vars));
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
                boolean four = fourStages(instance.getProcessDefinitionId());
                String stage=studentStage(task.getTaskDefinitionKey(),four);
                if (stage!=null && writable(task,userId,processId)) {
                    result.put("stage",stage);
                    result.put("current_task",Map.of("task_id",task.getId(),"stage",stage,"name",task.getName(),"content","creative".equals(stage)?snapshots.get(snapshots.size()-1).get("content"):snapshots.get(snapshots.size()-1).get("stage").equals("creative")?"":snapshots.get(snapshots.size()-1).get("content")));
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
        result.putAll(describe(instance, taskService.createTaskQuery().processInstanceId(processId).list(), reviews));
        return result;
    }

    private boolean fourStages(String definition) {
        var model=repositoryService.getBpmnModel(definition);
        return model!=null && model.getMainProcess().getFlowElement("FinalReview",true)!=null;
    }

    static String studentStage(String key, boolean four) {
        return switch(key) { case "StartUserNode" -> "creative"; case "StudentDraft" -> four?"card":"draft";
            case "StudentScript" -> "script"; case "StudentRevision" -> four?"final":"revision"; default -> null; };
    }
    static String phase(String key, boolean four) {
        return switch(key) { case "StartUserNode","CreativeReview" -> "creative";
            case "StudentDraft","OutlineReview" -> "card"; case "StudentScript" -> "script";
            case "ScriptReview" -> four?"script":"final"; case "StudentRevision","FinalReview" -> "final"; default -> "production"; };
    }
    Map<String,Object> describe(org.flowable.engine.history.HistoricProcessInstance instance, List<Task> tasks, List<Map<String,Object>> reviews) {
        boolean four=fourStages(instance.getProcessDefinitionId());
        int status=((Number)instance.getProcessVariables().get(PROCESS_INSTANCE_VARIABLE_STATUS)).intValue();
        String current="production", state="approved";
        for (Task task:tasks) {
            String candidate=phase(task.getTaskDefinitionKey(),four);
            if ("production".equals(candidate)) continue;
            current=candidate;state=studentStage(task.getTaskDefinitionKey(),four)==null?"under_review":"awaiting_submission";
            if ("awaiting_submission".equals(state)) {
                Map<String,Object> last=null;
                if (!reviews.isEmpty()) last=reviews.get(reviews.size()-1);
                if (last!=null && Set.of(3,5).contains(((Number)last.get("status")).intValue())) state="needs_changes";
            }
            break;
        }
        if (status==3 || status==4) {
            state=status==3?"rejected":"cancelled";
            current="creative";
            if (!reviews.isEmpty()) {
                var last=reviews.get(reviews.size()-1);String key=(String)last.get("node_key");current=phase(key,four);
                if (((Number)last.get("status")).intValue()==2) current=switch(key) {
                    case "CreativeReview" -> "card"; case "OutlineReview" -> four?"script":"final";
                    case "ScriptReview" -> four?"final":"production"; default -> "production";
                };
            }
        }
        Map<String,String> stages=new LinkedHashMap<>();
        List<String> order=List.of("creative","card","script","final","production");
        for (String p:order.subList(0,4)) stages.put(p,order.indexOf(p)<order.indexOf(current)?"approved":p.equals(current)?state:"not_started");
        if (!four) stages.put("script","not_applicable");
        return Map.of("current_stage",current,"review_status",state,"stage_statuses",stages,"flow_version",four?4:3);
    }

    public record SummaryRequest(String externalUserId,List<String> applicationIds) { }
    public SummaryRequest parseSummaries(String body) {
        try {
            var r=mapper.readValue(body,SummaryRequest.class);
            if (r.externalUserId()==null || !r.externalUserId().matches("[1-9][0-9]{0,18}") || r.applicationIds()==null
                    || r.applicationIds().isEmpty() || r.applicationIds().size()>100
                    || new HashSet<>(r.applicationIds()).size()!=r.applicationIds().size()
                    || r.applicationIds().stream().anyMatch(id->id==null || !id.matches("[1-9][0-9]{0,18}"))) throw new IllegalArgumentException();
            return r;
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid summary request"); }
    }
    @Transactional(readOnly=true)
    public Map<String,Object> summaries(SummaryRequest r) {
        String placeholders=String.join(",",Collections.nCopies(r.applicationIds().size(),"?"));
        List<Object> args=new ArrayList<>();args.add(r.externalUserId());args.addAll(r.applicationIds());
        var apps=jdbc.queryForList("SELECT a.application_id,a.process_id FROM bpm_external_application a JOIN bpm_external_student s ON s.source=a.source AND s.user_id=a.user_id WHERE a.source='zhenzhihui' AND s.external_user_id=? AND a.application_id IN ("+placeholders+")",args.toArray());
        if (apps.size()!=r.applicationIds().size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Set<String> ids=new HashSet<>();for(var a:apps) ids.add((String)a.get("process_id"));
        var instances=historyService.createHistoricProcessInstanceQuery().processInstanceIds(ids).includeProcessVariables().list();
        var active=taskService.createTaskQuery().processInstanceIdIn(ids).list();
        var history=historyService.createHistoricTaskInstanceQuery().processInstanceIdIn(ids).finished().includeTaskLocalVariables().orderByHistoricTaskInstanceEndTime().asc().list();
        Map<String,Object> out=new HashMap<>();
        for(var app:apps) {
            String pid=(String)app.get("process_id");
            var instance=instances.stream().filter(i->pid.equals(i.getId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            List<Map<String,Object>> reviews=new ArrayList<>();
            for(var t:history) {
                Object status=t.getTaskLocalVariables().get(TASK_VARIABLE_STATUS);
                if (pid.equals(t.getProcessInstanceId()) && REVIEW_KEYS.contains(t.getTaskDefinitionKey()) && status instanceof Number n && Set.of(2,3,5).contains(n.intValue()))
                    reviews.add(Map.of("node_key",t.getTaskDefinitionKey(),"status",status));
            }
            var summary=new HashMap<>(describe(instance,active.stream().filter(t->pid.equals(t.getProcessInstanceId())).toList(),reviews));
            summary.put("workflow_status",instance.getProcessVariables().get(PROCESS_INSTANCE_VARIABLE_STATUS));
            out.put((String)app.get("application_id"),summary);
        }
        return out;
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
