package top.enderherman.netdisk.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** 跨实例共享且原子递增的安全操作限流，Redis 不可用时不放行。 */
@Service
public class AccountRateLimiter {
    private static final DefaultRedisScript<Long> WINDOW = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n;", Long.class);
    private final StringRedisTemplate redis;

    public AccountRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void requireAllowed(String action, String identity, int limit, Duration duration) {
        String key = "netdisk:security:" + action + ":" + digest(identity);
        Long count = redis.execute(WINDOW, List.of(key), Long.toString(duration.toSeconds()));
        if (count == null || count > limit) {
            throw new BusinessException("操作过于频繁，请稍后重试");
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
