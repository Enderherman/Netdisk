package top.enderherman.netdisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.service.FileContentService;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileContentServiceTest {
    @TempDir Path root;
    FileContentService service = new FileContentService();
    FileInfo file;
    MockHttpServletRequest request;
    MockHttpServletResponse response;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(root.resolve("file/202610"));
        Files.writeString(root.resolve("file/202610/content.txt"), "0123456789");
        AppConfig config = new AppConfig(); config.setProjectFolder(root.toString());
        ReflectionTestUtils.setField(service, "appConfig", config);
        file = new FileInfo(); file.setDelFlag(2); file.setStatus(2); file.setFolderType(0);
        file.setFilePath("202610/content.txt"); file.setFileName("中文 report.txt");
        request = new MockHttpServletRequest("GET", "/file/content/file");
        response = new MockHttpServletResponse();
    }

    @Test void streamsWholeFileWithSafeUnicodeHeadersWithoutUserAgent() throws Exception {
        service.send(request, response, file, true);
        assertEquals("0123456789", response.getContentAsString());
        assertEquals(10, response.getContentLengthLong());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertTrue(response.getHeader("Content-Disposition").contains("filename*=UTF-8''%E4%B8%AD%E6%96%87%20report.txt"));
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
    }

    @ParameterizedTest
    @CsvSource({"bytes=2-5,2345,bytes 2-5/10", "bytes=7-,789,bytes 7-9/10", "bytes=-3,789,bytes 7-9/10", "bytes=0-100,0123456789,bytes 0-9/10", "bytes=-100,0123456789,bytes 0-9/10"})
    void rangeReturnsExactBytes(String range, String expected, String contentRange) throws Exception {
        request.addHeader("Range", range);
        service.send(request, response, file, false);
        assertEquals(206, response.getStatus()); assertEquals(expected, response.getContentAsString());
        assertEquals(contentRange, response.getHeader("Content-Range"));
        assertEquals(expected.length(), response.getContentLengthLong());
    }

    @ParameterizedTest
    @ValueSource(strings={"bytes=10-", "bytes=9-2", "bytes=-0", "bytes=-", "bytes=a-b", "bytes=0-1,3-4", "bytes=999999999999999999999-"})
    void invalidRangesAreExplicitAndEmpty(String range) {
        request.addHeader("Range", range);
        service.send(request, response, file, false);
        assertEquals(416, response.getStatus()); assertEquals("bytes */10", response.getHeader("Content-Range"));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"../private.txt", "/etc/passwd", "C:/Windows/win.ini", "202610/../../other", "202610\\content.txt", "202610/missing.txt", "202610/content.txt:stream"})
    void pathCannotEscapeStorageOrReadMissingFile(String path) {
        assertThrows(BusinessException.class, () -> service.resolve(path));
    }

    @Test void emptyFileAndHeadDoNotEmitBody() throws Exception {
        request.setMethod("HEAD");
        service.send(request, response, file, false);
        assertEquals(10, response.getContentLengthLong()); assertEquals(0, response.getContentAsByteArray().length);
        request.setMethod("GET"); response = new MockHttpServletResponse();
        Files.writeString(root.resolve("file/202610/content.txt"), "");
        service.send(request, response, file, false);
        assertEquals(200, response.getStatus()); assertEquals(0, response.getContentLengthLong());
        request.addHeader("Range", "bytes=0-"); response = new MockHttpServletResponse();
        service.send(request, response, file, false);
        assertEquals(416, response.getStatus());
    }

    @Test void activeHtmlAndSvgContentAreAlwaysDownloaded() throws Exception {
        for (String extension : new String[]{"html", "svg", "exe"}) {
            Files.writeString(root.resolve("file/202610/uploaded." + extension), "<script>alert(1)</script>");
            file.setFilePath("202610/uploaded." + extension);
            file.setFileName("pretend-safe.txt"); response = new MockHttpServletResponse();
            service.send(request, response, file, false);
            assertEquals("application/octet-stream", response.getContentType());
            assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"));
        }
    }

    @Test void renamedVideoKeepsOriginalContentType() throws Exception {
        Files.write(root.resolve("file/202610/video.mp4"), new byte[]{1, 2, 3});
        file.setFilePath("202610/video.mp4"); file.setFileName("renamed.txt");
        service.send(request, response, file, false);
        assertEquals("video/mp4", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").contains("renamed.txt"));
    }

    @Test void headIgnoresRangeAndIfRangeProtectsChangedRepresentation() throws Exception {
        request.setMethod("HEAD"); request.addHeader("Range", "bytes=1-2");
        service.send(request, response, file, false);
        assertEquals(200, response.getStatus()); assertEquals(10L, response.getContentLengthLong());
        String etag = response.getHeader("ETag");
        request.setMethod("GET"); request.addHeader("If-Range", etag);
        response = new MockHttpServletResponse(); service.send(request, response, file, false);
        assertEquals(206, response.getStatus()); assertEquals("12", response.getContentAsString());
        request.removeHeader("If-Range"); request.addHeader("If-Range", "\"outdated\"");
        response = new MockHttpServletResponse(); service.send(request, response, file, false);
        assertEquals(200, response.getStatus()); assertEquals("0123456789", response.getContentAsString());
    }

    @Test void largeSparseFileUsesLongOffsetsWithoutReadingWholeFile() throws Exception {
        long size = 3L * 1024 * 1024 * 1024;
        try (var large = new java.io.RandomAccessFile(root.resolve("file/202610/content.txt").toFile(), "rw")) {
            large.setLength(size); large.seek(size - 3); large.write(new byte[]{7, 8, 9});
        }
        request.addHeader("Range", "bytes=-3");
        service.send(request, response, file, true);
        assertEquals(206, response.getStatus()); assertArrayEquals(new byte[]{7, 8, 9}, response.getContentAsByteArray());
        assertEquals("bytes " + (size - 3) + "-" + (size - 1) + "/" + size, response.getHeader("Content-Range"));
        assertEquals(3L, response.getContentLengthLong());
    }

    @Test void recycledProcessingOrDirectoryCannotBeServed() {
        assertThrows(BusinessException.class, () -> service.requireUsable(null));
        file.setDelFlag(1); assertThrows(BusinessException.class, () -> service.send(request, response, file, false));
        file.setDelFlag(2); file.setStatus(0); assertThrows(BusinessException.class, () -> service.send(request, response, file, false));
        file.setStatus(2); file.setFolderType(1); assertThrows(BusinessException.class, () -> service.send(request, response, file, false));
    }
}
