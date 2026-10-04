package top.enderherman.netdisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageQuotaCacheTest {
    @Mock RedisUtils<Object> redisUtils;
    @Mock FileMapper<FileInfo, FileQuery> fileMapper;
    @Mock UserMapper<User, UserQuery> userMapper;
    @InjectMocks RedisComponent component;

    @Test
    void expiredCacheRetainsAdministrativelyAssignedQuota() {
        User user = new User();
        user.setTotalSpace(73L * Constants.MB);
        when(userMapper.selectByUserId("alice")).thenReturn(user);
        when(fileMapper.selectUseSpace("alice")).thenReturn(12L);
        UserSpaceDto result = component.getUserSpace("alice");
        assertEquals(73L * Constants.MB, result.getTotalSpace());
        assertEquals(12L, result.getUseSpace());
        verify(redisUtils).setEx(eq(Constants.REDIS_KEY_USER_SPACE_USE + "alice"), same(result), eq((long) Constants.REDIS_KEY_EXPIRES_DAY));
        verify(redisUtils, never()).get(Constants.REDIS_KEY_SYS_SETTING);
    }

    @Test
    void zeroQuotaIsPreservedAndEmptyUsageIsZero() {
        User user = new User();
        user.setTotalSpace(0L);
        when(userMapper.selectByUserId("alice")).thenReturn(user);
        when(fileMapper.selectUseSpace("alice")).thenReturn(null);
        UserSpaceDto result = component.getUserSpace("alice");
        assertEquals(0L, result.getTotalSpace());
        assertEquals(0L, result.getUseSpace());
    }

    @Test
    void existingCacheDoesNotQueryDatabase() {
        UserSpaceDto cached = new UserSpaceDto();
        cached.setTotalSpace(100L);
        cached.setUseSpace(4L);
        when(redisUtils.get(Constants.REDIS_KEY_USER_SPACE_USE + "alice")).thenReturn(cached);
        assertSame(cached, component.getUserSpace("alice"));
        verifyNoInteractions(userMapper, fileMapper);
    }

    @Test
    void deletedUserDoesNotReceiveFreshStorageAllowance() {
        assertThrows(BusinessException.class, () -> component.getUserSpace("missing"));
        verify(redisUtils, never()).setEx(anyString(), any(), anyLong());
    }
}
