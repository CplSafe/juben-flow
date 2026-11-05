package cn.iocoder.yudao.module.bpm.service.student;

import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;
import cn.iocoder.yudao.module.bpm.service.task.BpmTaskService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudentStageServiceTest {
    StudentStageService service; JdbcTemplate jdbc; AdminUserService users; TaskService tasks;
    BpmTaskService bpm; Task task; TaskQuery query;
    StudentStageService.Submission request=new StudentStageService.Submission("7","9","submission-key","task-1","draft","初稿正文");
    @BeforeEach void setup(){
        service=new StudentStageService();jdbc=mock(JdbcTemplate.class);users=mock(AdminUserService.class);tasks=mock(TaskService.class);bpm=mock(BpmTaskService.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"mapper",new ObjectMapper());ReflectionTestUtils.setField(service,"users",users);ReflectionTestUtils.setField(service,"taskService",tasks);ReflectionTestUtils.setField(service,"bpmTasks",bpm);
        Map<String,Object> app=new HashMap<>(Map.of("id",31L,"user_id",222L,"process_id","pid"));
        when(jdbc.queryForList(contains("FROM bpm_external_student"),eq("7"))).thenReturn(List.of(Map.of("user_id",222L)));
        when(jdbc.queryForList(startsWith("SELECT a.*"),eq("9"),eq(222L))).thenReturn(List.of(app));
        when(jdbc.queryForObject(contains("COUNT(*) FROM bpm_external_submission"),eq(Integer.class),eq(31L))).thenReturn(0);
        when(users.getUser(222L)).thenReturn(new AdminUserDO().setStatus(0));
        task=mock(Task.class);query=mock(TaskQuery.class);
        when(tasks.createTaskQuery()).thenReturn(query);when(query.taskId("task-1")).thenReturn(query);when(query.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn("task-1");when(task.getAssignee()).thenReturn("222");when(task.getProcessInstanceId()).thenReturn("pid");when(task.getTaskDefinitionKey()).thenReturn("StudentDraft");
    }
    @Test void versionsAndTaskAreOwnedAndReplayIsIdempotent(){
        List<Map<String,Object>> stored=new ArrayList<>();
        when(jdbc.queryForList(contains("request_key=?"),anyLong(),anyString())).thenAnswer(inv->stored);
        when(jdbc.queryForObject(contains("MAX(version)"),eq(Integer.class),eq(31L))).thenReturn(2);
        when(jdbc.update(startsWith("INSERT INTO bpm_external_submission"),any(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            stored.add(new HashMap<>(Map.of("version",2,"stage","draft","task_id","task-1","payload_hash",inv.getArgument(6,String.class),"content","初稿正文","submitted_at",2000L)));return 1;
        });
        when(jdbc.queryForMap(contains("bpm_external_submission"),eq(31L),eq("submission-key"))).thenAnswer(inv->stored.get(0));
        var result=service.submit(request);assertEquals(2,result.get("version"));assertEquals("初稿正文",result.get("content"));
        assertEquals(result,service.submit(request));
        var args=ArgumentCaptor.forClass(BpmTaskApproveReqVO.class);verify(bpm,times(1)).approveTask(eq(222L),args.capture());
        assertEquals(Map.of("script","初稿正文","scriptDraft","初稿正文","studentWithdrawalTarget",""),args.getValue().getVariables());
        assertTrue(mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().startsWith("query")).findFirst().orElseThrow().getArgument(0,String.class).endsWith("FOR UPDATE"));
        assertThrows(ResponseStatusException.class,()->service.submit(new StudentStageService.Submission("7","9","submission-key","task-1","draft","篡改内容")));
        assertEquals(1,stored.size());
    }
    @Test void wrongStudentTaskStageAndSuspensionCannotSubmit(){
        when(task.getAssignee()).thenReturn("999");assertThrows(ResponseStatusException.class,()->service.submit(request));
        when(task.getAssignee()).thenReturn("222");when(task.getTaskDefinitionKey()).thenReturn("StudentRevision");assertThrows(ResponseStatusException.class,()->service.submit(request));
        when(task.getTaskDefinitionKey()).thenReturn("StudentDraft");when(task.isSuspended()).thenReturn(true);assertThrows(ResponseStatusException.class,()->service.submit(request));
        when(task.isSuspended()).thenReturn(false);when(task.getDelegationState()).thenReturn(org.flowable.task.api.DelegationState.PENDING);assertThrows(ResponseStatusException.class,()->service.submit(request));
        verifyNoInteractions(bpm);verify(jdbc,never()).update(startsWith("INSERT INTO bpm_external_submission"),any(Object[].class));
    }
    @Test void foreignIdentityAndDisabledUsersAreDenied(){
        assertThrows(ResponseStatusException.class,()->service.submit(new StudentStageService.Submission("8","9","submission-key","task-1","draft","内容")));
        when(users.getUser(222L)).thenReturn(new AdminUserDO().setStatus(1));assertThrows(ResponseStatusException.class,()->service.submit(request));
        verifyNoInteractions(tasks,bpm);
    }
    @Test void malformedStageCannotBeParsed(){
        assertThrows(ResponseStatusException.class,()->service.parse("{}"));
        assertThrows(ResponseStatusException.class,()->service.parse("{\"externalUserId\":\"7\",\"applicationId\":\"9\",\"requestKey\":\"submission-key\",\"taskId\":\"task-1\",\"stage\":\"approval\",\"content\":\"内容\"}"));
    }
    @Test void fourStageStatusesDistinguishReturnsAndNextStage() {
        var repo=mock(org.flowable.engine.RepositoryService.class);
        var model=new org.flowable.bpmn.model.BpmnModel();var process=new org.flowable.bpmn.model.Process();
        var finalNode=new org.flowable.bpmn.model.UserTask();finalNode.setId("FinalReview");process.addFlowElement(finalNode);model.addProcess(process);
        ReflectionTestUtils.setField(service,"repositoryService",repo);when(repo.getBpmnModel("def")).thenReturn(model);
        var instance=mock(org.flowable.engine.history.HistoricProcessInstance.class);when(instance.getProcessDefinitionId()).thenReturn("def");
        when(instance.getProcessVariables()).thenReturn(Map.of(cn.iocoder.yudao.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,1));
        when(task.getTaskDefinitionKey()).thenReturn("StudentScript");
        var approved=List.<Map<String,Object>>of(Map.of("node_key","OutlineReview","status",2));
        var normal=service.describe(instance,List.of(task),approved);
        assertEquals("script",normal.get("current_stage"));assertEquals("awaiting_submission",normal.get("review_status"));
        var returned=service.describe(instance,List.of(task),List.of(Map.of("node_key","ScriptReview","status",5)));
        assertEquals("needs_changes",returned.get("review_status"));
        when(task.getTaskDefinitionKey()).thenReturn("ScriptReview");assertEquals("under_review",service.describe(instance,List.of(task),approved).get("review_status"));
        when(task.getTaskDefinitionKey()).thenReturn("DirectorReview");assertEquals("approved",service.describe(instance,List.of(task),approved).get("review_status"));
        assertEquals("production",service.describe(instance,List.of(task),approved).get("current_stage"));
        assertEquals("card",StudentStageService.studentStage("StudentDraft",true));assertEquals("draft",StudentStageService.studentStage("StudentDraft",false));
        assertEquals("final",StudentStageService.phase("ScriptReview",false));
        when(instance.getProcessVariables()).thenReturn(Map.of(cn.iocoder.yudao.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS,4));
        assertEquals("creative",service.describe(instance,List.of(),List.of()).get("current_stage"));
        assertEquals("script",service.describe(instance,List.of(),approved).get("current_stage"));
        assertEquals("cancelled",service.describe(instance,List.of(),approved).get("review_status"));
    }
    @Test void withdrawalIsOwnedIdempotentAndCannotTargetANewerTask() {
        var runtime=mock(org.flowable.engine.RuntimeService.class);
        var builder=mock(org.flowable.engine.runtime.ChangeActivityStateBuilder.class,RETURNS_SELF);
        var repo=mock(org.flowable.engine.RepositoryService.class);
        ReflectionTestUtils.setField(service,"runtimeService",runtime);ReflectionTestUtils.setField(service,"repositoryService",repo);
        when(runtime.createChangeActivityStateBuilder()).thenReturn(builder);
        when(query.processInstanceId("pid")).thenReturn(query);when(query.list()).thenReturn(List.of(task));
        when(task.getTaskDefinitionKey()).thenReturn("CreativeReview");when(task.getAssignee()).thenReturn("143");
        when(jdbc.queryForObject(contains("MAX(version)"),eq(Integer.class),eq(31L))).thenReturn(1);
        Map<String,Object> stored=new HashMap<>();
        when(jdbc.queryForList(contains("FROM bpm_external_withdrawal"),eq(31L),eq("withdraw-key"))).thenAnswer(inv->stored.isEmpty()?List.of():List.of(new HashMap<>(stored)));
        when(jdbc.update(startsWith("INSERT INTO bpm_external_withdrawal"),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{stored.putAll(Map.of("task_id","task-1","stage","creative","version",1,"reason","change","withdrawn_at",123L,"payload_hash",inv.getArgument(3,String.class)));return 1;});
        when(jdbc.queryForMap(contains("FROM bpm_external_withdrawal"),eq(31L),eq("withdraw-key"))).thenReturn(Map.of("task_id","task-1","stage","creative","version",1,"reason","change","withdrawn_at",123L));
        // JDBC vararg position 3 is task_id; payload hash is position 3 of SQL args, invocation index 3 because SQL is index0.
        var r=new StudentStageService.WithdrawRequest("7","9","withdraw-key","task-1","change");
        var first=service.withdraw(r);assertEquals("creative",first.get("stage"));assertEquals(first,service.withdraw(r));
        verify(runtime,times(1)).createChangeActivityStateBuilder();verify(bpm).processTaskCanceled("task-1");
        verify(builder).moveActivityIdsToSingleActivityId(List.of("CreativeReview"),"StartUserNode");
        verify(builder).processVariable("RETURN_FLAG_StartUserNode",true);
        assertThrows(ResponseStatusException.class,()->service.withdraw(new StudentStageService.WithdrawRequest("7","9","withdraw-new","stale-task","change")));
        assertThrows(ResponseStatusException.class,()->service.withdraw(new StudentStageService.WithdrawRequest("8","9","withdraw-new","task-1","change")));
    }
    @Test void submissionWithoutStructuredDataIsRejectedOnceAnApplicationUsesIt() {
        when(jdbc.queryForObject(contains("COUNT(*) FROM bpm_external_submission"),eq(Integer.class),eq(31L))).thenReturn(1);
        assertThrows(ResponseStatusException.class,()->service.submit(request));verifyNoInteractions(bpm);
    }

    @Test void gradedCardFlowHasNoFinalOrProductionStageAndReviewIsNeverStudentEditable() {
        var repo=mock(org.flowable.engine.RepositoryService.class);ReflectionTestUtils.setField(service,"repositoryService",repo);
        var model=new org.flowable.bpmn.model.BpmnModel();var process=new org.flowable.bpmn.model.Process();model.addProcess(process);var node=new org.flowable.bpmn.model.UserTask();node.setId("GradedCardReview");process.addFlowElement(node);when(repo.getBpmnModel("graded")).thenReturn(model);
        var instance=mock(org.flowable.engine.history.HistoricProcessInstance.class);when(instance.getProcessDefinitionId()).thenReturn("graded");when(instance.getProcessVariables()).thenReturn(Map.of("PROCESS_STATUS",1));
        when(task.getTaskDefinitionKey()).thenReturn("GradedCardReview");assertEquals("under_review",service.describe(instance,List.of(task),List.of()).get("review_status"));
        when(task.getTaskDefinitionKey()).thenReturn("CardRefinementReview");var review=service.describe(instance,List.of(task),List.of());assertEquals("under_review",review.get("review_status"));assertEquals("script",review.get("current_stage"));assertNull(StudentStageService.studentStage("CardRefinementReview",true));
        when(instance.getEndTime()).thenReturn(new Date());when(instance.getProcessVariables()).thenReturn(Map.of("PROCESS_STATUS",2));var ended=service.describe(instance,List.of(),List.of());assertEquals(5,ended.get("flow_version"));assertEquals("script",ended.get("current_stage"));assertEquals("approved",ended.get("review_status"));assertEquals("not_applicable",((Map<?,?>)ended.get("stage_statuses")).get("final"));
    }
}
