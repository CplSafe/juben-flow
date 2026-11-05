package cn.iocoder.yudao.module.bpm.service.student;

import jakarta.annotation.Resource;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Owns immutable A/S decisions and post-approval producer assignment, separately from student author identity. */
@Service
public class StudentProductionService {
    @Resource private JdbcTemplate jdbc;
    @Resource private HistoryService history;

    public static boolean gradedModel(BpmnModel model) {
        return model!=null && model.getMainProcess()!=null && model.getMainProcess().getFlowElement("GradedCardReview",true)!=null;
    }
    static String validGrade(String grade) {
        if (!"A".equals(grade) && !"S".equals(grade)) throw new ServiceException(400,"一卡审核通过必须选择A或S");
        return grade;
    }
    // Called only after the engine's normal assignee validation, within the same approval transaction.
    public void beforeContentApproval(long actor,Task task,ProcessInstance instance,BpmnModel model,String grade) {
        if ((instance.getBusinessKey()==null || !instance.getBusinessKey().startsWith("zhenzhihui:student:")) || !gradedModel(model)) return;
        boolean card="GradedCardReview".equals(task.getTaskDefinitionKey());
        boolean refinement="CardRefinementReview".equals(task.getTaskDefinitionKey());
        if(!card&&!refinement) {if(grade!=null)throw new ServiceException(400,"当前节点不能提交一卡评级");return;}
        if(task.getParentTaskId()!=null || org.flowable.task.api.DelegationState.PENDING.equals(task.getDelegationState()) || cn.iocoder.yudao.module.bpm.enums.task.BpmTaskSignTypeEnum.AFTER.getType().equals(task.getScopeType()))throw new ServiceException(409,"请由Sam或宇哥直接审核，不支持委派或加签通过");
        if(card)validGrade(grade);else if(grade!=null)throw new ServiceException(400,"完善剧本审核不能改写一卡评级");
        var rows=jdbc.queryForList("SELECT id FROM bpm_external_application WHERE process_id=? AND source='zhenzhihui' FOR UPDATE",instance.getId());
        if(rows.isEmpty())throw new ServiceException(404,"申请不存在");
        var active=jdbc.queryForList("SELECT ID_ FROM ACT_RU_TASK WHERE ID_=? AND PROC_INST_ID_=? FOR UPDATE",task.getId(),instance.getId());
        if(active.isEmpty())throw new ServiceException(409,"审批任务已变化");
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM system_users WHERE id=? AND username IN ('sam01','yuge') AND status=0 AND deleted=0",Integer.class,actor)))throw new ServiceException(403,"仅Sam或宇哥可以评级");
        long app=((Number)rows.get(0).get("id")).longValue();
        if(refinement) {if(!currentGrades(List.of(app)).containsKey(app))throw new ServiceException(409,"当前一卡版本尚未通过A/S评级");return;}
        var versions=jdbc.queryForList("SELECT version,stage FROM bpm_external_submission WHERE application_ref=? ORDER BY version DESC LIMIT 1",app);
        if(versions.isEmpty() || !"card".equals(versions.get(0).get("stage")))throw new ServiceException(409,"没有可评级的一卡提交版本");
        jdbc.update("INSERT INTO bpm_external_card_grade(application_ref,version,task_id,grade,reviewer_id) VALUES(?,?,?,?,?)",app,versions.get(0).get("version"),task.getId(),grade,actor);
    }
    public Map<String,Object> metadata(long app) {
        var grades=jdbc.queryForList("SELECT g.version,g.grade,g.reviewer_id,u.nickname AS reviewer_name,CAST(UNIX_TIMESTAMP(g.created_at)*1000 AS UNSIGNED) AS reviewed_at FROM bpm_external_card_grade g LEFT JOIN system_users u ON u.id=g.reviewer_id WHERE application_ref=? ORDER BY version",app);
        String current="";
        var versions=jdbc.queryForList("SELECT MAX(version) AS version FROM bpm_external_submission WHERE application_ref=? AND stage='card'",app);
        Object version=versions.isEmpty()?null:versions.get(0).get("version");
        for(var grade:grades)if(Objects.equals(((Number)grade.get("version")).longValue(),version instanceof Number n?n.longValue():null))current=grade.get("grade").toString();
        return Map.of("card_grade",current,"card_grades",grades,"production_assignment",assignment(app));
    }
    public Map<Long,String> currentGrades(List<Long> apps) {
        if(apps.isEmpty())return Map.of();
        String placeholders=String.join(",",Collections.nCopies(apps.size(),"?"));
        var rows=jdbc.queryForList("SELECT g.application_ref,g.grade FROM bpm_external_card_grade g WHERE g.application_ref IN ("+placeholders+") AND g.version=(SELECT MAX(s.version) FROM bpm_external_submission s WHERE s.application_ref=g.application_ref AND s.stage='card')",apps.toArray());
        Map<Long,String> result=new HashMap<>();for(var row:rows)result.put(((Number)row.get("application_ref")).longValue(),row.get("grade").toString());return result;
    }
    private Map<String,Object> assignment(long app) {
        var rows=jdbc.queryForList("SELECT a.producer_user_id,u.nickname AS producer_name,a.revision,a.assigned_by,d.nickname AS director_name,a.remark,CAST(UNIX_TIMESTAMP(a.updated_at)*1000 AS UNSIGNED) AS assigned_at FROM bpm_external_production_assignment a LEFT JOIN system_users u ON u.id=a.producer_user_id LEFT JOIN system_users d ON d.id=a.assigned_by WHERE application_ref=?",app);
        return rows.isEmpty()?Map.of("revision",0):rows.get(0);
    }
    public List<Map<String,Object>> producers() {
        return jdbc.queryForList("SELECT u.id,u.nickname,u.username FROM system_users u WHERE u.deleted=0 AND u.status=0 AND NOT EXISTS(SELECT 1 FROM bpm_external_student s WHERE s.user_id=u.id) ORDER BY u.id");
    }
    public List<Map<String,Object>> studentScripts(long student,int offset) {
        var rows=jdbc.queryForList("SELECT a.id,a.application_id,a.name,a.school,a.process_id,a.created_at FROM bpm_external_application a JOIN bpm_external_student s ON s.source=a.source AND s.user_id=a.user_id WHERE s.id=? ORDER BY a.id DESC LIMIT 50 OFFSET ?",student,Math.max(0,offset));
        for(var row:rows){row.putAll(metadata(((Number)row.get("id")).longValue()));var process=row.get("process_id")==null?null:history.createHistoricProcessInstanceQuery().processInstanceId((String)row.get("process_id")).includeProcessVariables().singleResult();
            row.put("can_assign",eligible(process));row.put("title",process==null?"":process.getProcessVariables().getOrDefault("studentTitle",process.getName()));}
        return rows;
    }
    static boolean eligible(org.flowable.engine.history.HistoricProcessInstance process) {
        return process!=null && process.getEndTime()!=null && process.getProcessVariables().get(cn.iocoder.yudao.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS) instanceof Number n && n.intValue()==2;
    }
    public record AssignmentRequest(long applicationId,long producerUserId,long expectedRevision,String remark) { }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> assign(long actor,AssignmentRequest request) {
        if(request.applicationId()<=0 || request.producerUserId()<=0 || request.expectedRevision()<0 || request.remark()!=null && request.remark().codePointCount(0,request.remark().length())>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT process_id FROM bpm_external_application WHERE id=? AND source='zhenzhihui' FOR UPDATE",request.applicationId());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var process=history.createHistoricProcessInstanceQuery().processInstanceId((String)rows.get(0).get("process_id")).includeProcessVariables().singleResult();
        if(!eligible(process))throw new ResponseStatusException(HttpStatus.CONFLICT,"剧本流程尚未审核通过，不能分配制剧人");
        var target=jdbc.queryForList("SELECT id FROM system_users u WHERE id=? AND deleted=0 AND status=0 AND NOT EXISTS(SELECT 1 FROM bpm_external_student s WHERE s.user_id=u.id) FOR UPDATE",request.producerUserId());
        if(target.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择启用的后台工作人员");
        var current=assignment(request.applicationId());long revision=((Number)current.get("revision")).longValue();
        if(revision!=request.expectedRevision())throw new ResponseStatusException(HttpStatus.CONFLICT,"分配关系已变化，请刷新后重试");
        String remark=request.remark()==null?"":request.remark().strip();
        if(Objects.equals(current.get("producer_user_id"),request.producerUserId()) && Objects.equals(current.get("remark"),remark))return current;
        jdbc.update("INSERT INTO bpm_external_production_assignment(application_ref,producer_user_id,revision,assigned_by,remark) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE producer_user_id=VALUES(producer_user_id),revision=VALUES(revision),assigned_by=VALUES(assigned_by),remark=VALUES(remark),updated_at=CURRENT_TIMESTAMP(3)",request.applicationId(),request.producerUserId(),revision+1,actor,remark);
        jdbc.update("INSERT INTO bpm_external_production_assignment_history(application_ref,producer_user_id,revision,assigned_by,remark) VALUES(?,?,?,?,?)",request.applicationId(),request.producerUserId(),revision+1,actor,remark);
        return assignment(request.applicationId());
    }
    public List<Map<String,Object>> assignmentHistory(long app) {
        return jdbc.queryForList("SELECT h.revision,h.producer_user_id,u.nickname AS producer_name,h.assigned_by,d.nickname AS director_name,h.remark,CAST(UNIX_TIMESTAMP(h.created_at)*1000 AS UNSIGNED) AS assigned_at FROM bpm_external_production_assignment_history h LEFT JOIN system_users u ON u.id=h.producer_user_id LEFT JOIN system_users d ON d.id=h.assigned_by WHERE h.application_ref=? ORDER BY h.revision",app);
    }
}
