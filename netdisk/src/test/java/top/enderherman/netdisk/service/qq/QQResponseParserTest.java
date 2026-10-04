package top.enderherman.netdisk.service.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class QQResponseParserTest {
    static final String OPEN_ID = "0123456789ABCDEF0123456789ABCDEF";
    private final QQResponseParser parser = new QQResponseParser(new ObjectMapper());

    @Test
    void acceptsOfficialFormAndJsonTokenFormats() {
        assertEquals("unit-token", parser.accessToken("access_token=unit-token&expires_in=7776000&refresh_token=unused"));
        assertEquals("unit-token", parser.accessToken("{\"access_token\":\"unit-token\",\"expires_in\":7776000}"));
        assertEquals("unit-token", parser.accessToken("callback( {\"access_token\":\"unit-token\",\"expires_in\":\"7776000\"} );"));
    }

    @Test
    void parsesCallbackOpenIdAndRequiresExpectedApplication() {
        String response = "callback( {\"client_id\":\"123456\",\"openid\":\"" + OPEN_ID.toLowerCase() + "\"} );";
        assertEquals(OPEN_ID, parser.openId(response, "123456"));
        assertThrows(BusinessException.class, () -> parser.openId(response, "999999"));
        assertThrows(BusinessException.class, () -> parser.openId("{\"client_id\":\"123456\",\"openid\":\"bad\"}", "123456"));
    }

    @Test
    void rejectsScriptsTrailingJsonDuplicateKeysAndProviderErrorsWithoutLeakingBody() {
        for (String body : new String[]{
                "callback({\"client_id\":\"123456\",\"openid\":\"" + OPEN_ID + "\"});alert('secret-code')",
                "{\"client_id\":\"123456\",\"client_id\":\"999999\",\"openid\":\"" + OPEN_ID + "\"}",
                "{\"client_id\":\"123456\",\"openid\":\"" + OPEN_ID + "\"} {}",
                "callback({\"error\":100016,\"error_description\":\"secret-code unit-token\"});"}) {
            BusinessException error = assertThrows(BusinessException.class, () -> parser.openId(body, "123456"));
            assertFalse(error.getMessage().contains("secret-code"));
            assertFalse(error.getMessage().contains("unit-token"));
            assertNull(error.getCause());
        }
    }

    @Test
    void malformedOrExpiredTokensDoNotPass() {
        for (String body : new String[]{"access_token=first&access_token=second&expires_in=60", "access_token=unit&expires_in=0",
                "access_token=unit&expires_in=-1", "access_token=unit", "error=100&error_description=unit-token",
                "access_token=%zz&expires_in=60", "{\"access_token\":null,\"expires_in\":60}"}) {
            assertThrows(BusinessException.class, () -> parser.accessToken(body));
        }
    }

    @Test
    void profileRequiresExactSuccessAndConsistentOptionalIdentity() {
        QQIdentity identity = parser.profile("{\"ret\":0,\"nickname\":\"测试用户\",\"figureurl_qq_2\":\"http://thirdqq.qlogo.cn/avatar/100\"}", "123456", OPEN_ID);
        assertEquals("测试用户", identity.nickname());
        assertEquals("https://thirdqq.qlogo.cn/avatar/100", identity.avatarUrl());
        for (String body : new String[]{"{}", "{\"ret\":1}", "{\"ret\":4294967296}", "{\"ret\":\"0\"}",
                "{\"ret\":0,\"openid\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}", "{\"ret\":0,\"client_id\":\"999\"}"}) {
            assertThrows(BusinessException.class, () -> parser.profile(body, "123456", OPEN_ID));
        }
    }

    @Test
    void unsafeAvatarLocationsAndCredentialQueriesAreDiscarded() {
        for (String value : new String[]{"javascript:alert(1)", "data:image/png;base64,AA", "https://evil.example/avatar",
                "https://qlogo.cn.evil.example/a", "https://user@qlogo.cn/a", "https://qlogo.cn:8443/a",
                "https://qlogo.cn/a?access_token=secret", "https://qlogo.cn/a?%61ccess_token=secret"}) {
            assertNull(QQResponseParser.safeAvatar(value));
        }
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=123&s=100", QQResponseParser.safeAvatar("https://q1.qlogo.cn/g?b=qq&nk=123&s=100"));
    }

    @Test
    void responseIsBoundedInBytesAndInvalidUtf8Fails() throws Exception {
        assertThrows(BusinessException.class, () -> parser.accessToken("x".repeat(QQResponseParser.MAX_RESPONSE_BYTES + 1)));
        assertThrows(IOException.class, () -> GraphQQOAuthGateway.readLimited(new ByteArrayInputStream(new byte[QQResponseParser.MAX_RESPONSE_BYTES + 1])));
        assertThrows(IOException.class, () -> GraphQQOAuthGateway.readLimited(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})));
        assertEquals("有效响应", GraphQQOAuthGateway.readLimited(new ByteArrayInputStream("有效响应".getBytes(StandardCharsets.UTF_8))));
    }
}
