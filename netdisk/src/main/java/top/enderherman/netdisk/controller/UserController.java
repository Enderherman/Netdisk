package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.enums.VerifyRegexEnum;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.service.EmailCodeService;
import top.enderherman.netdisk.common.utils.ImageGenerator;
import top.enderherman.netdisk.service.UserService;
import top.enderherman.netdisk.service.AccountSecurityService;
import top.enderherman.netdisk.service.AccountRateLimiter;
import top.enderherman.netdisk.service.AvatarService;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;

/**
 * 用户信息Controller
 */
@Slf4j
@RestController("accountController")
@RequestMapping()
public class UserController extends ABaseController {

    @Resource
    private EmailCodeService emailCodeService;

    @Resource
    private UserService userService;

    @Resource
    private AppConfig appConfig;

    @Resource
    private RedisComponent redisComponent;
    @Resource
    private AccountSecurityService accountSecurityService;
    @Resource
    private AccountRateLimiter accountRateLimiter;
    @Resource
    private AvatarService avatarService;

    @GetMapping("/checkCode")
    public void checkCode(HttpServletResponse response, HttpSession session, Integer type) {
        try {
            //1.生成图片
            ImageGenerator imageGenerator = new ImageGenerator(130, 38, 5, 10);
            //2.设置HTTP响应头
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires", 0);
            response.setContentType("image/jpeg");
            //3.存储验证码到session中
            String code = imageGenerator.getCode();
            //图片验证码是登录用的
            if (type == null || type == 0) {
                session.setAttribute(Constants.CHECK_CODE_KEY, code);
            } else {
                session.setAttribute(Constants.CHECK_CODE_KEY_EMAIL, code);
            }
            //4.将验证码图像写入到响应流中
            imageGenerator.write(response.getOutputStream());
        } catch (IOException e) {
            log.error("error Is:", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/sendEmailCode")
    @GlobalInterceptor(checkLogin = false)
    public BaseResponse<?> sendEmailCode(HttpServletRequest request, HttpSession session,
                                         @VerifyParam(required = true, regex = VerifyRegexEnum.EMAIL, max = 150) String email,
                                         @VerifyParam(required = true) String checkCode,
                                         @VerifyParam(required = true) Integer type) {
        consumeImageCode(session, Constants.CHECK_CODE_KEY_EMAIL, checkCode);
        accountRateLimiter.requireAllowed("email-ip", request.getRemoteAddr(), 20, Duration.ofHours(1));
        emailCodeService.sendEmailCode(email, type);
        return getSuccessResponse(null);
    }

    @PostMapping("/register")
    @GlobalInterceptor(checkLogin = false)
    public BaseResponse<?> register(HttpSession session,
                                    @VerifyParam(required = true, regex = VerifyRegexEnum.EMAIL, max = 150) String email,
                                    @VerifyParam(required = true) String nickName,
                                    @VerifyParam(required = true, max = 64, min = 8) String password,
                                    @VerifyParam(required = true) String checkCode,
                                    @VerifyParam(required = true) String emailCode) {
        consumeImageCode(session, Constants.CHECK_CODE_KEY, checkCode);
        userService.register(email, nickName, password, emailCode);
        return getSuccessResponse(null);
    }

    @PostMapping("/login")
    @GlobalInterceptor(checkLogin = false)
    public BaseResponse<?> login(HttpServletRequest request, HttpSession session,
                                 @VerifyParam(required = true, regex = VerifyRegexEnum.EMAIL, max = 150) String email,
                                 @VerifyParam(required = true) String password,
                                 @VerifyParam(required = true) String checkCode) {
        consumeImageCode(session, Constants.CHECK_CODE_KEY, checkCode);
        accountRateLimiter.requireAllowed("login-ip", request.getRemoteAddr(), 50, Duration.ofMinutes(15));
        SessionWebUserDto sessionWebUserDto = userService.login(email, password);
        request.changeSessionId();
        session.setAttribute(Constants.SESSION_KEY, sessionWebUserDto);
        return getSuccessResponse(sessionWebUserDto);
    }

    @GetMapping("/getAvatar/{userId}")
    @GlobalInterceptor(checkParams = true, checkLogin = false)
    public void getAvatar(HttpServletResponse response,
                          @VerifyParam(required = true) @PathVariable("userId") String userId) {
        byte[] avatar = avatarService.read(userId);
        response.setContentType("image/jpeg");
        response.setHeader("Cache-Control", "no-cache");
        try {
            response.getOutputStream().write(avatar);
        } catch (IOException e) {
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }

    @GetMapping("/getUserInfo")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> getUserInfo(HttpSession session) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        return getSuccessResponse(userDto);
    }


    @RequestMapping("/getUseSpace")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> getUserSpace(HttpSession session) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        UserSpaceDto userSpaceDto = redisComponent.getUserSpace(userDto.getUserId());
        return getSuccessResponse(userSpaceDto);
    }


    @PostMapping("/resetPwd")
    @GlobalInterceptor(checkLogin = false)
    public BaseResponse<?> resetPwd(HttpSession session,
                                    @VerifyParam(required = true) String email,
                                    @VerifyParam(required = true) String password,
                                    @VerifyParam(required = true) String checkCode,
                                    @VerifyParam(required = true) String emailCode) {
        consumeImageCode(session, Constants.CHECK_CODE_KEY, checkCode);
        userService.resetPwd(email, password, emailCode);
        return getSuccessResponse(null);
    }


    @PostMapping("/updatePassword")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> updatePassword(HttpSession session,
                                          @VerifyParam(required = true, max = 64) String currentPassword,
                                          @VerifyParam(required = true, min = 8, max = 64) String password) {
        userService.changePassword(getUserInfoFromSession(session).getUserId(), currentPassword, password);
        session.invalidate();
        return getSuccessResponse(null);
    }

    @PostMapping("/logout")
    public BaseResponse<?> logout(HttpSession session) {
        session.invalidate();
        return getSuccessResponse(null);
    }


    @PostMapping("/updateUserAvatar")
    @GlobalInterceptor
    public BaseResponse<?> updateUserAvatar(HttpSession session, MultipartFile avatar) {
        SessionWebUserDto webUserDto = getUserInfoFromSession(session);
        avatarService.save(webUserDto.getUserId(), avatar);
        User userInfo = new User();
        userInfo.setQqAvatar("");
        userService.updateUserByUserId(userInfo, webUserDto.getUserId());
        webUserDto.setAvatar(null);
        session.setAttribute(Constants.SESSION_KEY, webUserDto);
        return getSuccessResponse(null);
    }

    @PostMapping("/updateProfile")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> updateProfile(HttpSession session, @VerifyParam(required = true, max = 20) String nickName) {
        SessionWebUserDto user = getUserInfoFromSession(session);
        userService.updateNickname(user.getUserId(), nickName);
        user.setNickName(nickName.trim());
        return getSuccessResponse(user);
    }

    @RequestMapping("/qqlogin")
    @GlobalInterceptor(checkParams = true, checkLogin = false)
    public BaseResponse<?> qqLogin(HttpSession session, String callBackUrl) {
        throw new BusinessException("QQ 登录尚未启用，请使用邮箱登录");
    }

    @RequestMapping("/qqlogin/callback")
    @GlobalInterceptor(checkParams = true, checkLogin = false)
    public BaseResponse<?> qqLoginCallback(HttpSession session,
                                           @VerifyParam(required = true) String code,
                                           @VerifyParam(required = true) String state) {
        throw new BusinessException("QQ 登录尚未启用，请使用邮箱登录");
    }

    @GetMapping("/accountCapabilities")
    @GlobalInterceptor(checkLogin = false)
    public BaseResponse<?> accountCapabilities() {
        return getSuccessResponse(Map.of("emailVerificationEnabled", accountSecurityService.isEmailVerificationEnabled(),
                "qqLoginEnabled", false));
    }

    private void consumeImageCode(HttpSession session, String key, String supplied) {
        String expected = (String) session.getAttribute(key);
        session.removeAttribute(key);
        if (expected == null || supplied == null || !expected.equalsIgnoreCase(supplied)) {
            throw new BusinessException("图片验证码错误或已使用");
        }
    }
}
