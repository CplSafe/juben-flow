package cn.iocoder.yudao.module.bpm.service.message;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.module.system.api.sms.SmsSendApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.module.bpm.service.message.dto.BpmMessageSendWhenProcessInstanceApproveReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BpmStudentNotificationTest {
 @Test void unboundMobileDoesNotBlockApprovalButExistingMobileIsStillNotified(){
  var service=new BpmMessageServiceImpl();var sms=mock(SmsSendApi.class);var users=mock(AdminUserApi.class);var web=mock(WebProperties.class,RETURNS_DEEP_STUBS);
  ReflectionTestUtils.setField(service,"smsSendApi",sms);ReflectionTestUtils.setField(service,"adminUserApi",users);ReflectionTestUtils.setField(service,"webProperties",web);
  when(web.getAdminUi().getUrl()).thenReturn("https://workflow.example");
  var req=new BpmMessageSendWhenProcessInstanceApproveReqDTO().setStartUserId(222L).setProcessInstanceId("pid").setProcessInstanceName("流程");
  when(users.getUser(222L)).thenReturn(new AdminUserRespDTO().setId(222L));
  service.sendMessageWhenProcessInstanceApprove(req);verifyNoInteractions(sms);
  when(users.getUser(222L)).thenReturn(new AdminUserRespDTO().setId(222L).setMobile("13800000000"));
  service.sendMessageWhenProcessInstanceApprove(req);verify(sms,times(1)).sendSingleSmsToAdmin(argThat(r->r.getUserId().equals(222L)));
 }
}
