package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.common.utils.RedisUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.enums.PageSize;
import top.enderherman.netdisk.entity.enums.UserStatusEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.SimplePage;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;
import top.enderherman.netdisk.service.EmailCodeService;
import top.enderherman.netdisk.service.UserService;
import top.enderherman.netdisk.service.PasswordService;
import top.enderherman.netdisk.service.AccountSecurityService;
import top.enderherman.netdisk.service.AccountRateLimiter;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;

import java.util.Date;
import java.util.List;
import java.time.Duration;
import java.util.Objects;

@Service("userService")
public class UserServiceImpl implements UserService {

    @Resource
    private UserMapper<User, UserQuery> userMapper;

    @Resource
    private FileMapper<FileInfo, FileQuery> fileInfoMapper;

    @Resource
    private EmailCodeService emailCodeService;

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private AppConfig appConfig;
    @Resource
    private PasswordService passwordService;
    @Resource
    private AccountSecurityService accountSecurityService;
    @Resource
    private AccountRateLimiter accountRateLimiter;
    @Resource
    private RedisUtils<Object> redisUtils;
    @Resource
    private FileUploadService fileUploadService;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<User> findListByParam(UserQuery param) {
        return this.userMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(UserQuery param) {
        return this.userMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<User> findListByPage(UserQuery param) {
        if ((param.getPageNo() != null && (param.getPageNo() < 1 || param.getPageNo() > 1_000_000))
                || (param.getPageSize() != null && (param.getPageSize() < 1 || param.getPageSize() > 100))
                || (param.getStatus() != null && param.getStatus() != 0 && param.getStatus() != 1)
                || (param.getNickNameFuzzy() != null && param.getNickNameFuzzy().length() > 20)
                || (param.getEmailFuzzy() != null && param.getEmailFuzzy().length() > 150)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        param.setOrderBy("create_time desc,user_id asc");
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<User> list = this.findListByParam(param);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(String email, String nickName, String password, String emailCode) {
        email = accountSecurityService.normalizeEmail(email);
        passwordService.validateNewPassword(password);
        if (nickName == null || nickName.isBlank() || nickName.trim().length() > 20) {
            throw new BusinessException("昵称须为 1 至 20 个字符");
        }
        nickName = nickName.trim();
        User user = userMapper.selectByEmail(email);
        if (user != null) {
            throw new BusinessException("邮箱账号已经存在");
        }
        User nickNameUser = userMapper.selectByNickName(nickName);
        if (nickNameUser != null) {
            throw new BusinessException("昵称已经存在");
        }
        //校验邮箱验证码
        emailCodeService.checkEmailCode(email, emailCode, 0);
        //用户个人信息初始化
        String userId = StringUtils.getRandomNumber(Constants.LENGTH_10);
        user = new User();
        user.setUserId(userId);
        user.setNickName(nickName);
        user.setEmail(email);
        user.setPassword(passwordService.hash(password));
        user.setCreateTime(new Date());
        user.setStatus(UserStatusEnum.ENABLE.getStatus());
        user.setUseSpace(0L);
        //容量初始化
        SystemConfig systemConfig = redisComponent.getSystemConfig();
        if (systemConfig.getUserInitUseSpace() == null || systemConfig.getUserInitUseSpace() < 1
                || systemConfig.getUserInitUseSpace() > 1_048_576) {
            throw new BusinessException("系统初始容量配置不正确，请联系管理员");
        }
        user.setTotalSpace(systemConfig.getUserInitUseSpace() * Constants.MB);
        //插入用户数据
        userMapper.insert(user);
    }

    @Override
    public SessionWebUserDto login(String email, String password) {
        email = accountSecurityService.normalizeEmail(email);
        accountRateLimiter.requireAllowed("login-email", email, 10, Duration.ofMinutes(15));
        //1.校验账号密码以及账号状态
        User user = userMapper.selectByEmail(email);
        if (user == null || !passwordService.matches(password, user.getPassword())) {
            throw new BusinessException("账户或密码错误");
        }
        if (!UserStatusEnum.ENABLE.getStatus().equals(user.getStatus())) {
            throw new BusinessException("账户已被禁用");
        }
        if (passwordService.needsUpgrade(user.getPassword())) {
            String upgraded = passwordService.hash(password);
            if (userMapper.upgradePassword(user.getUserId(), user.getPassword(), upgraded) != 1) {
                throw new BusinessException("账户信息已变更，请重新登录");
            }
            user.setPassword(upgraded);
        }
        User current = userMapper.selectByUserId(user.getUserId());
        if (current == null || !UserStatusEnum.ENABLE.getStatus().equals(current.getStatus())
                || !Objects.equals(current.getPassword(), user.getPassword())) {
            throw new BusinessException("账户信息已变更，请重新登录");
        }
        user = current;

        //2.更新最近登录时间
        User updateUser = new User();
        updateUser.setLastLoginTime(new Date());
        userMapper.updateByUserId(updateUser, user.getUserId());


        //3.设置用户登录信息
        SessionWebUserDto dto = new SessionWebUserDto();
        dto.setUserId(user.getUserId());
        dto.setNickName(user.getNickName());
        dto.setIsAdmin(accountSecurityService.isAdmin(email));
        dto.setAvatar(user.getQqAvatar());
        dto.setSessionVersion(user.getSessionVersion());

        //4.设置用户空间使用情况
        UserSpaceDto userSpaceDto = new UserSpaceDto();
        Long useSpace = fileInfoMapper.selectUseSpace(user.getUserId());
        userSpaceDto.setUseSpace(useSpace);
        userSpaceDto.setTotalSpace(user.getTotalSpace());
        redisComponent.saveUserSpaceDto(user.getUserId(), userSpaceDto);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPwd(String email, String password, String emailCode) {
        email = accountSecurityService.normalizeEmail(email);
        passwordService.validateNewPassword(password);
        //1.校验账号密码以及账号状态
        User user = userMapper.selectByEmail(email);
        if (user == null) {
            throw new BusinessException("账户不存在");
        }
        //2.校验验证码
        emailCodeService.checkEmailCode(email, emailCode, 1);
        if (userMapper.changePasswordAndRevoke(user.getUserId(), passwordService.hash(password), user.getSessionVersion()) != 1) {
            throw new BusinessException("账户信息已变更，请重新操作");
        }

    }

    @Override
    public void updateUserByUserId(User bean, String userId) {
        if (bean.getPassword() != null || bean.getStatus() != null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        userMapper.updateByUserId(bean, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String userId, String currentPassword, String password) {
        passwordService.validateNewPassword(password);
        accountRateLimiter.requireAllowed("password-change", userId, 5, Duration.ofMinutes(15));
        User user = userMapper.selectByUserId(userId);
        if (user == null || !UserStatusEnum.ENABLE.getStatus().equals(user.getStatus())
                || !passwordService.matches(currentPassword, user.getPassword())) {
            throw new BusinessException("当前密码不正确");
        }
        if (userMapper.changePasswordAndRevoke(userId, passwordService.hash(password), user.getSessionVersion()) != 1) {
            throw new BusinessException("账户信息已变更，请重新登录");
        }
    }

    @Override
    public SessionWebUserDto qqLogin(String code) {
        throw new BusinessException("QQ 登录尚未启用，请使用邮箱登录");
    }

    @Override
    public void updateUserStatus(String userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (userMapper.changeStatusAndRevoke(userId, status) != 1) {
            throw new BusinessException("用户不存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeUserSpace(String userId, Integer changeSpace) {
        if (userId == null || userId.isBlank() || changeSpace == null || changeSpace == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (fileInfoMapper.lockUserForStorage(userId) == null) throw new BusinessException("用户不存在");
        User user = userMapper.selectByUserId(userId);
        long occupied = Objects.requireNonNullElse(fileInfoMapper.selectUseSpace(userId), 0L);
        long total;
        long required;
        try {
            total = Math.addExact(user.getTotalSpace(), Math.multiplyExact(changeSpace.longValue(), Constants.MB));
            required = Math.addExact(occupied, fileUploadService.pendingUploadBytes(userId));
        } catch (ArithmeticException | NullPointerException exception) {
            throw new BusinessException("容量数值超出范围");
        }
        if (total < 0 || total < required) throw new BusinessException("调整后的容量不能小于已占用和上传中空间");
        User update = new User();
        update.setTotalSpace(total);
        update.setUseSpace(occupied);
        if (userMapper.updateByUserId(update, userId) != 1) throw new BusinessException("用户不存在");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    redisUtils.delete(Constants.REDIS_KEY_USER_SPACE_USE + userId);
                } catch (RuntimeException exception) {
                    org.slf4j.LoggerFactory.getLogger(UserServiceImpl.class).warn("容量已提交，缓存失效操作失败", exception);
                }
            }
        });
    }

    @Override
    public void updateNickname(String userId, String nickName) {
        if (nickName == null || nickName.isBlank() || nickName.trim().length() > 20
                || nickName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("昵称须为 1 至 20 个字符，不能包含控制字符");
        }
        User update = new User();
        update.setNickName(nickName.trim());
        if (userMapper.updateByUserId(update, userId) != 1) throw new BusinessException("用户不存在");
    }

    @Override
    public User getUserInfoByUserId(String userId) {
        return this.userMapper.selectByUserId(userId);

    }
}
