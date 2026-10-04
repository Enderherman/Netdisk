package top.enderherman.netdisk.common.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component("redisUtils")
public class RedisUtils<V> {

    @Resource
    private RedisTemplate<String, V> redisTemplate;

    /**
     * 取值
     *
     * @param key 键
     * @return 值
     */
    public V get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (SerializationException e) {
            log.error("Redis 反序列化失败，键：{}，错误：{}", key, e.getMessage());
            throw new BusinessException(ResponseCodeEnum.CODE_506);
        }
    }


    /**
     * 存值
     *
     * @param key   键
     * @param value 值
     * @return 是否成功
     */
    public boolean set(String key, V value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            log.error("redis存储失败, key: {}, value: {}, error: {}", key, value, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 存储值_TTL
     *
     * @param key   键
     * @param value 值
     * @param time  TTL time to live
     * @return 是否成功
     */
    public boolean setEx(String key, V value, long time) {
        try {
            if (time > 0) {
                redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
            } else {
                return set(key, value);
            }
            return true;
        } catch (Exception e) {
            log.error("redis存储失败, key: {}, value: {}, error: {}", key, value, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 删除缓存
     *
     * @param key 可以传一个还可以传多个
     */
    public void delete(String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                redisTemplate.delete(key[0]);
            } else {
                redisTemplate.delete(Arrays.asList(key));
            }
        }
    }
}
