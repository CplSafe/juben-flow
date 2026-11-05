package cn.iocoder.yudao.module.bpm.service.student;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Reviewer downloads remain permission-checked in Java; bridge signatures never reach the browser. */
@Service
public class StudentDocumentService {
    @Resource private JdbcTemplate jdbc;
    @Resource private ObjectMapper mapper;
    @Resource private org.flowable.engine.HistoryService historyService;
    @Resource private cn.iocoder.yudao.module.system.api.permission.PermissionApi permissions;
    public void authorize(long recordId) {
        var rows=jdbc.queryForList("SELECT process_id,user_id FROM bpm_external_application WHERE id=?",recordId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Long user=cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(permissions.hasAnyPermissions(user,"bpm:student-application:query") || user.longValue()==((Number)rows.get(0).get("user_id")).longValue())return;
        String pid=(String)rows.get(0).get("process_id");
        if(pid==null || historyService.createHistoricTaskInstanceQuery().processInstanceId(pid).taskAssignee(String.valueOf(user)).count()==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public Long accessibleRecord(String processId) {
        var rows=jdbc.queryForList("SELECT id FROM bpm_external_application WHERE process_id=?",processId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long id=((Number)rows.get(0).get("id")).longValue();authorize(id);return id;
    }
    @Value("${yudao.student-bridge.secret:${ZZH_WORKFLOW_BRIDGE_SECRET:}}") private String secret;
    @Value("${yudao.student-bridge.business-url:${ZZH_STUDENT_BUSINESS_URL:http://host.docker.internal:9000}}") private String businessUrl;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    private com.fasterxml.jackson.databind.JsonNode findFile(Object raw,long fileId) {
        if(raw==null)return null;
        try {for(var file:mapper.readTree(raw.toString()).path("attachments")) if(file.path("file_id").isIntegralNumber() && file.path("file_id").canConvertToLong() && file.path("file_id").asLong()==fileId)return file;return null;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Attachment snapshot unavailable");}
    }
    boolean containsFile(Object raw,long fileId) {return findFile(raw,fileId)!=null;}
    record Source(String name,long size,String disposition,InputStream body) implements AutoCloseable {
        public void close() throws java.io.IOException {body.close();}
    }
    Source openSource(long applicationRecordId,long fileId) {
        authorize(applicationRecordId);
        var rows=jdbc.queryForList("SELECT id,application_id,data_json FROM bpm_external_application WHERE id=?",applicationRecordId);
        if(rows.isEmpty() || fileId<=0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var app=rows.get(0);var metadata=findFile(app.get("data_json"),fileId);
        if(metadata==null)for(var row:jdbc.queryForList("SELECT data_json FROM bpm_external_submission WHERE application_ref=? AND data_json IS NOT NULL",applicationRecordId)){metadata=findFile(row.get("data_json"),fileId);if(metadata!=null)break;}
        if(metadata==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(secret.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Attachment integration not configured");
        try {
            URI base=URI.create(businessUrl);
            if(!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)throw new IllegalArgumentException();
            String path="/api/internal/student-files/"+fileId+"/download";
            byte[] body=mapper.writeValueAsBytes(Map.of("applicationId",Long.parseLong(app.get("application_id").toString())));
            String timestamp=String.valueOf(Instant.now().getEpochSecond());byte[] random=new byte[16];new java.security.SecureRandom().nextBytes(random);String nonce=HexFormat.of().formatHex(random);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+path+"\n"+digest).getBytes(StandardCharsets.UTF_8)));
            var request=HttpRequest.newBuilder(URI.create(businessUrl.replaceAll("/$","")+path)).timeout(Duration.ofMinutes(5))
                .header("Content-Type","application/json").header("X-Bridge-Timestamp",timestamp).header("X-Bridge-Nonce",nonce).header("X-Bridge-Signature",signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var upstream=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            long size=upstream.headers().firstValueAsLong("Content-Length").orElse(-1);
            if(upstream.statusCode()!=200 || size<1 || size>(500L<<20)){upstream.body().close();throw new ResponseStatusException(upstream.statusCode()==404?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE,"Attachment temporarily unavailable");}
            return new Source(metadata.path("file_name").asText("document"),size,upstream.headers().firstValue("Content-Disposition").orElse("attachment"),upstream.body());
        }catch(ResponseStatusException e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Attachment temporarily unavailable");}
    }
    public void download(long applicationRecordId,long fileId,HttpServletResponse response) {
        try(Source source=openSource(applicationRecordId,fileId)) {
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentType("application/octet-stream");response.setContentLengthLong(source.size());response.setHeader("Content-Disposition",source.disposition());
            byte[] buffer=new byte[64*1024];long remaining=source.size();while(remaining>0){int n=source.body().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.IOException("Incomplete attachment");response.getOutputStream().write(buffer,0,n);remaining-=n;}
        }catch(ResponseStatusException e){throw e;}
        catch(java.io.IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Attachment temporarily unavailable");}
    }
}
