package top.enderherman.netdisk.service.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:qq-login-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qq.enabled=true", "qq.app.id=123456", "qq.app.key=unit-app-secret-not-real",
        "qq.url.redirect=https://cloud.example.test/api/qqlogin/callback"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class QQOAuthHttpIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final QQIdentity IDENTITY = new QQIdentity("123456", QQResponseParserTest.OPEN_ID, "测试QQ用户", "https://thirdqq.qlogo.cn/avatar/100");
    @MockBean private QQOAuthGateway gateway;
    @MockBean private RedisUtils<Object> redis;
    @MockBean private JavaMailSender mail;
    @MockBean private StringRedisTemplate rateRedis;
    @Autowired private QQOAuthService oauth;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    private SystemConfig systemConfig;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() {
        jdbc.update("delete from file_info"); jdbc.update("delete from user_info");
        systemConfig = new SystemConfig(); systemConfig.setUserInitUseSpace(64);
        when(redis.get(Constants.REDIS_KEY_SYS_SETTING)).thenReturn(systemConfig);
        when(gateway.authenticate(anyString())).thenReturn(IDENTITY);
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        when(rateRedis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            return counters.computeIfAbsent(keys.get(0), ignored -> new AtomicLong()).incrementAndGet();
        });
        ReflectionTestUtils.setField(oauth, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void capabilityAdvertisesOnlyConfiguredFeatureAndGetDoesNotStartFlow() throws Exception {
        mvc.perform(get("/accountCapabilities")).andExpect(jsonPath("$.data.qqLoginEnabled").value(true));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/qqlogin").session(session)).andExpect(jsonPath("$.code").value(600));
        assertNull(session.getAttribute(QQOAuthService.STATE_KEY));
        verifyNoInteractions(gateway);
    }

    @Test
    void getCallbackRotatesSessionAndRedirectsWithoutCodeOrTokenInFrontend() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = begin(session, "/s/share1?path=root");
        String originalSessionId = session.getId();
        var response = mvc.perform(get("/qqlogin/callback").session(session).param("code", "unit-auth-code").param("state", state))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/s/share1?path=root"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse();
        assertNotEquals(originalSessionId, session.getId());
        assertEquals(0, response.getContentAsByteArray().length);
        SessionWebUserDto user = (SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY);
        assertNotNull(user); assertEquals(0L, user.getSessionVersion()); assertFalse(user.getIsAdmin());
        assertNull(session.getAttribute(QQOAuthService.STATE_KEY));
        Map<String, Object> stored = jdbc.queryForMap("select * from user_info where user_id=?", user.getUserId());
        assertNull(stored.get("email")); assertNull(stored.get("password"));
        assertEquals(64 * Constants.MB, stored.get("total_space"));
        assertEquals(QQResponseParserTest.OPEN_ID, stored.get("qq_open_id"));
        mvc.perform(get("/getUserInfo").session(session)).andExpect(jsonPath("$.code").value(200));
        verify(gateway).authenticate("unit-auth-code");
    }

    @Test
    void postCallbackReturnsOnlyUserAndSafeReturnPath() throws Exception {
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        var response = mvc.perform(post("/qqlogin/callback").session(session).param("code", "unit-auth-code").param("state", state))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.callbackUrl").value("/drive"))
                .andExpect(jsonPath("$.data.userInfo.isAdmin").value(false))
                .andExpect(jsonPath("$.data.userInfo.qqOpenId").doesNotExist())
                .andExpect(jsonPath("$.data.userInfo.password").doesNotExist())
                .andExpect(jsonPath("$.data.userInfo.sessionVersion").doesNotExist()).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("unit-auth-code")); assertFalse(body.contains("unit-app-secret"));
        assertFalse(body.contains("access_token")); assertFalse(body.contains("refresh_token"));
    }

    @Test
    void stateIsBoundToSessionAndCannotBeReplayed() throws Exception {
        MockHttpSession first = new MockHttpSession(), second = new MockHttpSession();
        String firstState = begin(first, "/drive"), secondState = begin(second, "/drive");
        mvc.perform(post("/qqlogin/callback").session(second).param("code", "code").param("state", firstState))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(gateway);
        complete(first, firstState);
        mvc.perform(post("/qqlogin/callback").session(first).param("code", "code").param("state", firstState))
                .andExpect(jsonPath("$.code").value(600));
        complete(second, secondState);
        verify(gateway, times(2)).authenticate("code");
        assertEquals(1, users());
    }

    @Test
    void stateExpiresAtFiveMinuteBoundaryWithoutProviderRequest() throws Exception {
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        ReflectionTestUtils.setField(oauth, "clock", Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC));
        mvc.perform(get("/qqlogin/callback").session(session).param("code", "code").param("state", state))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=expired"));
        assertNull(session.getAttribute(Constants.SESSION_KEY)); assertNull(session.getAttribute(QQOAuthService.STATE_KEY));
        verifyNoInteractions(gateway);
    }

    @Test
    void providerFailureConsumesStateAndDoesNotExposeSensitiveDiagnostics() throws Exception {
        when(gateway.authenticate(anyString())).thenThrow(new IllegalStateException("code=unit-auth-code secret=unit-app-secret token=unit-token"));
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        String body = mvc.perform(post("/qqlogin/callback").session(session).param("code", "unit-auth-code").param("state", state))
                .andExpect(jsonPath("$.code").value(600)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("unit-auth-code")); assertFalse(body.contains("unit-app-secret")); assertFalse(body.contains("unit-token"));
        assertNull(session.getAttribute(Constants.SESSION_KEY)); assertEquals(0, users());
        mvc.perform(post("/qqlogin/callback").session(session).param("code", "unit-auth-code").param("state", state))
                .andExpect(jsonPath("$.code").value(600));
        verify(gateway, times(1)).authenticate("unit-auth-code");
    }

    @Test
    void wrongApplicationOrMalformedOpenIdCannotCreateAccount() throws Exception {
        for (QQIdentity invalid : new QQIdentity[]{new QQIdentity("999", IDENTITY.openId(), "Name", null), new QQIdentity("123456", "bad-openid", "Name", null)}) {
            when(gateway.authenticate(anyString())).thenReturn(invalid);
            MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
            mvc.perform(post("/qqlogin/callback").session(session).param("code", "code").param("state", state))
                    .andExpect(jsonPath("$.code").value(600));
            assertNull(session.getAttribute(Constants.SESSION_KEY));
        }
        assertEquals(0, users());
    }

    @Test
    void disabledExistingAccountIsNotRecreatedOrLoggedIn() throws Exception {
        existing(0, 3, "旧名字", "");
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        mvc.perform(post("/qqlogin/callback").session(session).param("code", "code").param("state", state))
                .andExpect(jsonPath("$.code").value(600)).andExpect(jsonPath("$.message").value("账户已被禁用"));
        assertNull(session.getAttribute(Constants.SESSION_KEY)); assertEquals(1, users());
        assertNull(jdbc.queryForObject("select last_login_time from user_info where user_id='existing'", java.sql.Timestamp.class));
    }

    @Test
    void existingMappingKeepsLocalNameAvatarQuotaAndCurrentSessionVersion() throws Exception {
        existing(1, 7, "自定义名字", "");
        MockHttpSession session = new MockHttpSession(); complete(session, begin(session, "/recycle"));
        SessionWebUserDto user = (SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY);
        assertEquals("existing", user.getUserId()); assertEquals("自定义名字", user.getNickName());
        assertNull(user.getAvatar()); assertEquals(7L, user.getSessionVersion());
        assertEquals(1, users());
        assertEquals(42 * Constants.MB, jdbc.queryForObject("select total_space from user_info where user_id='existing'", Long.class));
        assertEquals("", jdbc.queryForObject("select qq_avatar from user_info where user_id='existing'", String.class));
        mvc.perform(get("/getUserInfo").session(session)).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void sameNicknameNeverMergesWithEmailAdminOrGrantsAdminRole() throws Exception {
        jdbc.update("insert into user_info(user_id,nick_name,email,status,use_space,total_space) values ('admin','Admin','admin@example.invalid',1,0,1048576)");
        when(gateway.authenticate(anyString())).thenReturn(new QQIdentity("123456", IDENTITY.openId(), "Admin", IDENTITY.avatarUrl()));
        MockHttpSession session = new MockHttpSession(); complete(session, begin(session, "/drive"));
        SessionWebUserDto user = (SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY);
        assertNotEquals("admin", user.getUserId()); assertTrue(user.getNickName().startsWith("Admin-"));
        assertFalse(user.getIsAdmin()); assertEquals(2, users());
        assertNull(jdbc.queryForObject("select email from user_info where user_id=?", String.class, user.getUserId()));
        mvc.perform(post("/admin/getSysSettings").session(session)).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void invalidDefaultQuotaCannotLeavePartialAccountOrSession() throws Exception {
        for (int quota : new int[]{0, -1, 1_048_577}) {
            systemConfig.setUserInitUseSpace(quota);
            MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
            mvc.perform(post("/qqlogin/callback").session(session).param("code", "code").param("state", state))
                    .andExpect(jsonPath("$.code").value(500));
            assertNull(session.getAttribute(Constants.SESSION_KEY)); assertEquals(0, users());
        }
    }

    @Test
    void concurrentLoginsProduceOneQqMappingAndSameUser() throws Exception {
        MockHttpSession first = new MockHttpSession(), second = new MockHttpSession();
        String stateA = begin(first, "/drive"), stateB = begin(second, "/drive");
        CountDownLatch providerBarrier = new CountDownLatch(2);
        when(gateway.authenticate(anyString())).thenAnswer(invocation -> {
            providerBarrier.countDown(); if (!providerBarrier.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout");
            return IDENTITY;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<QQOAuthService.CallbackResult> a = pool.submit(() -> oauth.complete(first, "one", stateA));
            Future<QQOAuthService.CallbackResult> b = pool.submit(() -> oauth.complete(second, "two", stateB));
            assertEquals(a.get(10, TimeUnit.SECONDS).userInfo().getUserId(), b.get(10, TimeUnit.SECONDS).userInfo().getUserId());
            assertEquals(1, users());
            assertEquals(1, jdbc.queryForObject("select count(*) from user_info where qq_open_id=?", Integer.class, IDENTITY.openId()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentCallbacksCannotExchangeTheSameStateTwice() throws Exception {
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        CountDownLatch exchanging = new CountDownLatch(1), continueExchange = new CountDownLatch(1);
        when(gateway.authenticate(anyString())).thenAnswer(invocation -> {
            exchanging.countDown(); if (!continueExchange.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout");
            return IDENTITY;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<QQOAuthService.CallbackResult> first = pool.submit(() -> oauth.complete(session, "first", state));
            assertTrue(exchanging.await(5, TimeUnit.SECONDS));
            Future<QQOAuthService.CallbackResult> second = pool.submit(() -> oauth.complete(session, "second", state));
            ExecutionException error = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertInstanceOf(top.enderherman.netdisk.common.exceptions.BusinessException.class, error.getCause());
            verify(gateway, times(1)).authenticate(anyString());
            continueExchange.countDown();
            assertNotNull(first.get(10, TimeUnit.SECONDS).userInfo());
            assertEquals(1, users());
        } finally { continueExchange.countDown(); pool.shutdownNow(); }
    }

    @Test
    void unsafeReturnLocationsAreRejectedBeforeStateOrProviderUse() throws Exception {
        for (String path : new String[]{"https://evil.test", "//evil.test", "/%2f%2fevil.test", "/\\evil.test"}) {
            MockHttpSession session = new MockHttpSession();
            mvc.perform(post("/qqlogin").session(session).param("callBackUrl", path)).andExpect(jsonPath("$.code").value(600));
            assertNull(session.getAttribute(QQOAuthService.STATE_KEY));
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void providerDenialConsumesMatchingStateWithoutCreatingSession() throws Exception {
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
        mvc.perform(get("/qqlogin/callback").session(session).param("state", state).param("error", "access_denied"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=cancelled"));
        assertNull(session.getAttribute(QQOAuthService.STATE_KEY)); assertNull(session.getAttribute(Constants.SESSION_KEY));
        verifyNoInteractions(gateway);
    }

    @Test
    void missingStateAndProviderErrorReturnOnlyFixedLoginErrorCategories() throws Exception {
        mvc.perform(get("/qqlogin/callback").param("error", "https://evil.test?secret=hidden"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=expired"))
                .andExpect(content().string(""));
        when(gateway.authenticate(anyString())).thenThrow(new top.enderherman.netdisk.common.exceptions.BusinessException(500, "unit-token"));
        MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive?private=value");
        mvc.perform(get("/qqlogin/callback").session(session).param("code", "unit-code").param("state", state))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=unavailable"))
                .andExpect(content().string(""));
        assertNull(session.getAttribute(Constants.SESSION_KEY));
    }

    @Test
    void beginRateLimitDoesNotTrustForwardedHeadersOrCreateExcessState() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) begin(new MockHttpSession(), "/drive");
        MockHttpSession blocked = new MockHttpSession();
        mvc.perform(post("/qqlogin").session(blocked).header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(jsonPath("$.code").value(600)).andExpect(jsonPath("$.message").value("操作过于频繁，请稍后重试"));
        assertNull(blocked.getAttribute(QQOAuthService.STATE_KEY)); verifyNoInteractions(gateway);
    }

    @Test
    void callbackRateLimitStopsBogusCodeExchangesAcrossFreshSessionsAndMethods() throws Exception {
        when(gateway.authenticate(anyString())).thenThrow(new IllegalArgumentException("bad code"));
        for (int attempt = 0; attempt < 20; attempt++) {
            MockHttpSession session = new MockHttpSession(); String state = begin(session, "/drive");
            if (attempt % 2 == 0) {
                mvc.perform(post("/qqlogin/callback").session(session).param("code", "bogus").param("state", state))
                        .andExpect(jsonPath("$.code").value(600));
            } else {
                mvc.perform(get("/qqlogin/callback").session(session).param("code", "bogus").param("state", state))
                        .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/auth/login?qqError=failed"));
            }
        }
        MockHttpSession blocked = new MockHttpSession(); String state = begin(blocked, "/drive");
        mvc.perform(post("/qqlogin/callback").session(blocked).header("X-Forwarded-For", "203.0.113.99").param("code", "bogus").param("state", state))
                .andExpect(jsonPath("$.code").value(600)).andExpect(jsonPath("$.message").value("操作过于频繁，请稍后重试"));
        verify(gateway, times(20)).authenticate("bogus"); assertEquals(0, users());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unavailableRateLimitStorageFailsClosedBeforeCreatingStateOrCallingProvider() throws Exception {
        MockHttpSession callbackSession = new MockHttpSession(); String state = begin(callbackSession, "/drive");
        when(rateRedis.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);
        MockHttpSession beginSession = new MockHttpSession();
        mvc.perform(post("/qqlogin").session(beginSession)).andExpect(jsonPath("$.code").value(600));
        assertNull(beginSession.getAttribute(QQOAuthService.STATE_KEY));
        mvc.perform(post("/qqlogin/callback").session(callbackSession).param("code", "code").param("state", state))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(gateway); assertEquals(0, users());
    }

    private String begin(MockHttpSession session, String callback) throws Exception {
        byte[] response = mvc.perform(post("/qqlogin").session(session).param("callBackUrl", callback))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsByteArray();
        URI uri = URI.create(json.readTree(response).path("data").asText());
        return QQOAuthSettingsTest.query(uri).get("state");
    }
    private void complete(MockHttpSession session, String state) throws Exception {
        mvc.perform(post("/qqlogin/callback").session(session).param("code", "code").param("state", state))
                .andExpect(jsonPath("$.code").value(200));
    }
    private int users() { return jdbc.queryForObject("select count(*) from user_info", Integer.class); }
    private void existing(int status, long version, String nickname, String avatar) {
        jdbc.update("insert into user_info(user_id,nick_name,qq_open_id,qq_avatar,status,session_version,use_space,total_space) values ('existing',?,?,?,?,?,3,?)",
                nickname, IDENTITY.openId().toLowerCase(), avatar, status, version, 42 * Constants.MB);
    }
}
