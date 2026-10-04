package top.enderherman.netdisk.service.qq;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;
import top.enderherman.netdisk.service.AccountSecurityService;

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.util.*;

@Service("qqOAuthService")
public class QQOAuthService {
    static final String STATE_KEY = "netdisk.qq.pending-login";
    private static final long STATE_TTL_MS = 5 * 60 * 1000;
    @Resource private QQOAuthSettings settings;
    @Resource private QQOAuthGateway gateway;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private RedisComponent redis;
    @Resource private AccountSecurityService security;
    @Resource private PlatformTransactionManager transactions;
    private final SecureRandom random = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    public record CallbackResult(String callbackUrl, SessionWebUserDto userInfo) { }
    private record PendingLogin(byte[] stateDigest, long expiresAt, String callbackUrl,
                                String clientId, String redirectUri) implements Serializable { }

    public boolean isEnabled() { return settings.isEnabled(); }

    public String begin(HttpSession session, String callbackUrl) {
        settings.requireEnabled();
        String safeCallback = safeReturnPath(callbackUrl);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (session) {
            session.setAttribute(STATE_KEY, new PendingLogin(digest(state), clock.millis() + STATE_TTL_MS,
                    safeCallback, settings.clientId(), settings.redirectUri()));
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code"); parameters.put("client_id", settings.clientId());
        parameters.put("redirect_uri", settings.redirectUri()); parameters.put("state", state);
        parameters.put("scope", "get_user_info");
        return QQOAuthSettings.AUTHORIZATION + "?" + GraphQQOAuthGateway.query(parameters);
    }

    public CallbackResult complete(HttpSession session, String code, String state) {
        settings.requireEnabled();
        if (code == null || code.isBlank() || code.length() > 2048 || code.chars().anyMatch(Character::isWhitespace)
                || code.chars().anyMatch(Character::isISOControl)) failState();
        PendingLogin pending = consumeState(session, state);
        QQIdentity identity;
        try { identity = gateway.authenticate(code); }
        catch (RuntimeException exception) {
            // Gateway 的诊断可能含授权码或令牌，边界处只向外传播固定文案且不携带 cause。
            int status = exception instanceof BusinessException business && Integer.valueOf(500).equals(business.getCode()) ? 500 : 600;
            throw new QQOAuthFailure(status == 500 ? QQOAuthFailure.Reason.unavailable : QQOAuthFailure.Reason.failed,
                    status, "QQ 授权未能完成，请重新发起登录");
        }
        if (identity == null || !settings.clientId().equals(identity.clientId())) {
            throw new BusinessException("QQ 身份校验失败，请重新发起登录");
        }
        String openId = QQResponseParser.normalizeOpenId(identity.openId());
        User user = provision(openId, identity);
        SessionWebUserDto dto = new SessionWebUserDto();
        dto.setUserId(user.getUserId()); dto.setNickName(user.getNickName());
        dto.setAvatar(QQResponseParser.safeAvatar(user.getQqAvatar()));
        dto.setIsAdmin(security.isAdmin(user.getEmail())); dto.setSessionVersion(user.getSessionVersion());
        UserSpaceDto space = new UserSpaceDto(); space.setTotalSpace(user.getTotalSpace());
        space.setUseSpace(Objects.requireNonNullElse(fileMapper.selectUseSpace(user.getUserId()), 0L));
        try { redis.saveUserSpaceDto(user.getUserId(), space); }
        catch (RuntimeException ignored) { /* 登录身份已确认，容量缓存可在后续查询时重建。 */ }
        return new CallbackResult(pending.callbackUrl(), dto);
    }

    public void reject(HttpSession session, String state) {
        settings.requireEnabled();
        consumeState(session, state);
        throw new QQOAuthFailure(QQOAuthFailure.Reason.cancelled, 600, "QQ 授权已取消或未获许可，请重新发起登录");
    }

    private PendingLogin consumeState(HttpSession session, String supplied) {
        if (supplied == null || !supplied.matches("[A-Za-z0-9_-]{43}")) failState();
        synchronized (session) {
            Object value = session.getAttribute(STATE_KEY);
            if (!(value instanceof PendingLogin pending)) { failState(); return null; }
            if (pending.expiresAt() <= clock.millis() || !settings.clientId().equals(pending.clientId())
                    || !settings.redirectUri().equals(pending.redirectUri())) {
                session.removeAttribute(STATE_KEY); failState();
            }
            if (!MessageDigest.isEqual(pending.stateDigest(), digest(supplied))) failState();
            // 匹配后先消费，再访问提供者。并发回调和提供者失败都不能重复使用。
            session.removeAttribute(STATE_KEY);
            return pending;
        }
    }

    private User provision(String openId, QQIdentity identity) {
        for (int attempt = 0; attempt < 5; attempt++) {
            final int collision = attempt;
            TransactionTemplate transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                return transaction.execute(status -> {
                    User user = userMapper.selectByQqOpenId(openId);
                    if (user == null) {
                        Integer quota = redis.getSystemConfig().getUserInitUseSpace();
                        if (quota == null || quota < 1 || quota > 1_048_576) {
                            throw new BusinessException(500, "系统初始容量配置不正确，请联系管理员");
                        }
                        user = new User();
                        String userId = String.format(Locale.ROOT, "%010d", random.nextLong(10_000_000_000L));
                        user.setUserId(userId); user.setQqOpenId(openId);
                        user.setNickName(nickname(identity.nickname(), collision == 0 ? "" : "-" + userId.substring(5)));
                        user.setQqAvatar(QQResponseParser.safeAvatar(identity.avatarUrl()));
                        user.setStatus(1); user.setUseSpace(0L); user.setTotalSpace(quota * Constants.MB);
                        user.setCreateTime(new Date(clock.millis())); user.setLastLoginTime(new Date(clock.millis()));
                        // 不生成伪邮箱、不设置本地密码，也不按昵称寻找或合并已有账号。
                        userMapper.insert(user);
                    }
                    if (fileMapper.lockUserForStorage(user.getUserId()) == null) throw new BusinessException("QQ 账号不可用");
                    User current = userMapper.selectByUserId(user.getUserId());
                    if (current == null || !Integer.valueOf(1).equals(current.getStatus())) {
                        throw new BusinessException("账户已被禁用");
                    }
                    if (current.getTotalSpace() == null || current.getTotalSpace() < 0 || current.getSessionVersion() == null) {
                        throw new BusinessException(500, "账户容量或会话信息异常，请联系管理员");
                    }
                    User update = new User(); update.setLastLoginTime(new Date(clock.millis()));
                    // 空字符串表示用户已上传本地头像，后续 QQ 登录不覆盖本地选择。
                    if (!"".equals(current.getQqAvatar())) {
                        String avatar = QQResponseParser.safeAvatar(identity.avatarUrl());
                        if (avatar == null) avatar = QQResponseParser.safeAvatar(current.getQqAvatar());
                        if (avatar != null) { update.setQqAvatar(avatar); current.setQqAvatar(avatar); }
                        else if (current.getQqAvatar() != null) { update.setQqAvatar(""); current.setQqAvatar(""); }
                    }
                    userMapper.updateByUserId(update, current.getUserId());
                    return current;
                });
            } catch (DuplicateKeyException ignored) {
                // 唯一 QQ 映射或昵称发生竞争：新事务重读已有映射，或分配新的显示名。
            }
        }
        throw new BusinessException(500, "QQ 账号初始化失败，请稍后重试");
    }

    static String nickname(String value, String suffix) {
        String base = value == null ? "QQ用户" : Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("\\p{Cc}", "").strip();
        if (base.isBlank()) base = "QQ用户";
        int length = Math.min(base.length(), 20 - suffix.length());
        if (length > 0 && Character.isHighSurrogate(base.charAt(length - 1))) length--;
        String prefix = base.substring(0, length);
        if (prefix.isBlank()) prefix = "QQ用户";
        return prefix + suffix;
    }

    public static String safeReturnPath(String value) {
        if (value == null || value.isBlank()) return "/drive";
        if (value.length() > 2048 || !value.startsWith("/") || value.startsWith("//") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) failRedirect();
        try {
            URI uri = URI.create(value);
            String rawPath = uri.getRawPath(), path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || rawPath == null || path == null
                    || path.startsWith("//") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)
                    || rawPath.toLowerCase(Locale.ROOT).matches(".*%(?:2f|5c|25|0[0-9a-f]|1[0-9a-f]|7f).*")) failRedirect();
            if ((uri.getQuery() != null && uri.getQuery().chars().anyMatch(Character::isISOControl))
                    || (uri.getFragment() != null && uri.getFragment().chars().anyMatch(Character::isISOControl))) failRedirect();
            String normalized = uri.normalize().toASCIIString();
            if (normalized.startsWith("//") || uri.normalize().getPath().startsWith("/auth")) failRedirect();
            return normalized;
        } catch (IllegalArgumentException exception) { throw new BusinessException("登录返回地址不正确"); }
    }
    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用"); }
    }
    private static void failState() { throw new QQOAuthFailure(QQOAuthFailure.Reason.expired, 600, "QQ 登录验证已失效，请重新发起登录"); }
    private static void failRedirect() { throw new BusinessException("登录返回地址不正确"); }
}
