package cn.iocoder.yudao.module.bpm.service.student;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class StudentPreviewServiceTest {
 @Test void previewRechecksTheSameSourceAclAndDoesNotReturnForeignBytes(){
  var documents=mock(StudentDocumentService.class);var preview=new StudentPreviewService();ReflectionTestUtils.setField(preview,"documents",documents);
  when(documents.openSource(8,12)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  var response=new MockHttpServletResponse();assertEquals(403,assertThrows(ResponseStatusException.class,()->preview.preview(8,12,response)).getStatusCode().value());assertEquals(0,response.getContentAsByteArray().length);
  // A denied request must release the render slot for the next authorized request.
  byte[] data="# 第一幕\n\n剧本文字".getBytes(StandardCharsets.UTF_8);when(documents.openSource(8,13)).thenReturn(new StudentDocumentService.Source("script.md",data.length,"attachment",new ByteArrayInputStream(data)));
  preview.preview(8,13,response);assertArrayEquals(data,response.getContentAsByteArray());assertTrue(response.getContentType().startsWith("text/plain"));assertEquals("no-store",response.getHeader("Cache-Control"));
 }
 @Test void preservesPdfAndRejectsSpoofedOrOversizedPreview(){
  var documents=mock(StudentDocumentService.class);var preview=new StudentPreviewService();ReflectionTestUtils.setField(preview,"documents",documents);
  byte[] pdf="%PDF-1.7\nfixture".getBytes(StandardCharsets.US_ASCII);when(documents.openSource(8,12)).thenReturn(new StudentDocumentService.Source("SCRIPT.PDF",pdf.length,"attachment",new ByteArrayInputStream(pdf)));
  var response=new MockHttpServletResponse();preview.preview(8,12,response);assertArrayEquals(pdf,response.getContentAsByteArray());assertEquals("application/pdf",response.getContentType());
  when(documents.openSource(8,13)).thenReturn(new StudentDocumentService.Source("large.docx",31L<<20,"attachment",new ByteArrayInputStream(new byte[0])));assertEquals(413,assertThrows(ResponseStatusException.class,()->preview.preview(8,13,new MockHttpServletResponse())).getStatusCode().value());
  when(documents.openSource(8,14)).thenReturn(new StudentDocumentService.Source("fake.pdf",4,"attachment",new ByteArrayInputStream(new byte[4])));assertEquals(503,assertThrows(ResponseStatusException.class,()->preview.preview(8,14,new MockHttpServletResponse())).getStatusCode().value());
 }
 @Test void acceptsEverySupportedOfficeExtensionAndBoundsUnknownStreams()throws Exception {
  for(String ext:new String[]{".doc",".docx",".ppt",".pptx"})assertEquals(30<<20,StudentPreviewService.limit(ext));
  assertEquals(415,assertThrows(ResponseStatusException.class,()->StudentPreviewService.limit(".html")).getStatusCode().value());
  assertThrows(ResponseStatusException.class,()->StudentPreviewService.bounded(new ByteArrayInputStream(new byte[11]),-1,10));
  assertThrows(IOException.class,()->StudentPreviewService.bounded(new ByteArrayInputStream(new byte[2]),3,10));
 }
}
