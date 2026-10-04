package top.enderherman.netdisk.service.qq;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QQOAuthSettingsTest {
    static AppConfig config() {
        AppConfig config = new AppConfig(); config.setQqAppId("123456");
        config.setQqAppKey("unit-app-secret-not-real"); config.setQqUrlRedirect("https://cloud.example.test/api/qqlogin/callback");
        return config;
    }
    static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> value[0], value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
    }

    @Test
    void explicitSwitchAndAllCredentialsAreRequired() {
        AppConfig config = config();
        assertFalse(new QQOAuthSettings(config, false).isEnabled());
        assertTrue(new QQOAuthSettings(config, true).isEnabled());
        config.setQqAppKey(""); assertFalse(new QQOAuthSettings(config, true).isEnabled());
    }

    @Test
    void remoteHttpCredentialsInUrlAndFragmentsAreNotAccepted() {
        AppConfig config = config();
        for (String url : new String[]{"http://cloud.example.test/callback", "https://user@cloud.example.test/callback", "https://cloud.example.test/callback#fragment", "javascript:alert(1)"}) {
            config.setQqUrlRedirect(url); assertFalse(new QQOAuthSettings(config, true).isEnabled());
        }
        config.setQqUrlRedirect("http://127.0.0.1:7090/api/qqlogin/callback");
        assertTrue(new QQOAuthSettings(config, true).isEnabled());
    }

    @Test
    void disabledFeatureNeverCreatesStateOrCallsProvider() {
        QQOAuthService service = new QQOAuthService();
        QQOAuthGateway gateway = mock(QQOAuthGateway.class);
        ReflectionTestUtils.setField(service, "settings", new QQOAuthSettings(config(), false));
        ReflectionTestUtils.setField(service, "gateway", gateway);
        MockHttpSession session = new MockHttpSession();
        assertThrows(BusinessException.class, () -> service.begin(session, "/drive"));
        assertThrows(BusinessException.class, () -> service.complete(session, "code", "state"));
        assertFalse(session.getAttributeNames().hasMoreElements());
        verifyNoInteractions(gateway);
    }

    @Test
    void authorizationUsesFixedOfficialEndpointAndUniqueHashedSessionState() {
        AppConfig config = config(); config.setQqUrlAuthorization("https://evil.example/?secret=%s");
        QQOAuthService service = new QQOAuthService();
        ReflectionTestUtils.setField(service, "settings", new QQOAuthSettings(config, true));
        MockHttpSession session = new MockHttpSession();
        URI first = URI.create(service.begin(session, "/s/share1?path=root%2Ffolder"));
        Map<String, String> fields = query(first);
        assertEquals("https", first.getScheme()); assertEquals("graph.qq.com", first.getHost());
        assertEquals("/oauth2.0/authorize", first.getPath()); assertEquals("123456", fields.get("client_id"));
        assertTrue(fields.get("state").matches("[A-Za-z0-9_-]{43}"));
        assertFalse(first.toString().contains(config.getQqAppKey()));
        assertFalse(session.getAttribute(QQOAuthService.STATE_KEY).toString().contains(fields.get("state")));
        URI second = URI.create(service.begin(session, "/drive"));
        assertNotEquals(fields.get("state"), query(second).get("state"));
    }

    @Test
    void returnPathIsLocalAndRejectsEncodedOpenRedirects() {
        assertEquals("/drive", QQOAuthService.safeReturnPath(null));
        assertEquals("/s/share1?path=root%2Fsub&page=2", QQOAuthService.safeReturnPath("/s/share1?path=root%2Fsub&page=2"));
        for (String url : new String[]{"https://evil.example", "//evil.example", "/\\evil", "/%2f%2fevil", "/%252f%252fevil",
                "/drive\r\nLocation:evil", "/drive?x=%0d%0a", "/drive#x%0a", "/auth/login", "drive"}) {
            assertThrows(BusinessException.class, () -> QQOAuthService.safeReturnPath(url), url);
        }
    }
}
