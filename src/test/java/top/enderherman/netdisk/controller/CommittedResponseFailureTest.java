package top.enderherman.netdisk.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CommittedResponseFailureTest {
    @Test
    void committedStreamFailureEscapesWithoutAppendingJson() {
        MockHttpServletResponse response = new MockHttpServletResponse(); response.setCommitted(true);
        IOException original = new IOException("stream interrupted");
        IOException actual = assertThrows(IOException.class, () -> new AGlobalExceptionHandlerController().handleException(
                original, new MockHttpServletRequest("GET", "/file/downloadZip/code"), response));
        assertSame(original, actual);
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void uncommittedBusinessErrorStillUsesExistingEnvelope() throws Exception {
        Object result = new AGlobalExceptionHandlerController().handleException(new BusinessException(600, "invalid request"),
                new MockHttpServletRequest("GET", "/file/downloadZip/code"), new MockHttpServletResponse());
        assertInstanceOf(BaseResponse.class, result);
        assertEquals(600, ((BaseResponse<?>) result).getCode());
    }
}
