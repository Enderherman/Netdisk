package top.enderherman.netdisk.service.qq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 解析官方 JSON、callback(JSON) 或 form 响应；不执行返回内容。 */
@Component
public class QQResponseParser {
    public static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final Pattern CALLBACK = Pattern.compile("^callback\\s*\\(\\s*(\\{.*})\\s*\\)\\s*;?$", Pattern.DOTALL);
    private final ObjectMapper mapper;
    public QQResponseParser(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public String accessToken(String body) {
        String content = bounded(body);
        String token;
        String expires;
        if (content.startsWith("{") || content.startsWith("callback")) {
            JsonNode object = object(content);
            rejectError(object);
            token = text(object, "access_token");
            JsonNode expiry = object.get("expires_in");
            expires = expiry == null ? "" : expiry.asText();
        } else {
            Map<String, String> values = form(content);
            if (values.containsKey("error")) fail();
            token = values.get("access_token"); expires = values.get("expires_in");
        }
        if (token == null || token.isBlank() || token.length() > 4096
                || token.chars().anyMatch(Character::isWhitespace) || token.chars().anyMatch(Character::isISOControl)
                || expires == null || !expires.matches("[0-9]{1,10}") || Long.parseLong(expires) < 1) fail();
        return token;
    }

    public String openId(String body, String expectedClientId) {
        JsonNode object = object(bounded(body)); rejectError(object);
        JsonNode client = object.get("client_id");
        if (client == null || (!client.isTextual() && !client.isIntegralNumber())
                || !expectedClientId.equals(client.asText())) fail();
        return normalizeOpenId(text(object, "openid"));
    }

    public QQIdentity profile(String body, String clientId, String openId) {
        JsonNode object = object(bounded(body)); rejectError(object);
        JsonNode result = object.get("ret");
        if (result == null || !result.isIntegralNumber() || result.bigIntegerValue().signum() != 0) fail();
        if (object.has("openid") && !normalizeOpenId(text(object, "openid")).equals(openId)) fail();
        if (object.has("client_id") && !clientId.equals(object.get("client_id").asText())) fail();
        String nickname = object.path("nickname").isTextual() ? object.path("nickname").textValue() : "QQ用户";
        String avatar = null;
        for (String key : new String[]{"figureurl_qq_2", "figureurl_qq_1", "figureurl_2", "figureurl_1"}) {
            if (object.path(key).isTextual()) avatar = safeAvatar(object.path(key).textValue());
            if (avatar != null) break;
        }
        return new QQIdentity(clientId, openId, nickname, avatar);
    }

    public static String normalizeOpenId(String value) {
        if (value == null || !value.matches("[A-Fa-f0-9]{32}")) fail();
        return value.toUpperCase(Locale.ROOT);
    }

    public static String safeAvatar(String value) {
        if (value == null || value.isBlank() || value.length() > 150) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || !(host.equals("qlogo.cn") || host.endsWith(".qlogo.cn")) || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) return null;
            String query = uri.getRawQuery();
            if (uri.getQuery() != null && uri.getQuery().toLowerCase(Locale.ROOT).matches(".*(?:access_token|refresh_token|client_secret|(?:^|&)code|(?:^|&)state)=.*")) return null;
            return "https://" + host + (uri.getRawPath() == null ? "" : uri.getRawPath()) + (query == null ? "" : "?" + query);
        } catch (IllegalArgumentException exception) { return null; }
    }

    private JsonNode object(String content) {
        if (content.startsWith("callback")) {
            var match = CALLBACK.matcher(content);
            if (!match.matches()) fail();
            content = match.group(1);
        }
        try {
            JsonNode object = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(content);
            if (!object.isObject()) fail();
            return object;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            // 解析异常可能含原始 token，不能作为 cause 或消息传播到日志。
            throw new BusinessException("QQ 授权响应无效，请重新发起登录");
        }
    }
    private Map<String, String> form(String content) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String part : content.split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) fail();
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (values.putIfAbsent(key, value) != null) fail();
            }
            return values;
        } catch (IllegalArgumentException exception) { throw new BusinessException("QQ 授权响应无效，请重新发起登录"); }
    }
    private String text(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual()) fail();
        return value.textValue();
    }
    private void rejectError(JsonNode object) { if (object.has("error")) fail(); }
    private String bounded(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) fail();
        String result = body.strip();
        if (result.startsWith("\uFEFF")) result = result.substring(1).strip();
        if (result.isEmpty()) fail();
        return result;
    }
    private static void fail() { throw new BusinessException("QQ 授权响应无效，请重新发起登录"); }
}
