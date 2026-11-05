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
    @Resource private StudentProductionService production;
    @Resource private ObjectMapper mapper;
    @Resource private TaskService taskService;
    @Resource private HistoryService historyService;
    @Resource private org.flowable.engine.RuntimeService runtimeService;
    @Resource private org.flowable.engine.RepositoryService repositoryService;
    @Resource private BpmTaskService bpmTasks;
    @Resource private BpmProcessInstanceService processes;
    @Resource private AdminUserService users;
    private static final Map<String,String> TASK_KEYS = Map.of("creative","StartUserNode","card","StudentDraft","script","StudentScript","final","StudentRevision","draft","StudentDraft","revision","StudentRevision");
    private static boolean contentReview(String key) {return key!=null && REVIEW_KEYS.contains(key);}
    private static final Set<String> REVIEW_KEYS = Set.of("CreativeReview","OutlineReview","ScriptReview","FinalReview","GradedCardReview","CardRefinementReview");

    public record Submission(String externalUserId, String applicationId, String requestKey,
                             String taskId, String stage, String content,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.fasterxml.jackson.databind.JsonNode data) {
        public Submission(String externalUserId,String applicationId,String requestKey,String taskId,String stage,String content) {this(externalUserId,applicationId,requestKey,taskId,stage,content,null);}
    }

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
            if (r.data()!=null && !r.data().isNull() && (!r.data().isObject() || r.data().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)) throw new IllegalArgumentException();
            return r;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid stage submission"); }
    }

    static void validateStructuredSnapshot(com.fasterxml.jackson.databind.JsonNode data) {
        if(data==null||!data.isObject()||!data.path("attachments").isArray()||data.path("attachments").isEmpty()||data.path("attachments").size()>30)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Structured attachments required");
        Set<Long> ids=new HashSet<>();
        for(var file:data.path("attachments")) if(!file.path("file_id").canConvertToLong() || file.path("file_id").asLong()<=0 || !ids.add(file.path("file_id").asLong()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid attachment references");
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
                && String.valueOf(userId).equals(task.getAssignee()) && task.getTaskDefinitionKey()!=null && TASK_KEYS.containsValue(task.getTaskDefinitionKey());
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
        boolean structured=app.get("data_json")!=null || jdbc.queryForObject("SELECT COUNT(*) FROM bpm_external_submission WHERE application_ref=? AND data_json IS NOT NULL",Integer.class,app.get("id"))>0;
        if (structured && (r.data()==null || r.data().isNull())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Structured stage data required");
        if(r.data()!=null&&!r.data().isNull())validateStructuredSnapshot(r.data());
        String processId=(String)app.get("process_id");
        Task task=taskService.createTaskQuery().taskId(r.taskId()).singleResult();
        if (!writable(task,userId,processId) || !TASK_KEYS.get(r.stage()).equals(task.getTaskDefinitionKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stage is not currently assigned to this student");
        int number=jdbc.queryForObject("SELECT COALESCE(MAX(version),1)+1 FROM bpm_external_submission WHERE application_ref=?",Integer.class,app.get("id"));
        String structuredJson=null;
        if (r.data()!=null && !r.data().isNull()) {
            var data=r.data().deepCopy();
            if ("final".equals(r.stage()) || "script".equals(r.stage()) && StudentProductionService.gradedModel(repositoryService.getBpmnModel(task.getProcessDefinitionId()))) {
                if(!data.path("originality_confirmed").asBoolean() || !"originality-2026-11-05".equals(data.path("originality_version").asText()) || data.has("originality_confirmed_at")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请重新确认原创声明");
                ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("originality_confirmed_at",java.time.Instant.now().toString());}
            structuredJson=data.toString();
        }
        jdbc.update("INSERT INTO bpm_external_submission(application_ref,version,stage,task_id,request_key,payload_hash,content,data_json) VALUES(?,?,?,?,?,?,?,?)",
                app.get("id"),number,r.stage(),r.taskId(),r.requestKey(),digest,r.content(),structuredJson);
        Map<String,Object> vars=new HashMap<>();
        String field = switch (r.stage()) { case "creative" -> "creative"; case "card", "draft" -> "scriptDraft"; default -> "script"; };
        vars.put(field,r.content());
        vars.put("studentWithdrawalTarget","");
        if (r.data()!=null && !r.data().isNull()) {
            vars.put("studentForm",r.data().toString());
            if("creative".equals(r.stage())) {vars.put("projectName",r.data().path("title").asText());vars.put("studentTitle",r.data().path("title").asText());vars.put("studentGenre",r.data().path("genre").asText());}
        }
        if ("draft".equals(r.stage())) vars.put("script",r.content());
        // The task and immutable version commit together; no administrator/service-account impersonation.
        FlowableUtils.executeAuthenticatedUserId(userId,()->{
            bpmTasks.approveTask(userId,new BpmTaskApproveReqVO().setId(r.taskId())
                    .setReason("学生提交"+r.stage()+"，版本 v"+number).setVariables(vars));
            return null;
        });
        return version(jdbc.queryForMap("SELECT *,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS submitted_at FROM bpm_external_submission WHERE application_ref=? AND request_key=?",app.get("id"),r.requestKey()));
    }

    private Map<String,Object> version(Map<String,Object> row) {
        Map<String,Object> out=new HashMap<>(Map.of("version",((Number)row.get("version")).intValue(),"stage",row.get("stage"),"content",row.get("content"),
                "task_id",row.get("task_id"),"submitted_at",((Number)row.get("submitted_at")).longValue()));
        out.put("data",json(row.get("data_json")));return out;
    }
    private Object json(Object raw) {
        if (raw==null) return null;
        try{return mapper.readValue(raw.toString(),Map.class);}catch(Exception e){throw new IllegalStateException("Stored structured snapshot unavailable",e);}
    }

    private List<Map<String,Object>> versions(Map<String,Object> app) {
        List<Map<String,Object>> out=new ArrayList<>();
        Map<String,Object> initial=new HashMap<>(Map.of("version",1,"stage","creative","content",app.get("creative"),"task_id","","submitted_at",((Number)app.get("submitted_at")).longValue()));
        initial.put("data",json(app.get("data_json")));out.add(initial);
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
                    Map<String,Object> edit=new HashMap<>(Map.of("task_id",task.getId(),"stage",stage,"name",task.getName(),"content","creative".equals(stage)?snapshots.get(snapshots.size()-1).get("content"):snapshots.get(snapshots.size()-1).get("stage").equals("creative")?"":snapshots.get(snapshots.size()-1).get("content")));
                    edit.put("data",prefill(stage,snapshots));result.put("current_task",edit);
                    break;
                }
            }
        }
        List<Map<String,Object>> reviews=new ArrayList<>();
        for (var task:historyService.createHistoricTaskInstanceQuery().processInstanceId(processId).finished().includeTaskLocalVariables().orderByHistoricTaskInstanceEndTime().asc().list()) {
            Object status=task.getTaskLocalVariables().get(TASK_VARIABLE_STATUS);
            if (!contentReview(task.getTaskDefinitionKey()) || !(status instanceof Number n) || !Set.of(2,3,5).contains(n.intValue())) continue;
            int number=1;
            for (var v:snapshots) if (((Number)v.get("submitted_at")).longValue()<=task.getCreateTime().getTime()) number=((Number)v.get("version")).intValue();
            Map<String,Object> review=new HashMap<>();review.put("task_id",task.getId());review.put("node_key",task.getTaskDefinitionKey());review.put("name",task.getName());
            review.put("status",status);review.put("reason",task.getTaskLocalVariables().get(TASK_VARIABLE_REASON));review.put("version",number);review.put("finished_at",task.getEndTime().getTime());reviews.add(review);
        }
        result.put("versions",snapshots);result.put("reviews",reviews);
        result.putAll(production.metadata(((Number)app.get("id")).longValue()));
        result.put("structured",snapshots.stream().anyMatch(v->v.get("data")!=null));
        var actions=jdbc.queryForList("SELECT task_id,stage,version,reason,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS withdrawn_at FROM bpm_external_withdrawal WHERE application_ref=? ORDER BY id",app.get("id"));
        result.put("withdrawals",actions);
        List<Task> active=taskService.createTaskQuery().processInstanceId(processId).list();
        boolean can=instance.getEndTime()==null && user!=null && Integer.valueOf(0).equals(user.getStatus()) && !active.isEmpty() && active.stream().allMatch(t->contentReview(t.getTaskDefinitionKey()) && !t.isSuspended());
        result.put("can_withdraw",can);
        if(can)result.put("withdraw_task_id",active.get(0).getId());
        result.putAll(describe(instance, taskService.createTaskQuery().processInstanceId(processId).list(), reviews));
        return result;
    }

    private Object prefill(String stage,List<Map<String,Object>> snapshots) {
        for (int i=snapshots.size()-1;i>=0;i--) {
            var v=snapshots.get(i);
            if(stage.equals(v.get("stage")) && v.get("data") instanceof Map<?,?> raw) {
                Map<String,Object> out=new HashMap<>();raw.forEach((k,val)->out.put(k.toString(),val));
                if("final".equals(stage)||"script".equals(stage)){out.remove("originality_confirmed");out.remove("originality_confirmed_at");out.remove("originality_version");}
                return out;
            }
        }
        Map<String,Object> inherited=new HashMap<>();
        if (!Set.of("card","script").contains(stage)) return null;
        for(var v:snapshots) if(v.get("data") instanceof Map<?,?> raw) {
            if(raw.get("title")!=null) inherited.put("title_zh",raw.get("title"));
            if(raw.get("title_zh")!=null) inherited.put("title_zh",raw.get("title_zh"));
            if("script".equals(stage) && raw.get("genre")!=null) inherited.put("genre",raw.get("genre"));
        }
        return inherited.isEmpty()?null:inherited;
    }

    private boolean fourStages(String definition) {
        var model=repositoryService.getBpmnModel(definition);
        return model!=null && (model.getMainProcess().getFlowElement("FinalReview",true)!=null || StudentProductionService.gradedModel(model));
    }

    static String studentStage(String key, boolean four) {
        if(key==null)return null;
        return switch(key) { case "StartUserNode" -> "creative"; case "StudentDraft" -> four?"card":"draft";
            case "StudentScript" -> "script"; case "StudentRevision" -> four?"final":"revision"; default -> null; };
    }
    static String phase(String key, boolean four) {
        if(key==null)return "production";
        return switch(key) { case "StartUserNode","CreativeReview" -> "creative";
            case "StudentDraft","OutlineReview","GradedCardReview" -> "card"; case "StudentScript","CardRefinementReview" -> "script";
            case "ScriptReview" -> four?"script":"final"; case "StudentRevision","FinalReview" -> "final"; default -> "production"; };
    }
    Map<String,Object> describe(org.flowable.engine.history.HistoricProcessInstance instance, List<Task> tasks, List<Map<String,Object>> reviews) {
        boolean four=fourStages(instance.getProcessDefinitionId());
        boolean graded=StudentProductionService.gradedModel(repositoryService.getBpmnModel(instance.getProcessDefinitionId()));
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
        Object withdrawalTarget=instance.getProcessVariables().get("studentWithdrawalTarget");
        if (instance.getEndTime()==null && withdrawalTarget instanceof String target && !target.isBlank() && tasks.stream().anyMatch(t->target.equals(t.getTaskDefinitionKey()))) state="withdrawn";
        if (status==3 || status==4) {
            state=status==3?"rejected":"cancelled";
            current="creative";
            if (!reviews.isEmpty()) {
                var last=reviews.get(reviews.size()-1);String key=(String)last.get("node_key");current=phase(key,four);
                if (((Number)last.get("status")).intValue()==2) current=switch(key) {
                    case "CreativeReview" -> "card"; case "GradedCardReview","CardRefinementReview" -> "script"; case "OutlineReview" -> four?"script":"final";
                    case "ScriptReview" -> four?"final":"production"; default -> "production";
                };
            }
        }
        if(graded && instance.getEndTime()!=null && status==2){current="script";state="approved";}
        Map<String,String> stages=new LinkedHashMap<>();
        List<String> order=List.of("creative","card","script","final","production");
        for (String p:order.subList(0,4)) stages.put(p,order.indexOf(p)<order.indexOf(current)?"approved":p.equals(current)?state:"not_started");
        if (!four) stages.put("script","not_applicable");
        if(graded)stages.put("final","not_applicable");
        Map<String,Object> description=new HashMap<>(Map.of("current_stage",current,"review_status",state,"stage_statuses",stages,"flow_version",graded?5:four?4:3));
        Object title=instance.getProcessVariables().get("studentTitle"),genre=instance.getProcessVariables().get("studentGenre");
        description.put("display_title",title instanceof String?title:"");description.put("display_genre",genre instanceof String?genre:"");return description;
    }

    public record WithdrawRequest(String externalUserId,String applicationId,String requestKey,String taskId,String reason) { }
    public WithdrawRequest parseWithdraw(String body) {
        try {
            var r=mapper.readValue(body,WithdrawRequest.class);
            if(r.externalUserId()==null || !r.externalUserId().matches("[1-9][0-9]{0,18}") || r.applicationId()==null || !r.applicationId().matches("[1-9][0-9]{0,18}") || r.requestKey()==null || !r.requestKey().matches("[a-zA-Z0-9_-]{8,80}") || r.taskId()==null || r.taskId().isBlank() || r.taskId().length()>128 || r.reason()==null || r.reason().codePointCount(0,r.reason().length())>500)throw new IllegalArgumentException();
            return r;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid withdrawal");}
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> withdraw(WithdrawRequest r) {
        var app=owned(r.externalUserId(),r.applicationId(),true);long userId=((Number)app.get("user_id")).longValue();
        var user=users.getUser(userId);if(user==null || !Integer.valueOf(0).equals(user.getStatus()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(r)));}catch(Exception e){throw new IllegalArgumentException(e);}
        var previous=jdbc.queryForList("SELECT task_id,stage,version,reason,payload_hash,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS withdrawn_at FROM bpm_external_withdrawal WHERE application_ref=? AND request_key=?",app.get("id"),r.requestKey());
        if(!previous.isEmpty()) {if(!hash.equals(previous.get(0).remove("payload_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT);return previous.get(0);}
        String pid=(String)app.get("process_id");
        // Lock actual active task rows before reading their stage; concurrent staff approval either wins before this read, or sees deleted/stale tasks and rolls back.
        jdbc.queryForList("SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_=? ORDER BY ID_ FOR UPDATE",pid);
        var active=taskService.createTaskQuery().processInstanceId(pid).list();
        Task current=active.stream().filter(t->r.taskId().equals(t.getId())).findFirst().orElse(null);
        if(current==null || current.isSuspended() || !contentReview(current.getTaskDefinitionKey()) || active.stream().anyMatch(t->!current.getTaskDefinitionKey().equals(t.getTaskDefinitionKey()) || t.isSuspended()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Review task changed");
        String target=switch(current.getTaskDefinitionKey()){case "CreativeReview"->"StartUserNode";case "OutlineReview","GradedCardReview"->"StudentDraft";case "CardRefinementReview"->"StudentScript";case "ScriptReview"->fourStages(current.getProcessDefinitionId())?"StudentScript":"StudentRevision";default->"StudentRevision";};
        int number=jdbc.queryForObject("SELECT COALESCE(MAX(version),1) FROM bpm_external_submission WHERE application_ref=?",Integer.class,app.get("id"));
        String stage=phase(current.getTaskDefinitionKey(),fourStages(current.getProcessDefinitionId()));
        jdbc.update("INSERT INTO bpm_external_withdrawal(application_ref,request_key,payload_hash,task_id,stage,version,reason) VALUES(?,?,?,?,?,?,?)",app.get("id"),r.requestKey(),hash,r.taskId(),stage,number,r.reason());
        FlowableUtils.executeAuthenticatedUserId(userId,()->{
            for(Task task:active){taskService.addComment(task.getId(),pid,"withdraw","学生撤回本轮提交："+r.reason());bpmTasks.processTaskCanceled(task.getId());}
            runtimeService.createChangeActivityStateBuilder().processInstanceId(pid)
                .moveActivityIdsToSingleActivityId(List.of(current.getTaskDefinitionKey()),target)
                .processVariable(String.format(PROCESS_INSTANCE_VARIABLE_RETURN_FLAG,target),Boolean.TRUE)
                .processVariable("studentWithdrawalTarget",target).changeState();return null;
        });
        return jdbc.queryForMap("SELECT task_id,stage,version,reason,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS withdrawn_at FROM bpm_external_withdrawal WHERE application_ref=? AND request_key=?",app.get("id"),r.requestKey());
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
        var apps=jdbc.queryForList("SELECT a.id,a.application_id,a.process_id FROM bpm_external_application a JOIN bpm_external_student s ON s.source=a.source AND s.user_id=a.user_id WHERE a.source='zhenzhihui' AND s.external_user_id=? AND a.application_id IN ("+placeholders+")",args.toArray());
        if (apps.size()!=r.applicationIds().size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Set<String> ids=new HashSet<>();for(var a:apps) ids.add((String)a.get("process_id"));
        var instances=historyService.createHistoricProcessInstanceQuery().processInstanceIds(ids).includeProcessVariables().list();
        var active=taskService.createTaskQuery().processInstanceIdIn(ids).list();
        var history=historyService.createHistoricTaskInstanceQuery().processInstanceIdIn(ids).finished().includeTaskLocalVariables().orderByHistoricTaskInstanceEndTime().asc().list();
        Map<Long,String> grades=production.currentGrades(apps.stream().map(a->((Number)a.get("id")).longValue()).toList());
        Map<String,Object> out=new HashMap<>();
        for(var app:apps) {
            String pid=(String)app.get("process_id");
            var instance=instances.stream().filter(i->pid.equals(i.getId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            List<Map<String,Object>> reviews=new ArrayList<>();
            for(var t:history) {
                Object status=t.getTaskLocalVariables().get(TASK_VARIABLE_STATUS);
                if (pid.equals(t.getProcessInstanceId()) && contentReview(t.getTaskDefinitionKey()) && status instanceof Number n && Set.of(2,3,5).contains(n.intValue()))
                    reviews.add(Map.of("node_key",t.getTaskDefinitionKey(),"status",status));
            }
            var summary=new HashMap<>(describe(instance,active.stream().filter(t->pid.equals(t.getProcessInstanceId())).toList(),reviews));
            summary.put("card_grade",grades.getOrDefault(((Number)app.get("id")).longValue(),""));
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
