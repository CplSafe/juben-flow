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
        when(users.getUser(222L)).thenReturn(new AdminUserDO().setStatus(0));
        task=mock(Task.class);query=mock(TaskQuery.class);
        when(tasks.createTaskQuery()).thenReturn(query);when(query.taskId("task-1")).thenReturn(query);when(query.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn("task-1");when(task.getAssignee()).thenReturn("222");when(task.getProcessInstanceId()).thenReturn("pid");when(task.getTaskDefinitionKey()).thenReturn("StudentDraft");
    }
    @Test void versionsAndTaskAreOwnedAndReplayIsIdempotent(){
        List<Map<String,Object>> stored=new ArrayList<>();
        when(jdbc.queryForList(contains("request_key=?"),anyLong(),anyString())).thenAnswer(inv->stored);
        when(jdbc.queryForObject(contains("MAX(version)"),eq(Integer.class),eq(31L))).thenReturn(2);
        when(jdbc.update(startsWith("INSERT INTO bpm_external_submission"),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            stored.add(new HashMap<>(Map.of("version",2,"stage","draft","task_id","task-1","payload_hash",inv.getArgument(6,String.class),"content","初稿正文","submitted_at",2000L)));return 1;
        });
        when(jdbc.queryForMap(contains("bpm_external_submission"),eq(31L),eq("submission-key"))).thenAnswer(inv->stored.get(0));
        var result=service.submit(request);assertEquals(2,result.get("version"));assertEquals("初稿正文",result.get("content"));
        assertEquals(result,service.submit(request));
        var args=ArgumentCaptor.forClass(BpmTaskApproveReqVO.class);verify(bpm,times(1)).approveTask(eq(222L),args.capture());
        assertEquals(Map.of("script","初稿正文","scriptDraft","初稿正文"),args.getValue().getVariables());
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
}
