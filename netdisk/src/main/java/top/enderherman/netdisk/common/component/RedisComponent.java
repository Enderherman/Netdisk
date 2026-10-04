package top.enderherman.netdisk.common.component;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

@Slf4j
@Component("redisComponent")
public class RedisComponent {

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private FileMapper<FileInfo, FileQuery> fileMapper;

    @Resource
    private UserMapper<User, UserQuery> userMapper;

    /**
     * 获取系统设置
     */
    public SystemConfig getSystemConfig() {
        SystemConfig systemConfig = (SystemConfig) redisUtils.get(Constants.REDIS_KEY_SYS_SETTING);
        if (systemConfig == null) {
            systemConfig = new SystemConfig();
            redisUtils.set(Constants.REDIS_KEY_SYS_SETTING, systemConfig);
        }
        return systemConfig;
    }

    /**
     * 存储对应系统配置
     */
    public void saveSystemConfig(SystemConfig systemConfig) {
        redisUtils.set(Constants.REDIS_KEY_SYS_SETTING, systemConfig);
    }

    /**
     * 获取用户使用空间
     *
     * @param userId 用户ID
     * @return 用户使用空间
     */
    public UserSpaceDto getUserSpace(String userId) {
        UserSpaceDto userSpaceDto = (UserSpaceDto) redisUtils.get(Constants.REDIS_KEY_USER_SPACE_USE + userId);
        if (null == userSpaceDto) {
            //1.初始化
            userSpaceDto = new UserSpaceDto();
            Long useSpace = fileMapper.selectUseSpace(userId);
            User user = userMapper.selectByUserId(userId);
            if (user == null) {
                throw new BusinessException(ResponseCodeEnum.CODE_901);
            }
            //2.设置已使用空间
            userSpaceDto.setUseSpace(useSpace == null ? 0L : useSpace);
            //3.设置总空间
            userSpaceDto.setTotalSpace(user.getTotalSpace());

            redisUtils.setEx(Constants.REDIS_KEY_USER_SPACE_USE + userId, userSpaceDto, Constants.REDIS_KEY_EXPIRES_DAY);
        }
        return userSpaceDto;
    }
    /**
     * 存储用户已使用空间
     */
    public void saveUserSpaceDto(String userId, UserSpaceDto userSpaceDto) {
        redisUtils.setEx(Constants.REDIS_KEY_USER_SPACE_USE + userId, userSpaceDto, Constants.REDIS_KEY_EXPIRES_DAY);
    }

    /**
     * 重置用户空间
     */
    public void resetUserSpaceUse(String userId) {
        UserSpaceDto spaceDto = new UserSpaceDto();
        Long useSpace = fileMapper.selectUseSpace(userId);
        spaceDto.setUseSpace(useSpace);

        User userInfo = userMapper.selectByUserId(userId);
        spaceDto.setTotalSpace(userInfo.getTotalSpace());
        redisUtils.setEx(Constants.REDIS_KEY_USER_SPACE_USE + userId, spaceDto, Constants.REDIS_KEY_EXPIRES_DAY);
    }



    /**
     * 获取临时使用空间
     */
    public Long getFileTemplateSize(String userId, String fileId) {
        return getFileSizeFromRedis(Constants.REDIS_KEY_USER_FILE_TEMP_SIZE + userId + fileId);
    }

    private Long getFileSizeFromRedis(String key) {
        Object sizeObj = redisUtils.get(key);
        if (sizeObj == null) {
            return 0L;
        }
        if (sizeObj instanceof Integer) {
            return ((Integer) sizeObj).longValue();
        } else if (sizeObj instanceof Long) {
            return (Long) sizeObj;
        }
        return 0L;
    }

    /**
     * 保存临时文件大小
     */
    public void saveFileTempSize(String userId, String fileId, Long fileSize) {
        Long currentSize = getFileTemplateSize(userId, fileId);
        redisUtils.setEx(Constants.REDIS_KEY_USER_FILE_TEMP_SIZE + userId + fileId, currentSize + fileSize,
                Constants.REDIS_KEY_EXPIRES_ONE_HOUR);
    }

    /**
     * 存储下载时的code
     *
     * @param code 50位code
     */
    public void saveDownloadCode(String code, DownloadFileDto fileDto) {
        redisUtils.setEx(Constants.REDIS_KEY_DOWNLOAD + code, fileDto, Constants.REDIS_KEY_EXPIRES_FIVE_MIN);
    }

    /**
     * 获取对应下载文件的code
     */
    public DownloadFileDto getDownloadCode(String code) {
        return (DownloadFileDto) redisUtils.get(Constants.REDIS_KEY_DOWNLOAD + code);
    }


}
