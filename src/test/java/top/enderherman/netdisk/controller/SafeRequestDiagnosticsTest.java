package top.enderherman.netdisk.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.util.pattern.PathPatternParser;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.aspect.GlobalOperationAspect;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafeRequestDiagnosticsTest {
    private static final String SECRET = "sensitive-unit-value-7f31";
    private final AGlobalExceptionHandlerController handler = new AGlobalExceptionHandlerController();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final List<Logger> loggers = new ArrayList<>();

    @BeforeEach
    void capture() {
        events.start();
        for (Class<?> type : List.of(AGlobalExceptionHandlerController.class, GlobalOperationAspect.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.addAppender(events); loggers.add(logger);
        }
    }

    @AfterEach
    void detach() {
        loggers.forEach(logger -> logger.detachAppender(events)); events.stop();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/file/download/{code}", "/file/downloadZip/{code}", "/showShare/download/{code}", "/admin/download/{code}"})
    void allFourDownloadRoutesLogOnlyTemplateAndPreserveErrorEnvelope(String template) throws Exception {
        MockHttpServletRequest request = request("GET", "/api" + template.replace("{code}", SECRET));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        BaseResponse<?> result = invoke(new BusinessException(902, "分享连接不存在，或者已失效"), request);
        assertEquals(902, result.getCode()); assertEquals("分享连接不存在，或者已失效", result.getMessage());
        assertEquals("error", result.getStatus());
        String log = captured();
        assertTrue(log.contains("method=GET route=" + template)); assertFalse(log.contains(SECRET));
    }

    @Test
    void qqCallbackNeverLogsCodeStateBodyOrProviderExceptionText() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/qqlogin/callback");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, new PathPatternParser().parse("/qqlogin/callback"));
        request.setQueryString("code=" + SECRET + "&state=" + SECRET);
        request.addParameter("password", SECRET); request.addParameter("code", SECRET);
        request.setContent(("client_secret=" + SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        IOException failure = ordinaryFailure();
        failure.addSuppressed(new IllegalStateException("access_token=" + SECRET));
        BaseResponse<?> result = invoke(failure, request);
        assertEquals(500, result.getCode()); assertEquals("服务器返回错误，请联系管理员", result.getMessage());
        String log = captured();
        assertTrue(log.contains("route=/qqlogin/callback")); assertFalse(log.contains(SECRET));
        assertFalse(log.contains("client_secret")); assertFalse(log.contains("access_token"));
        assertFalse(log.contains("password=")); assertFalse(log.contains("state="));
    }

    @Test
    void unknownRouteDoesNotFallBackToUrlPathHeadersOrNoHandlerMessage() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/unknown/" + SECRET);
        HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", "Bearer " + SECRET);
        BaseResponse<?> result = invoke(new NoHandlerFoundException("GET", request.getRequestURI(), headers), request);
        assertEquals(404, result.getCode()); assertEquals("请求地址不存在", result.getMessage());
        String log = captured();
        assertTrue(log.contains("route=[unmapped]")); assertTrue(log.contains("type=org.springframework.web.servlet.NoHandlerFoundException"));
        assertFalse(log.contains(SECRET)); assertFalse(log.contains("unknown/"));
    }

    @Test
    void ordinaryFaultKeepsTypeCauseAndUsefulCodePositionWithoutFileNameOrMessage() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/file/loadDataList");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/file/loadDataList");
        BaseResponse<?> result = invoke(ordinaryFailure(), request);
        assertEquals(500, result.getCode());
        String log = captured();
        assertTrue(log.contains("phase=REQUEST")); assertTrue(log.contains("type=java.io.IOException"));
        assertTrue(log.contains("java.lang.IllegalStateException"));
        assertTrue(log.contains("top.enderherman.netdisk.service.FileContentService.send:123"));
        assertFalse(log.contains(SECRET)); assertFalse(log.contains(".java"));
    }

    @Test
    void unsupportedMethodStillReturns405WithoutLoggingSuppliedMethodOrValues() throws Exception {
        MockHttpServletRequest request = request("BAD-" + SECRET, "/api/file/download/" + SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        BaseResponse<?> result = (BaseResponse<?>) handler.handleException(new HttpRequestMethodNotSupportedException(request.getMethod()), request, response);
        assertEquals(405, result.getCode()); assertEquals(405, response.getStatus());
        assertEquals("请求方法不支持，请使用指定的方法", result.getMessage());
        assertTrue(captured().contains("method=OTHER route=[unmapped]")); assertFalse(captured().contains(SECRET));
    }

    @Test
    void invalidPatternAndArbitraryAttributeCannotInjectIntoLogs() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/" + SECRET);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/qqlogin/callback?code=" + SECRET + "\nforged-log");
        invoke(ordinaryFailure(), request);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, new Object() {
            @Override public String toString() { fail("Untrusted attribute must not be stringified"); return SECRET; }
        });
        invoke(ordinaryFailure(), request);
        assertTrue(captured().contains("route=[unmapped]")); assertFalse(captured().contains(SECRET));
        assertFalse(captured().contains("forged-log"));
    }

    @Test
    void committedResponseStillPropagatesOriginalFailureWithoutAnotherLogOrBody() {
        MockHttpServletResponse response = new MockHttpServletResponse(); response.setCommitted(true);
        IOException failure = ordinaryFailure();
        assertSame(failure, assertThrows(IOException.class, () -> handler.handleException(failure, request("GET", "/" + SECRET), response)));
        assertTrue(events.list.isEmpty()); assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void interceptorFaultIsSanitizedBeforeItReachesGlobalHandlerAndKeeps500Conversion() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request("POST", "/api/" + SECRET)));
        JoinPoint point = mock(JoinPoint.class);
        when(point.getTarget()).thenThrow(new IllegalStateException("password=" + SECRET, ordinaryFailure()));
        BusinessException failure = assertThrows(BusinessException.class, () -> new GlobalOperationAspect().interceptor(point));
        assertEquals(500, failure.getCode());
        String log = captured(); assertTrue(log.contains("phase=INTERCEPTOR"));
        assertTrue(log.contains("java.lang.IllegalStateException")); assertFalse(log.contains(SECRET));
    }

    @Test
    void parameterValidationBusinessFailureKeeps600AndBothLogsAreSafe() {
        JoinPoint point = parameterPoint(new Input());
        BusinessException failure = assertThrows(BusinessException.class, () -> new GlobalOperationAspect().interceptor(point));
        assertEquals(600, failure.getCode());
        assertEquals(2, events.list.size()); assertTrue(captured().contains("phase=PARAMETER_VALIDATION"));
        assertTrue(captured().contains("phase=INTERCEPTOR")); assertFalse(captured().contains(SECRET));
    }

    @Test
    void parameterReflectionFailureKeeps600AndDoesNotLogRawThrowable() {
        JoinPoint point = parameterPoint(null);
        BusinessException failure = assertThrows(BusinessException.class, () -> new GlobalOperationAspect().interceptor(point));
        assertEquals(600, failure.getCode());
        assertEquals(2, events.list.size()); assertTrue(captured().contains("phase=PARAMETER_VALIDATION"));
        assertFalse(captured().contains(SECRET));
    }

    private JoinPoint parameterPoint(Input input) {
        MockHttpServletRequest request = request("POST", "/api/" + SECRET);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/register");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        JoinPoint point = mock(JoinPoint.class); MethodSignature signature = mock(MethodSignature.class);
        when(point.getTarget()).thenReturn(new ParameterController()); when(point.getArgs()).thenReturn(new Object[]{input});
        when(point.getSignature()).thenReturn(signature); when(signature.getName()).thenReturn("register");
        when(signature.getParameterTypes()).thenReturn(new Class<?>[]{Input.class});
        return point;
    }

    public static class ParameterController {
        @GlobalInterceptor(checkLogin = false, checkParams = true)
        public void register(@VerifyParam Input input) { }
    }
    public static class Input {
        @VerifyParam(required = true) private String password;
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName(SECRET + ".example.test"); request.setQueryString("password=" + SECRET);
        request.addHeader("Authorization", "Bearer " + SECRET); request.addHeader("Cookie", "JSESSIONID=" + SECRET);
        request.addHeader("Referer", "https://example.test/?code=" + SECRET);
        return request;
    }
    private IOException ordinaryFailure() {
        IOException failure = new IOException("GET https://example.test/download/" + SECRET + "?code=" + SECRET,
                new IllegalStateException("password=" + SECRET));
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "top.enderherman.netdisk.service.FileContentService", "send", SECRET + ".java", 123)});
        return failure;
    }
    private BaseResponse<?> invoke(Exception failure, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        BaseResponse<?> result = (BaseResponse<?>) handler.handleException(failure, request, response);
        assertEquals(200, response.getStatus()); return result;
    }
    private String captured() {
        assertFalse(events.list.isEmpty());
        for (ILoggingEvent event : events.list) {
            assertNull(event.getThrowableProxy(), "Logback must not receive the original throwable");
            for (Object argument : event.getArgumentArray()) assertFalse(argument instanceof Throwable);
        }
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
    }
}
