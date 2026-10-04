package top.enderherman.netdisk;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.service.FileContentService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileContentFailureTest {
    @TempDir Path storage;

    @Test
    void committedOutputFailureIsPropagatedInsteadOfSilentlySucceeding() throws Exception {
        FileContentService service = service();
        MockHttpServletResponse response = failingResponse(); response.setCommitted(true);
        assertThrows(UncheckedIOException.class, () -> service.sendPath(new MockHttpServletRequest("GET", "/file/content/file"), response,
                "document.bin", "document.bin", true));
        Files.delete(storage.resolve("file/document.bin"));
    }

    @Test
    void uncommittedFailureClearsDownloadHeadersForNormalJsonError() throws Exception {
        FileContentService service = service();
        MockHttpServletResponse response = failingResponse();
        BusinessException error = assertThrows(BusinessException.class, () -> service.sendPath(new MockHttpServletRequest("GET", "/file/content/file"), response,
                "document.bin", "document.bin", true));
        assertEquals(500, error.getCode());
        assertNull(response.getHeader("Content-Length"));
        assertNull(response.getHeader("Content-Disposition"));
    }

    private FileContentService service() throws Exception {
        Files.createDirectories(storage.resolve("file")); Files.write(storage.resolve("file/document.bin"), new byte[1024]);
        AppConfig config = new AppConfig(); config.setProjectFolder(storage.toString());
        FileContentService service = new FileContentService(); ReflectionTestUtils.setField(service, "appConfig", config);
        return service;
    }
    private MockHttpServletResponse failingResponse() {
        return new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) { }
                    @Override public void write(int value) throws IOException { throw new IOException("simulated disconnected client"); }
                };
            }
        };
    }
}
