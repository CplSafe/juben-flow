package cn.iocoder.yudao.module.bpm.service.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StudentBridgeServiceTest {
    StudentBridgeService service;
    JdbcTemplate jdbc;
    AdminUserService users;
    BpmProcessInstanceService processes;
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    final String secret="01234567890123456789012345678901";
    @BeforeEach void init() {
        service=new StudentBridgeService(); jdbc=mock(JdbcTemplate.class); users=mock(AdminUserService.class);
        processes=mock(BpmProcessInstanceService.class); redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc); ReflectionTestUtils.setField(service,"users",users);
        ReflectionTestUtils.setField(service,"processes",processes); ReflectionTestUtils.setField(service,"redis",redis);
        ReflectionTestUtils.setField(service,"mapper",new ObjectMapper()); ReflectionTestUtils.setField(service,"secret",secret);
        ReflectionTestUtils.setField(service,"deptId",123L); ReflectionTestUtils.setField(service,"processKey","screenwriting_production");
        when(redis.opsForValue()).thenReturn(values);
    }
    String signature(String path,String body,String timestamp,String nonce) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+path+"\n"+hash).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void signaturesRejectTamperingReplayAndExpiry() throws Exception {
        String path="/bpm/student-bridge/submit",body="{}",nonce="a".repeat(32),ts=""+Instant.now().getEpochSecond();
        when(values.setIfAbsent(anyString(),eq("1"),any(java.time.Duration.class))).thenReturn(true,false);
        String sig=signature(path,body,ts,nonce);
        service.authenticate(path,body,ts,nonce,sig);
        assertThrows(ResponseStatusException.class,()->service.authenticate(path,body,ts,nonce,sig));
        assertThrows(ResponseStatusException.class,()->service.authenticate(path,body+" ",ts,nonce,sig));
        assertThrows(ResponseStatusException.class,()->service.authenticate(path+"x",body,ts,nonce,sig));
        String expired=""+(Instant.now().getEpochSecond()-1000);
        String old=signature(path,body,expired,nonce);
        assertThrows(ResponseStatusException.class,()->service.authenticate(path,body,expired,nonce,old));
    }
    @Test void malformedIdentityRejected() {
        assertThrows(ResponseStatusException.class,()->service.parse("{\"externalUserId\":\"0\",\"applicationId\":\"1\"}",false));
        assertThrows(ResponseStatusException.class,()->service.parse("{\"externalUserId\":\"1\",\"applicationId\":\"1\"}",true));
    }
    @Test void createsStudentAndUsesActualStudentAsInitiator() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(123L))).thenReturn(1);
        Map<String,Object> student=new HashMap<>();student.put("id",1L);
        when(jdbc.queryForMap(contains("bpm_external_student"),anyString(),anyString())).thenReturn(student);
        var req=new StudentBridgeService.Request("7","9","学生","13800000000","学校","创意","Sample Title",cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree("{\"title\":\"Sample Title\",\"attachments\":[{\"file_id\":1}]}"),cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree("{\"user_id\":7,\"revision\":1,\"real_name\":\"Creator\",\"phone\":\"13800000000\",\"school\":\"School\"}"));
        when(users.createExternalStudent(any())).thenReturn(222L);
        when(users.getUser(222L)).thenReturn(new AdminUserDO().setStatus(0));
        Map<String,Object> application=new HashMap<>(Map.of("id",2L,"user_id",222L));
        when(jdbc.queryForMap(contains("FROM bpm_external_application"),anyString(),anyString())).thenReturn(application);
        when(jdbc.update(startsWith("INSERT INTO bpm_external_application"),any(),any(),any(),any(),any(),any(),any(),any())).thenAnswer(inv->{
            application.putIfAbsent("payload_hash",inv.getArgument(4,String.class)); return 1;
        });
        when(jdbc.update(startsWith("UPDATE bpm_external_application"),anyString(),anyLong())).thenAnswer(inv->{
            application.put("process_id",inv.getArgument(1,String.class)); return 1;
        });
        when(jdbc.update(startsWith("UPDATE bpm_external_student SET user_id"),anyLong(),anyLong())).thenAnswer(inv->{
            student.put("user_id",222L); return 1;
        });
        when(processes.createProcessInstance(eq(222L),any(BpmProcessInstanceCreateReqDTO.class))).thenReturn("pid");
        var result=service.submit(req);
        var firstRead=mockingDetails(jdbc).getInvocations().stream().filter(inv->inv.getMethod().getName().startsWith("query")).findFirst().orElseThrow();
        assertTrue(firstRead.getArgument(0,String.class).contains("FOR UPDATE"),"Identity lock must precede consistent reads");
        assertEquals(222L,result.get("workflowUserId")); assertEquals("pid",result.get("processId"));
        var account=ArgumentCaptor.forClass(UserSaveReqVO.class);verify(users).createExternalStudent(account.capture());
        assertEquals(123L,account.getValue().getDeptId()); assertNull(account.getValue().getPostIds());
        assertEquals(16,account.getValue().getPassword().length());
        var start=ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);verify(processes).createProcessInstance(eq(222L),start.capture());
        assertEquals("zhenzhihui:student:9",start.getValue().getBusinessKey());
        assertEquals("7",start.getValue().getVariables().get("externalUserId"));
        assertEquals("Sample Title",start.getValue().getVariables().get("projectName"));
        assertEquals(result,service.submit(req));
        verify(users,times(1)).createExternalStudent(any());
        verify(processes,times(1)).createProcessInstance(eq(222L),any(BpmProcessInstanceCreateReqDTO.class));
        assertThrows(ResponseStatusException.class,()->service.submit(new StudentBridgeService.Request("7","9","学生","13800000000","学校","修改后的创意")));
        when(users.getUser(222L)).thenReturn(new AdminUserDO().setStatus(1));
        assertThrows(ResponseStatusException.class,()->service.submit(req));
    }
    @Test void optionalTitlePreservesLegacyPayloadHash() throws Exception {
        var mapper=new ObjectMapper();
        var legacy=new StudentBridgeService.Request("7","9","name","13800000000","school","creative");
        String expected="{\"externalUserId\":\"7\",\"applicationId\":\"9\",\"name\":\"name\",\"phone\":\"13800000000\",\"school\":\"school\",\"creative\":\"creative\"}";
        assertEquals(expected,mapper.writeValueAsString(legacy));
        var blank=new StudentBridgeService.Request("7","9","name","13800000000","school","creative","");
        assertEquals(expected,mapper.writeValueAsString(blank));
    }
}
