package top.enderherman.netdisk.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountRateLimiterTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void limitBoundaryUsesAtomicRedisAndHidesIdentityInKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L, 2L);
        AccountRateLimiter limiter = new AccountRateLimiter(redis);
        limiter.requireAllowed("email", "user@example.test", 1, Duration.ofSeconds(60));
        assertThrows(BusinessException.class, () -> limiter.requireAllowed("email", "user@example.test", 1, Duration.ofSeconds(60)));
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(2)).execute(any(RedisScript.class), keys.capture(), eq("60"));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertFalse(keys.getValue().toString().contains("user@example.test"));
    }

    @Test
    void missingRedisResultDoesNotAllowRequest() {
        AccountRateLimiter limiter = new AccountRateLimiter(mock(StringRedisTemplate.class));
        assertThrows(BusinessException.class, () -> limiter.requireAllowed("email", "user@example.test", 1, Duration.ofSeconds(60)));
    }
}
