package cn.iocoder.yudao.module.bpm.service.student;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Private, bounded document rendering. Every request rechecks the original attachment ACL. */
@Service
public class StudentPreviewService {
 @Resource private StudentDocumentService documents;
 @Value("${yudao.student-bridge.preview-url:${ZZH_STUDENT_PREVIEW_URL:http://student-preview:3000}}") private String converterUrl;
 private static final java.util.concurrent.ScheduledThreadPoolExecutor READ_TIMEOUT=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"student-preview-read-timeout");t.setDaemon(true);return t;});
 static {READ_TIMEOUT.setRemoveOnCancelPolicy(true);}
 private final Semaphore slots=new Semaphore(1);
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 static String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot).toLowerCase(Locale.ROOT);}
 static int limit(String ext){return switch(ext){case ".txt",".md"->5<<20;case ".pdf"->50<<20;case ".doc",".docx",".ppt",".pptx"->30<<20;default->throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"此格式暂不支持预览，请下载原文件");};}
 static byte[] bounded(InputStream in,long expected,int max)throws IOException {
  if(expected>max)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"文件超过在线预览大小限制，请下载原文件");
  var deadline=READ_TIMEOUT.schedule(()->{try{in.close();}catch(IOException ignored){}},90,java.util.concurrent.TimeUnit.SECONDS);
  byte[] data;try{data=in.readNBytes(max+1);}finally{deadline.cancel(false);}if(data.length>max)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"预览文件过大，请下载原文件");
  if(expected>=0 && data.length!=expected)throw new IOException("Incomplete document");return data;
 }
 public void preview(long application,long file,HttpServletResponse response){
  // Acquisition is non-blocking: a large document cannot create an unbounded conversion queue.
  if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"预览正在生成，请稍后重试");
  try(StudentDocumentService.Source source=documents.openSource(application,file)){
   String ext=extension(source.name());int max=limit(ext);byte[] data=bounded(source.body(),source.size(),max);
   String type="application/pdf";
   if(Set.of(".txt",".md").contains(ext))type="text/plain;charset=UTF-8";
   else if(!".pdf".equals(ext))data=convert(data,ext);
   if("application/pdf".equals(type) && (data.length<5 || !new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-")))throw new IOException("Invalid preview PDF");
   response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Disposition","inline");response.setContentType(type);response.setContentLength(data.length);response.getOutputStream().write(data);
  }catch(ResponseStatusException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"文档预览生成失败，请重试或下载原文件");}finally{slots.release();}
 }
 private byte[] convert(byte[] data,String extension)throws Exception {
  URI base=URI.create(converterUrl);if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)throw new IOException("Invalid converter configuration");
  String boundary="preview-"+UUID.randomUUID();byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document"+extension+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
  var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofByteArray(data),HttpRequest.BodyPublishers.ofByteArray(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII)));
  var request=HttpRequest.newBuilder(URI.create(converterUrl.replaceAll("/$","")+"/forms/libreoffice/convert")).timeout(Duration.ofSeconds(75)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
  var result=http.send(request,HttpResponse.BodyHandlers.ofInputStream());try(InputStream in=result.body()){if(result.statusCode()!=200)throw new IOException("Conversion failed");return bounded(in,-1,50<<20);}
 }
}
