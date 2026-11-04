package cn.iocoder.yudao.module.bpm.controller.admin.student;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.service.student.StudentBridgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/bpm/student-bridge")
@Tag(name="管理后台 - 学生与创意申请")
public class StudentBridgeController {
    @Resource private StudentBridgeService service;
    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @ExceptionHandler(ResponseStatusException.class)
    public CommonResult<Object> bridgeError(ResponseStatusException error, HttpServletResponse response) {
        response.setStatus(error.getStatusCode().value());
        return CommonResult.error(error.getStatusCode().value(), error.getReason() == null ? "Request rejected" : error.getReason());
    }

    private StudentBridgeService.Request request(String path,String body,String timestamp,String nonce,String signature,boolean submit) {
        service.authenticate(path,body,timestamp,nonce,signature);
        return service.parse(body,submit);
    }
    @PostMapping("/submit") @PermitAll
    @Operation(summary="受信后端签名提交；自动同步学生并以本人发起SIMPLE流程")
    public CommonResult<Map<String,Object>> submit(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        return success(service.submit(request("/bpm/student-bridge/submit",body,timestamp,nonce,signature,true)));
    }
    @PostMapping("/progress") @PermitAll
    @Operation(summary="受信后端按学生与申请归属查询进度")
    public CommonResult<Map<String,Object>> progress(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        return success(service.progress(request("/bpm/student-bridge/progress",body,timestamp,nonce,signature,false)));
    }
    @GetMapping("/students") @PreAuthorize("@ss.hasPermission('bpm:student:query')")
    @Operation(summary="学生列表（每页50条）")
    public CommonResult<List<Map<String,Object>>> students(@RequestParam(defaultValue="0") int offset) { return success(service.students(offset)); }
    @PutMapping("/student-status") @PreAuthorize("@ss.hasPermission('bpm:student:update')")
    @Operation(summary="启用或停用同步学生")
    public CommonResult<Boolean> status(@RequestParam long id,@RequestParam int status) { service.setStudentStatus(id,status); return success(true); }
    @GetMapping("/applications") @PreAuthorize("@ss.hasPermission('bpm:student-application:query')")
    @Operation(summary="创意申请及关联流程列表（每页50条）")
    public CommonResult<List<Map<String,Object>>> applications(@RequestParam(defaultValue="0") int offset) { return success(service.applications(offset)); }
}
