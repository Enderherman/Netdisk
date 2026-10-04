package top.enderherman.netdisk.service.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphQQOAuthGatewayTest {
    @Test
    void officialExchangeUsesBoundedTimedHttpsAndNeverFollowsRedirects() throws Exception {
        List<URI> requests = new ArrayList<>();
        List<HttpsURLConnection> connections = List.of(
                response("access_token=unit-token&expires_in=7776000"),
                response("callback({\"client_id\":\"123456\",\"openid\":\"" + QQResponseParserTest.OPEN_ID + "\"});"),
                response("{\"ret\":0,\"nickname\":\"测试QQ\",\"figureurl_qq_2\":\"https://thirdqq.qlogo.cn/avatar/100\"}"));
        Queue<HttpsURLConnection> queue = new ArrayDeque<>(connections);
        GraphQQOAuthGateway gateway = new GraphQQOAuthGateway(new QQOAuthSettings(QQOAuthSettingsTest.config(), true),
                new QQResponseParser(new ObjectMapper()), uri -> { requests.add(uri); return queue.remove(); });
        QQIdentity identity = gateway.authenticate("unit-code&injected=1");
        assertEquals(QQResponseParserTest.OPEN_ID, identity.openId());
        assertEquals(List.of("/oauth2.0/token", "/oauth2.0/me", "/user/get_user_info"), requests.stream().map(URI::getPath).toList());
        assertTrue(requests.stream().allMatch(uri -> uri.getScheme().equals("https") && uri.getHost().equals("graph.qq.com")));
        assertEquals("unit-code&injected=1", QQOAuthSettingsTest.query(requests.get(0)).get("code"));
        assertEquals(5, QQOAuthSettingsTest.query(requests.get(0)).size());
        assertFalse(identity.toString().contains("unit-token"));
        for (HttpsURLConnection connection : connections) {
            verify(connection).setConnectTimeout(3000); verify(connection).setReadTimeout(5000);
            verify(connection).setInstanceFollowRedirects(false); verify(connection).disconnect();
        }
    }

    @Test
    void redirectsAndTransportErrorsDoNotExposeCredentials() throws Exception {
        HttpsURLConnection redirect = response("unused"); when(redirect.getResponseCode()).thenReturn(302);
        GraphQQOAuthGateway gateway = gateway(redirect);
        assertThrows(BusinessException.class, () -> gateway.authenticate("unit-code"));
        verify(redirect, never()).getInputStream(); verify(redirect).disconnect();
        HttpsURLConnection failed = response("unused");
        when(failed.getResponseCode()).thenThrow(new SocketTimeoutException("client_secret=unit-secret access_token=unit-token"));
        BusinessException error = assertThrows(BusinessException.class, () -> gateway(failed).authenticate("unit-code"));
        assertEquals(500, error.getCode()); assertNull(error.getCause());
        assertFalse(error.getMessage().contains("unit-secret")); assertFalse(error.getMessage().contains("unit-token"));
    }

    private GraphQQOAuthGateway gateway(HttpsURLConnection connection) {
        return new GraphQQOAuthGateway(new QQOAuthSettings(QQOAuthSettingsTest.config(), true), new QQResponseParser(new ObjectMapper()), uri -> connection);
    }

    @Test
    void providerMetadataCannotReflectCodeSecretOrTokenIntoPersistedProfile() throws Exception {
        for (String credential : List.of("unit-auth-code", "unit-app-secret-not-real", "unit-token")) {
            String encoded = credential.replace("-", "%2D");
            Queue<HttpsURLConnection> responses = new ArrayDeque<>(List.of(
                    response("access_token=unit-token&expires_in=7776000"),
                    response("{\"client_id\":\"123456\",\"openid\":\"" + QQResponseParserTest.OPEN_ID + "\"}"),
                    response("{\"ret\":0,\"nickname\":\"Name " + credential + "\",\"figureurl_qq_2\":\"https://thirdqq.qlogo.cn/avatar/" + encoded + "\"}")));
            GraphQQOAuthGateway gateway = new GraphQQOAuthGateway(new QQOAuthSettings(QQOAuthSettingsTest.config(), true),
                    new QQResponseParser(new ObjectMapper()), uri -> responses.remove());
            QQIdentity identity = gateway.authenticate("unit-auth-code");
            assertEquals("QQ用户", identity.nickname()); assertNull(identity.avatarUrl());
            assertFalse(identity.toString().contains(credential));
        }
    }
    private HttpsURLConnection response(String body) throws Exception {
        HttpsURLConnection connection = mock(HttpsURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentLengthLong()).thenReturn(-1L);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return connection;
    }
}
