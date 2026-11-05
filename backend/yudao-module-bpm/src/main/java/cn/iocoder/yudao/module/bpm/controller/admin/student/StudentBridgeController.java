package cn.iocoder.yudao.module.bpm.controller.admin.student;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.service.student.StudentBridgeService;
import cn.iocoder.yudao.module.bpm.service.student.StudentStageService;
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
    @Resource private StudentStageService stages;
    @Resource private cn.iocoder.yudao.module.bpm.service.student.StudentDocumentService documents;
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
    @PostMapping("/creator") @PermitAll
    @Operation(summary="受信业务后端同步创作者资料，不创建流程")
    public CommonResult<Map<String,Object>> creator(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        service.authenticate("/bpm/student-bridge/creator",body,timestamp,nonce,signature);
        return success(service.syncCreator(service.parseCreator(body)));
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
    @PostMapping("/stage-submit") @PermitAll
    @Operation(summary="受信后端提交学生当前阶段，原子保存版本并办理本人任务")
    public CommonResult<Map<String,Object>> stageSubmit(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        service.authenticate("/bpm/student-bridge/stage-submit",body,timestamp,nonce,signature);
        return success(stages.submit(stages.parse(body)));
    }
    @PostMapping("/withdraw") @PermitAll
    @Operation(summary="受信业务后端撤回本人当前内容审核，原子保存撤回记录")
    public CommonResult<Map<String,Object>> withdraw(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        service.authenticate("/bpm/student-bridge/withdraw",body,timestamp,nonce,signature);
        return success(stages.withdraw(stages.parseWithdraw(body)));
    }
    @PostMapping("/summaries") @PermitAll
    @Operation(summary="受信后端批量查询本人申请阶段状态，最多100条")
    public CommonResult<Map<String,Object>> summaries(@RequestBody String body,
            @RequestHeader("X-Bridge-Timestamp") String timestamp,@RequestHeader("X-Bridge-Nonce") String nonce,
            @RequestHeader("X-Bridge-Signature") String signature) {
        service.authenticate("/bpm/student-bridge/summaries",body,timestamp,nonce,signature);
        return success(stages.summaries(stages.parseSummaries(body)));
    }
    @GetMapping("/file-download") @PreAuthorize("isAuthenticated()")
    @Operation(summary="审批人员下载当前申请历史版本中的附件；校验版本归属后代理流式下载")
    public void file(@RequestParam long id,@RequestParam long fileId,HttpServletResponse response) {documents.download(id,fileId,response);}
    @GetMapping("/process-detail") @PreAuthorize("isAuthenticated()")
    @Operation(summary="当前流程参与人员查询结构化提交与附件；非参与者不可枚举其他申请")
    public CommonResult<Map<String,Object>> processDetail(@RequestParam String processId) {return success(stages.adminDetail(documents.accessibleRecord(processId)));}
    @GetMapping("/application-detail") @PreAuthorize("@ss.hasPermission('bpm:student-application:query')")
    @Operation(summary="申请、历次提交版本及审批记录")
    public CommonResult<Map<String,Object>> detail(@RequestParam long id) { return success(stages.adminDetail(id)); }
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
