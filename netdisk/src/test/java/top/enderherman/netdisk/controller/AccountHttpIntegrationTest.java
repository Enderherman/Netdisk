package top.enderherman.netdisk.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.service.AccountRateLimiter;
import top.enderherman.netdisk.service.PasswordService;
import top.enderherman.netdisk.service.UserService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AccountHttpIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @MockBean private AccountRateLimiter rateLimiter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private PasswordService passwords;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void seed() {
        jdbc.update("insert into user_info(user_id,nick_name,email,password,status,use_space,total_space) values ('authuser','Auth User','auth@example.test',?,1,8,1048576)", StringUtils.encodingByMd5("Password123"));
        when(redisUtils.get(Constants.REDIS_KEY_SYS_SETTING)).thenReturn(new SystemConfig());
    }

    @Test
    void legacyPasswordLoginMigratesHashRotatesSessionAndHidesSecrets() throws Exception {
        MockHttpSession session = captcha();
        String previousId = session.getId();
        mvc.perform(post("/login").session(session).param("email", "AUTH@Example.test")
                        .param("password", "Password123").param("checkCode", "AbC12"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.isAdmin").value(false))
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.sessionVersion").doesNotExist());
        assertNotEquals(previousId, session.getId());
        String stored = jdbc.queryForObject("select password from user_info where user_id='authuser'", String.class);
        assertTrue(stored.startsWith("pbkdf2-sha256$"));
        assertTrue(passwords.matches("Password123", stored));
        assertNull(session.getAttribute(Constants.CHECK_CODE_KEY));
        mvc.perform(get("/getUserInfo").session(session)).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void badLoginConsumesOnlyTheSubmittedCaptcha() throws Exception {
        MockHttpSession session = captcha();
        session.setAttribute(Constants.CHECK_CODE_KEY_EMAIL, "email-image");
        mvc.perform(post("/login").session(session).param("email", "auth@example.test")
                        .param("password", "Wrong123").param("checkCode", "abc12"))
                .andExpect(jsonPath("$.code").value(600));
        assertNull(session.getAttribute(Constants.CHECK_CODE_KEY));
        assertEquals("email-image", session.getAttribute(Constants.CHECK_CODE_KEY_EMAIL));
        mvc.perform(post("/login").session(session).param("email", "auth@example.test")
                        .param("password", "Password123").param("checkCode", "abc12"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void registerConsumesPurposeBoundCodeAndStoresSecurePassword() throws Exception {
        code("new@example.test", "12345", 0);
        MockHttpSession session = captcha();
        mvc.perform(post("/register").session(session).param("email", "new@example.test").param("nickName", "New User")
                        .param("password", "New password123!").param("checkCode", "abc12").param("emailCode", "12345"))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(session.getAttribute(Constants.CHECK_CODE_KEY));
        assertEquals(1, jdbc.queryForObject("select status from email_code where email='new@example.test'", Integer.class));
        String stored = jdbc.queryForObject("select password from user_info where email='new@example.test'", String.class);
        assertTrue(passwords.matches("New password123!", stored));
        assertFalse(stored.equals("New password123!"));
    }

    @Test
    void registrationRejectsResetPurposeAndWeakPassword() throws Exception {
        code("new@example.test", "12345", 1);
        mvc.perform(post("/register").session(captcha()).param("email", "new@example.test").param("nickName", "New User")
                        .param("password", "Password123").param("checkCode", "abc12").param("emailCode", "12345"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/register").session(captcha()).param("email", "new@example.test").param("nickName", "New User")
                        .param("password", "weak").param("checkCode", "abc12").param("emailCode", "12345"))
                .andExpect(jsonPath("$.code").value(600));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_info where email='new@example.test'", Integer.class));
    }

    @Test
    void resetRequiresResetPurposeAndRevokesExistingSessions() throws Exception {
        MockHttpSession oldSession = authenticated();
        code("auth@example.test", "12345", 0);
        mvc.perform(post("/resetPwd").session(captcha()).param("email", "auth@example.test").param("password", "Changed123")
                        .param("checkCode", "abc12").param("emailCode", "12345"))
                .andExpect(jsonPath("$.code").value(600));
        code("auth@example.test", "54321", 1);
        mvc.perform(post("/resetPwd").session(captcha()).param("email", "auth@example.test").param("password", "Changed123")
                        .param("checkCode", "abc12").param("emailCode", "54321"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1L, jdbc.queryForObject("select session_version from user_info where user_id='authuser'", Long.class));
        mvc.perform(get("/getUserInfo").session(oldSession)).andExpect(jsonPath("$.code").value(901));
        login("auth@example.test", "Changed123");
    }

    @Test
    void changingPasswordNeedsOldPasswordAndRevokesOtherSessions() throws Exception {
        MockHttpSession current = authenticated();
        MockHttpSession other = authenticated();
        mvc.perform(post("/updatePassword").session(current).param("currentPassword", "Wrong123").param("password", "Changed123"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/updatePassword").session(current).param("currentPassword", "Password123").param("password", "Changed123"))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(current.isInvalid());
        mvc.perform(get("/getUserInfo").session(other)).andExpect(jsonPath("$.code").value(901));
        login("auth@example.test", "Changed123");
    }

    @Test
    void disablingPreservesSpaceAndReenablingDoesNotReviveOldSessions() throws Exception {
        MockHttpSession old = authenticated();
        userService.updateUserStatus("authuser", 0);
        assertEquals(8L, jdbc.queryForObject("select use_space from user_info where user_id='authuser'", Long.class));
        mvc.perform(get("/getUserInfo").session(old)).andExpect(jsonPath("$.code").value(901));
        userService.updateUserStatus("authuser", 1);
        mvc.perform(get("/getUserInfo").session(authenticated())).andExpect(jsonPath("$.code").value(901));
        login("auth@example.test", "Password123");
    }

    @Test
    void clientSessionAdminFlagCannotGrantAdminAccess() throws Exception {
        MockHttpSession session = authenticated();
        ((SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY)).setIsAdmin(true);
        mvc.perform(post("/admin/getSysSettings").session(session)).andExpect(jsonPath("$.code").value(404));
        jdbc.update("insert into user_info(user_id,nick_name,email,password,status,use_space,total_space) values ('adminuser','Admin','admin@example.invalid',?,1,0,1048576)", StringUtils.encodingByMd5("Password123"));
        MockHttpSession admin = login("admin@example.invalid", "Password123");
        mvc.perform(post("/admin/getSysSettings").session(admin)).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void sendEmailUsesDedicatedCaptchaAndCreatesPurposeBoundCodeWithoutRealMail() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        MockHttpSession session = captcha();
        session.setAttribute(Constants.CHECK_CODE_KEY_EMAIL, "mail1");
        mvc.perform(post("/sendEmailCode").session(session).param("email", "new@example.test").param("type", "0").param("checkCode", "MAIL1"))
                .andExpect(jsonPath("$.code").value(200));
        assertNull(session.getAttribute(Constants.CHECK_CODE_KEY_EMAIL));
        assertNotNull(session.getAttribute(Constants.CHECK_CODE_KEY));
        assertEquals(0, jdbc.queryForObject("select purpose from email_code where email='new@example.test'", Integer.class));
        verify(mailSender).send(message);
        verify(rateLimiter).requireAllowed(eq("email-cooldown"), eq("new@example.test"), eq(1), any());
        verify(rateLimiter).requireAllowed(eq("email-hour"), eq("new@example.test"), eq(5), any());
    }

    @Test
    void capabilitiesAndQqEndpointsNeverPretendQqLoginSucceeded() throws Exception {
        mvc.perform(get("/accountCapabilities")).andExpect(jsonPath("$.data.qqLoginEnabled").value(false))
                .andExpect(jsonPath("$.data.emailVerificationEnabled").value(true));
        mvc.perform(get("/qqlogin")).andExpect(jsonPath("$.code").value(600));
        mvc.perform(get("/qqlogin/callback").param("code", "ignored").param("state", "ignored"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = authenticated();
        mvc.perform(post("/logout").session(session)).andExpect(jsonPath("$.code").value(200));
        assertTrue(session.isInvalid());
        mvc.perform(get("/getUserInfo")).andExpect(jsonPath("$.code").value(901));
    }

    private void code(String email, String code, int purpose) {
        jdbc.update("insert into email_code(email,code,create_time,status,purpose) values (?,?,?,0,?)",
                email, code, Timestamp.from(Instant.now().minusSeconds(1)), purpose);
    }

    private MockHttpSession captcha() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(Constants.CHECK_CODE_KEY, "abc12");
        return session;
    }

    private MockHttpSession authenticated() {
        MockHttpSession session = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId("authuser");
        user.setIsAdmin(false);
        session.setAttribute(Constants.SESSION_KEY, user);
        return session;
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MockHttpSession session = captcha();
        mvc.perform(post("/login").session(session).param("email", email).param("password", password).param("checkCode", "abc12"))
                .andExpect(jsonPath("$.code").value(200));
        return session;
    }
}
