package top.enderherman.netdisk.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.utils.RedisUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SafeRequestDiagnosticsHttpTest {
    private static final String SECRET = "secretPathAndQueryValue73";
    @MockBean private RedisUtils<Object> redis;
    @MockBean private JavaMailSender mail;
    @Autowired private MockMvc mvc;
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void captureAllLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        events.start(); root.addAppender(events);
    }
    @AfterEach
    void detach() { root.detachAppender(events); events.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"/file/download/", "/file/downloadZip/", "/showShare/download/", "/admin/download/"})
    void actualDownloadFailureNeverLogsTokenFromAnyRequestLogger(String prefix) throws Exception {
        mvc.perform(get(prefix + SECRET).queryParam("password", SECRET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("error"));
        assertSafeLogs();
        assertTrue(messages().contains("route=" + prefix + "{code}"));
    }

    @Test
    void actualUnknownPathUsesSafe404DiagnosticWithoutSpringRawPathWarning() throws Exception {
        mvc.perform(get("/unknown/" + SECRET).queryParam("code", SECRET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("请求地址不存在"));
        assertSafeLogs(); assertTrue(messages().contains("route=[unmapped]"));
        assertTrue(messages().contains("type=org.springframework.web.servlet.NoHandlerFoundException"));
    }

    @Test
    void actualQqPostFailureKeepsJsonAndDoesNotLogCredentials() throws Exception {
        mvc.perform(post("/qqlogin/callback").param("code", SECRET).param("state", SECRET))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(600));
        assertSafeLogs(); assertTrue(messages().contains("route=/qqlogin/callback"));
    }

    @Test
    void actualQqGetFailureKeepsFixedRedirectAndDoesNotLogCredentials() throws Exception {
        mvc.perform(get("/qqlogin/callback").queryParam("code", SECRET).queryParam("state", SECRET))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=unavailable"));
        assertFalse(messages().contains(SECRET));
        events.list.forEach(event -> assertNull(event.getThrowableProxy()));
    }

    private void assertSafeLogs() {
        assertFalse(events.list.isEmpty()); assertFalse(messages().contains(SECRET));
        events.list.forEach(event -> assertNull(event.getThrowableProxy(), "Request log must not include exception messages"));
    }
    private String messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
    }
}
