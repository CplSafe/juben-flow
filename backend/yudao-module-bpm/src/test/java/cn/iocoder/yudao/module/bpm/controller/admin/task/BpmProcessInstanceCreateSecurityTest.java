package cn.iocoder.yudao.module.bpm.controller.admin.task;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCreateReqVO;
import cn.iocoder.yudao.module.bpm.service.task.BpmProcessInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BpmProcessInstanceCreateSecurityTest {
    public static class ReadOnlyPermissions {
        public boolean hasPermission(String permission) { return "bpm:process-instance:query".equals(permission); }
    }

    @Test void detailReadPermissionCannotStartAProcess() {
        var service = mock(BpmProcessInstanceService.class);
        when(service.createProcessInstance(anyLong(), any(BpmProcessInstanceCreateReqVO.class))).thenReturn("would-start");
        var target = new BpmProcessInstanceController();
        ReflectionTestUtils.setField(target, "processInstanceService", service);
        try (var context = new StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("ss", new ReadOnlyPermissions());
            var expressions = new DefaultMethodSecurityExpressionHandler();
            expressions.setApplicationContext(context);
            var manager = new PreAuthorizeAuthorizationManager();
            manager.setExpressionHandler(expressions);
            var factory = new ProxyFactory(target);
            factory.addAdvice(AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager));
            var secured = (BpmProcessInstanceController) factory.getProxy();
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new LoginUser().setId(143L), null, "ROLE_USER"));
            var request = new BpmProcessInstanceCreateReqVO();
            request.setProcessDefinitionId("permission-check-only");
            assertThrows(AccessDeniedException.class, () -> secured.createProcessInstance(request));
            verify(service, never()).createProcessInstance(anyLong(), any(BpmProcessInstanceCreateReqVO.class));
            assertEquals(0, secured.getProcessInstance("read-only-detail").getCode());
            verify(service).getHistoricProcessInstance("read-only-detail");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
