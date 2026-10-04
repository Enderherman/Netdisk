package top.enderherman.netdisk.service.qq;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GraphQQOAuthGateway implements QQOAuthGateway {
    static final int CONNECT_TIMEOUT_MS = 3000;
    static final int READ_TIMEOUT_MS = 5000;
    private final QQOAuthSettings settings;
    private final QQResponseParser parser;
    @FunctionalInterface interface Connector { HttpsURLConnection open(URI uri) throws IOException; }
    private final Connector connector;
    @Autowired
    public GraphQQOAuthGateway(QQOAuthSettings settings, QQResponseParser parser) {
        this(settings, parser, uri -> (HttpsURLConnection) uri.toURL().openConnection());
    }
    GraphQQOAuthGateway(QQOAuthSettings settings, QQResponseParser parser, Connector connector) {
        this.settings = settings; this.parser = parser; this.connector = connector;
    }

    @Override
    public QQIdentity authenticate(String authorizationCode) {
        settings.requireEnabled();
        Map<String, String> tokenQuery = new LinkedHashMap<>();
        tokenQuery.put("grant_type", "authorization_code"); tokenQuery.put("client_id", settings.clientId());
        tokenQuery.put("client_secret", settings.clientSecret()); tokenQuery.put("code", authorizationCode);
        tokenQuery.put("redirect_uri", settings.redirectUri());
        String accessToken = parser.accessToken(get(QQOAuthSettings.TOKEN, tokenQuery));
        String openId = parser.openId(get(QQOAuthSettings.OPEN_ID, Map.of("access_token", accessToken)), settings.clientId());
        QQIdentity identity = parser.profile(get(QQOAuthSettings.PROFILE, Map.of("access_token", accessToken,
                "oauth_consumer_key", settings.clientId(), "openid", openId)), settings.clientId(), openId);
        // 凭证只存在于换票过程，异常资料不能把它们夹带进昵称、数据库或浏览器头像请求。
        String[] credentials = {authorizationCode, settings.clientSecret(), accessToken};
        return new QQIdentity(identity.clientId(), identity.openId(),
                containsCredentials(identity.nickname(), credentials) ? "QQ用户" : identity.nickname(),
                containsCredentials(identity.avatarUrl(), credentials) ? null : identity.avatarUrl());
    }

    private boolean containsCredentials(String value, String... credentials) {
        if (value == null) return false;
        for (int pass = 0; pass < 4; pass++) {
            for (String credential : credentials) if (credential != null && !credential.isEmpty() && value.contains(credential)) return true;
            try {
                String decoded = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (decoded.equals(value)) break;
                value = decoded;
            } catch (IllegalArgumentException ignored) { break; }
        }
        return false;
    }

    private String get(String endpoint, Map<String, String> parameters) {
        HttpsURLConnection connection = null;
        try {
            connection = connector.open(URI.create(endpoint + "?" + query(parameters)));
            connection.setRequestMethod("GET"); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > QQResponseParser.MAX_RESPONSE_BYTES) {
                throw new BusinessException("QQ 登录服务响应异常，请稍后重试");
            }
            try (InputStream input = connection.getInputStream()) { return readLimited(input); }
        } catch (IOException | IllegalArgumentException exception) {
            // 不记录包含 code、secret 或 token 的请求 URI、响应正文及底层异常消息。
            throw new BusinessException(500, "暂时无法连接 QQ 登录服务，请稍后重试");
        } finally { if (connection != null) connection.disconnect(); }
    }

    static String readLimited(InputStream input) throws IOException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int length; (length = input.read(buffer)) != -1; ) {
            if (System.nanoTime() > deadline || bytes.size() + length > QQResponseParser.MAX_RESPONSE_BYTES) {
                throw new IOException("QQ response limit exceeded");
            }
            bytes.write(buffer, 0, length);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    static String query(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
