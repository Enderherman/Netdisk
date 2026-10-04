package top.enderherman.netdisk.service.qq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.common.config.AppConfig;

import java.net.URI;
import java.util.Locale;

@Component
public class QQOAuthSettings {
    public static final String AUTHORIZATION = "https://graph.qq.com/oauth2.0/authorize";
    public static final String TOKEN = "https://graph.qq.com/oauth2.0/token";
    public static final String OPEN_ID = "https://graph.qq.com/oauth2.0/me";
    public static final String PROFILE = "https://graph.qq.com/user/get_user_info";
    private final AppConfig config;
    private final boolean enabled;

    public QQOAuthSettings(AppConfig config, @Value("${qq.enabled:false}") boolean enabled) {
        this.config = config; this.enabled = enabled;
    }
    public String clientId() { return clean(config.getQqAppId()); }
    public String clientSecret() { return clean(config.getQqAppKey()); }
    public String redirectUri() { return clean(config.getQqUrlRedirect()); }

    public boolean isEnabled() {
        if (!enabled || !clientId().matches("[0-9]{1,20}") || clientSecret().isBlank() || clientSecret().length() > 512
                || clientSecret().chars().anyMatch(Character::isISOControl) || redirectUri().length() > 2048) return false;
        try {
            URI uri = URI.create(redirectUri());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) return false;
            String host = uri.getHost().replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme())
                    && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")));
        } catch (IllegalArgumentException exception) { return false; }
    }

    public void requireEnabled() {
        if (!isEnabled()) throw new QQOAuthFailure(QQOAuthFailure.Reason.unavailable, 600, "QQ 登录尚未启用或配置不完整，请使用邮箱登录");
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
}
