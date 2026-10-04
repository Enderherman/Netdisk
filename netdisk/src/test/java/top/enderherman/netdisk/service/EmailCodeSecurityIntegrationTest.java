package top.enderherman.netdisk.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.RedisUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailCodeSecurityIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @MockBean private AccountRateLimiter rateLimiter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmailCodeService service;
    private static final String EMAIL = "code-security@example.test";

    @AfterEach
    void clean() {
        jdbc.update("delete from email_code where email=?", EMAIL);
    }

    @Test
    void purposeMismatchDoesNotConsumeButCorrectPurposeConsumesExactlyOnce() {
        code("12345", 0, Instant.now().minusSeconds(1));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "12345", 1));
        assertEquals(0, status("12345", 0));
        service.checkEmailCode(EMAIL, "12345", 0);
        assertEquals(1, status("12345", 0));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "12345", 0));
    }

    @Test
    void expiredFutureAndMalformedCodesAreRejected() {
        code("12345", 0, Instant.now().minusSeconds(901));
        code("54321", 0, Instant.now().plusSeconds(60));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "12345", 0));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "54321", 0));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "wrong", 0));
        assertThrows(BusinessException.class, () -> service.checkEmailCode(EMAIL, "12345", 99));
    }

    @Test
    void concurrentConsumersCanOnlyUseOneCodeOnce() throws Exception {
        code("12345", 0, Instant.now().minusSeconds(1));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> consume = () -> {
                try {
                    service.checkEmailCode(EMAIL, "12345", 0);
                    return true;
                } catch (BusinessException expected) {
                    return false;
                }
            };
            var results = executor.invokeAll(List.of(consume, consume));
            assertEquals(1, results.stream().filter(result -> {
                try { return result.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, status("12345", 0));
    }

    @Test
    void resendInvalidatesOnlyMatchingPurposeAndPersistsBeforeSimulatedSend() {
        code("12345", 0, Instant.now().minusSeconds(60));
        code("12345", 1, Instant.now().minusSeconds(60));
        when(redisUtils.get(Constants.REDIS_KEY_SYS_SETTING)).thenReturn(new SystemConfig());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        service.sendEmailCode(EMAIL, 0);
        assertEquals(1, jdbc.queryForObject("select count(*) from email_code where email=? and purpose=0 and status=0", Integer.class, EMAIL));
        assertEquals(0, status("12345", 1));
        verify(mailSender).send(message);
    }

    @Test
    void mailFailureRollsBackBothNewCodeAndOldCodeInvalidation() {
        code("12345", 0, Instant.now().minusSeconds(60));
        when(redisUtils.get(Constants.REDIS_KEY_SYS_SETTING)).thenReturn(new SystemConfig());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("simulated failure")).when(mailSender).send(message);
        assertThrows(BusinessException.class, () -> service.sendEmailCode(EMAIL, 0));
        assertEquals(0, status("12345", 0));
        assertEquals(1, jdbc.queryForObject("select count(*) from email_code where email=?", Integer.class, EMAIL));
    }

    @Test
    void rateLimitedSendDoesNotCreateOrSendCode() {
        doThrow(new BusinessException("too frequent")).when(rateLimiter)
                .requireAllowed(eq("email-cooldown"), eq(EMAIL), eq(1), any());
        assertThrows(BusinessException.class, () -> service.sendEmailCode(EMAIL, 0));
        assertEquals(0, jdbc.queryForObject("select count(*) from email_code where email=?", Integer.class, EMAIL));
        verifyNoInteractions(mailSender);
    }

    private void code(String code, int purpose, Instant time) {
        jdbc.update("insert into email_code(email,code,create_time,status,purpose) values (?,?,?,0,?)", EMAIL, code, Timestamp.from(time), purpose);
    }

    private int status(String code, int purpose) {
        return jdbc.queryForObject("select status from email_code where email=? and code=? and purpose=?", Integer.class, EMAIL, code, purpose);
    }
}
