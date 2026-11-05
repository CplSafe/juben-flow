package cn.iocoder.yudao.module.bpm.service.student;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.flowable.engine.*;
import org.flowable.task.api.Task;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.history.*;
import org.flowable.bpmn.model.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class StudentProductionServiceTest {
 StudentProductionService service;JdbcTemplate jdbc;HistoryService history;
 @BeforeEach void setup(){service=new StudentProductionService();jdbc=mock(JdbcTemplate.class);history=mock(HistoryService.class);ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"history",history);}
 @Test void cardGradeIsRequiredAndBoundToRealReviewerAndVersion(){
  var task=mock(Task.class);when(task.getId()).thenReturn("review");when(task.getTaskDefinitionKey()).thenReturn("GradedCardReview");
  var instance=mock(ProcessInstance.class);when(instance.getId()).thenReturn("pid");when(instance.getBusinessKey()).thenReturn("zhenzhihui:student:7");
  var model=new BpmnModel();var process=new org.flowable.bpmn.model.Process();model.addProcess(process);var node=new UserTask();node.setId("GradedCardReview");process.addFlowElement(node);
  for(String invalid:new String[]{null,"","B","a"})assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.beforeContentApproval(143,task,instance,model,invalid));
  when(jdbc.queryForList(contains("bpm_external_application"),eq("pid"))).thenReturn(List.of(Map.of("id",9L)));
  when(jdbc.queryForList(contains("ACT_RU_TASK"),eq("review"),eq("pid"))).thenReturn(List.of(Map.of("ID_","review")));
  when(jdbc.queryForObject(contains("username IN"),eq(Integer.class),eq(143L))).thenReturn(1);
  when(jdbc.queryForList(startsWith("SELECT version,stage"),eq(9L))).thenReturn(List.of(Map.of("stage","card","version",3)));
  service.beforeContentApproval(143,task,instance,model,"S");verify(jdbc).update(startsWith("INSERT INTO bpm_external_card_grade"),eq(9L),eq(3),eq("review"),eq("S"),eq(143L));
  assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.beforeContentApproval(145,task,instance,model,"A"));
  when(task.getTaskDefinitionKey()).thenReturn("CardRefinementReview");
  assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.beforeContentApproval(143,task,instance,model,null));
  when(jdbc.queryForList(contains("SELECT g.application_ref,g.grade"),any(Object[].class))).thenReturn(List.of(Map.of("application_ref",9L,"grade","S")));
  assertDoesNotThrow(()->service.beforeContentApproval(143,task,instance,model,null));
  when(task.getTaskDefinitionKey()).thenReturn("GradedCardReview");
  when(jdbc.queryForList(contains("ACT_RU_TASK"),eq("review"),eq("pid"))).thenReturn(List.of());assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.beforeContentApproval(143,task,instance,model,"A"));
 }
 @Test void onlyCompletedApprovedScriptsCanBeAssignedAndRevisionConflictsNeverOverwrite(){
  var process=mock(HistoricProcessInstance.class);when(process.getProcessVariables()).thenReturn(Map.of("PROCESS_STATUS",1));assertFalse(StudentProductionService.eligible(process));when(process.getEndTime()).thenReturn(new Date());assertFalse(StudentProductionService.eligible(process));when(process.getProcessVariables()).thenReturn(Map.of("PROCESS_STATUS",2));assertTrue(StudentProductionService.eligible(process));
  var query=mock(HistoricProcessInstanceQuery.class,RETURNS_SELF);when(history.createHistoricProcessInstanceQuery()).thenReturn(query);when(query.singleResult()).thenReturn(process);
  when(jdbc.queryForList(contains("SELECT process_id FROM bpm_external_application"),eq(9L))).thenReturn(List.of(Map.of("process_id","pid")));
  when(jdbc.queryForList(contains("SELECT id FROM system_users"),eq(145L))).thenReturn(List.of(Map.of("id",145L)));
  when(jdbc.queryForList(contains("FROM bpm_external_production_assignment a"),eq(9L))).thenReturn(List.of(Map.of("revision",2L,"producer_user_id",145L,"remark","existing")));
  assertThrows(ResponseStatusException.class,()->service.assign(142,new StudentProductionService.AssignmentRequest(9,145,1,"new")));verify(jdbc,never()).update(anyString(),any(Object[].class));
  assertEquals(2L,service.assign(142,new StudentProductionService.AssignmentRequest(9,145,2,"existing")).get("revision"));
  service.assign(142,new StudentProductionService.AssignmentRequest(9,145,2,"new"));verify(jdbc).update(startsWith("INSERT INTO bpm_external_production_assignment_history"),eq(9L),eq(145L),eq(3L),eq(142L),eq("new"));
  when(jdbc.queryForList(contains("SELECT id FROM system_users"),eq(145L))).thenReturn(List.of());assertThrows(ResponseStatusException.class,()->service.assign(142,new StudentProductionService.AssignmentRequest(9,145,2,"new")));
 }
}
