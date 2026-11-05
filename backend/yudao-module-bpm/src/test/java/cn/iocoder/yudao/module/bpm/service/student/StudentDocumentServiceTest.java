package cn.iocoder.yudao.module.bpm.service.student;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class StudentDocumentServiceTest {
 @Test void attachmentMembershipIsCheckedAgainstExactSnapshotFileIds(){
  var s=new StudentDocumentService();ReflectionTestUtils.setField(s,"mapper",new ObjectMapper());
  assertTrue(s.containsFile("{\"attachments\":[{\"file_id\":12}]}",12));
  assertFalse(s.containsFile("{\"attachments\":[{\"file_id\":12}]}",13));
  assertFalse(s.containsFile(null,12));assertFalse(s.containsFile("{\"attachments\":[{\"file_id\":\"12\"}]}",12));
 }

 @Test void onlyOwnerAssignedReviewerOrExplicitAdministratorMayReadSnapshots(){
  var s=new StudentDocumentService();var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var permissions=org.mockito.Mockito.mock(cn.iocoder.yudao.module.system.api.permission.PermissionApi.class);
  var history=org.mockito.Mockito.mock(org.flowable.engine.HistoryService.class);
  var query=org.mockito.Mockito.mock(org.flowable.task.api.history.HistoricTaskInstanceQuery.class,org.mockito.Mockito.RETURNS_SELF);
  ReflectionTestUtils.setField(s,"jdbc",jdbc);ReflectionTestUtils.setField(s,"permissions",permissions);ReflectionTestUtils.setField(s,"historyService",history);
  org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(8L))).thenReturn(java.util.List.of(java.util.Map.of("process_id","pid","user_id",222L)));
  org.mockito.Mockito.when(history.createHistoricTaskInstanceQuery()).thenReturn(query);
  try(var identity=org.mockito.Mockito.mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class)){
   identity.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(222L);assertDoesNotThrow(()->s.authorize(8));
   identity.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(143L);
   var denied=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->s.authorize(8));assertEquals(403,denied.getStatusCode().value());
   org.mockito.Mockito.when(query.count()).thenReturn(1L);assertDoesNotThrow(()->s.authorize(8));
   identity.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn((Long)null);
   assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->s.authorize(8)).getStatusCode().value());
  }
 }
}
