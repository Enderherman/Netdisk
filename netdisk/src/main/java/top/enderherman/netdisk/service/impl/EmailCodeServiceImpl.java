package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.entity.pojo.EmailCode;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.EmailCodeQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.mapper.EmailCodeMapper;
import top.enderherman.netdisk.mapper.UserMapper;
import top.enderherman.netdisk.service.EmailCodeService;
import top.enderherman.netdisk.service.AccountSecurityService;
import top.enderherman.netdisk.service.AccountRateLimiter;
import top.enderherman.netdisk.common.utils.StringUtils;

import java.util.Date;
import java.time.Duration;
import java.security.SecureRandom;


/**
 * @author Enderherman
 * @date 2024/12/24
 * 邮箱验证码 业务接口
 */
@Slf4j
@Service("emailCodeService")
public class EmailCodeServiceImpl implements EmailCodeService {


    @Resource
    private UserMapper<User, UserQuery> userMapper;

    @Resource
    private EmailCodeMapper<EmailCode, EmailCodeQuery> emailCodeMapper;

    @Resource
    private JavaMailSender javaMailSender;

    @Resource
    private AppConfig appConfig;

    @Resource
    private RedisComponent redisComponent;
    @Resource
    private AccountSecurityService accountSecurityService;
    @Resource
    private AccountRateLimiter accountRateLimiter;
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendEmailCode(String email, Integer type) {
        email = accountSecurityService.normalizeEmail(email);
        if (type == null || (type != 0 && type != 1)) {
            throw new BusinessException("验证码用途不正确");
        }
        if (!accountSecurityService.isEmailVerificationEnabled()) {
            throw new BusinessException("邮件服务尚未配置，暂不支持注册或找回密码");
        }
        accountRateLimiter.requireAllowed("email-cooldown", email, 1, Duration.ofSeconds(60));
        accountRateLimiter.requireAllowed("email-hour", email, 5, Duration.ofHours(1));
        //0注册 1找回
        if (type.equals(Constants.ZERO)) {
            User user = userMapper.selectByEmail(email);
            if (user != null) {
                throw new BusinessException("邮箱已经存在");
            }
        } else if (userMapper.selectByEmail(email) == null) {
            throw new BusinessException("邮箱账号不存在");
        }

        //1.获取验证码
        String code = String.format(java.util.Locale.ROOT, "%05d", random.nextInt(100_000));

        //2.发送验证码给用户
        //3.设置之前验证码为过期
        emailCodeMapper.disableEmailCode(email, type);

        //4.存储验证码
        EmailCode emailCode = new EmailCode(email, code, new Date(), Constants.ZERO, type);
        emailCodeMapper.insert(emailCode);
        send(email, code);

    }

    void send(String toEmail, String code) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            //1.设置发送人
            helper.setFrom(appConfig.getSendUserName());
            helper.setTo(toEmail);
            //2.设置发送主题
            SystemConfig systemConfig = redisComponent.getSystemConfig();
            helper.setSubject(systemConfig.getRegisterEmailTitle());
            //3.设置发送内容
            if (systemConfig.getRegisterEmailContent() == null || !systemConfig.getRegisterEmailContent().contains("%s")) {
                throw new BusinessException("邮件验证码模板必须包含 %s 占位符");
            }
            helper.setText(systemConfig.getRegisterEmailContent().replace("%s", code));
            //4.邮件发送时间
            helper.setSentDate(new Date());
            //5.邮件发送
            javaMailSender.send(message);
        } catch (Exception e) {
            log.error("邮件发送失败", e);
            throw new BusinessException("邮件发送失败");
        }
    }

    /**
     * 校验验证码
     *
     * @param email 邮箱
     * @param code  验证码
     */
    @Override
    public void checkEmailCode(String email, String code, Integer purpose) {
        email = accountSecurityService.normalizeEmail(email);
        if (purpose == null || (purpose != 0 && purpose != 1)) {
            throw new BusinessException("验证码用途不正确");
        }
        accountRateLimiter.requireAllowed("email-verify", email + ":" + purpose, 5, Duration.ofMinutes(15));
        if (code == null || !code.matches("[0-9]{5}") || emailCodeMapper.consumeCode(email, code, purpose,
                new Date(System.currentTimeMillis() - Duration.ofMinutes(15).toMillis()), new Date()) != 1) {
            throw new BusinessException("邮箱验证码错误、已过期或已使用");
        }
    }
}
