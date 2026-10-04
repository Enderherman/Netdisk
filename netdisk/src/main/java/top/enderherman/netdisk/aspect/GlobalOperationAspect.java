package top.enderherman.netdisk.aspect;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.common.utils.VerifyUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.enums.UserStatusEnum;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.UserMapper;
import top.enderherman.netdisk.service.AccountSecurityService;
import java.util.Objects;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Slf4j
@Aspect
@Component("GlobalOperationAspect")
public class GlobalOperationAspect {
    @Resource
    private UserMapper<User, UserQuery> userMapper;
    @Resource
    private AccountSecurityService accountSecurityService;

    private static final String[] TYPE_BASE = {"java.lang.String", "java.lang.Integer", "java.lang.Long"};

    @Pointcut("@annotation(top.enderherman.netdisk.annotation.GlobalInterceptor)")
    private void requestInterceptor() {

    }

    @Before("requestInterceptor()")
    public void interceptor(JoinPoint point) throws BusinessException {
        try {
            //1.获取方法相关信息
            Object target = point.getTarget();
            Object[] args = point.getArgs();
            String methodName = point.getSignature().getName();
            Class<?>[] parameterTypes = ((MethodSignature) point.getSignature()).getParameterTypes();
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            GlobalInterceptor interceptor = method.getAnnotation(GlobalInterceptor.class);
            if (interceptor == null) {
                return;
            }
            //2.校验登录
            if (interceptor.checkLogin() || interceptor.checkAdmin()) {
                validateLogin(interceptor.checkAdmin());
            }

            //3.校验参数
            if (interceptor.checkParams()) {
                validateParams(method, args);
            }

        } catch (BusinessException e) {
            log.error("全局拦截器异常", e);
            throw e;
        } catch (Throwable e) {
            log.error("全局拦截器异常", e);
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }


    private void validateLogin(Boolean checkAdmin) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        HttpSession session = request.getSession(false);
        SessionWebUserDto sessionWebUserDto = session == null ? null : (SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY);
        if (sessionWebUserDto == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        User user = userMapper.selectByUserId(sessionWebUserDto.getUserId());
        if (user == null || !UserStatusEnum.ENABLE.getStatus().equals(user.getStatus())
                || !Objects.equals(user.getSessionVersion(), sessionWebUserDto.getSessionVersion())) {
            session.removeAttribute(Constants.SESSION_KEY);
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        sessionWebUserDto.setIsAdmin(accountSecurityService.isAdmin(user.getEmail()));
        sessionWebUserDto.setNickName(user.getNickName());
        sessionWebUserDto.setAvatar(user.getQqAvatar());
        if (checkAdmin && !Boolean.TRUE.equals(sessionWebUserDto.getIsAdmin())) {
            throw new BusinessException(ResponseCodeEnum.CODE_404);
        }
    }

    private void validateParams(Method method, Object[] args) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Object value = args[i];
            VerifyParam verifyParam = parameter.getAnnotation(VerifyParam.class);
            //不校验直接跳过
            if (verifyParam == null)
                continue;

            if (ArrayUtils.contains(TYPE_BASE, parameter.getParameterizedType().getTypeName())) {
                //基本数据类型格式
                checkValue(value, verifyParam);
            } else {
                checkObjValue(parameter, value);
                //对象格式
            }


        }
    }


    private void checkObjValue(Parameter parameter, Object value) {
        try {
            String typeName = parameter.getParameterizedType().getTypeName();
            Class<?> classCur = Class.forName(typeName);
            Field[] fields = classCur.getDeclaredFields();
            for (Field field : fields) {
                VerifyParam fieldVerifyParam = field.getAnnotation(VerifyParam.class);
                if (fieldVerifyParam == null)
                    continue;
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                checkValue(fieldValue, fieldVerifyParam);
            }


        } catch (BusinessException e) {
            log.error("校验参数失败", e);
            throw e;
        } catch (Exception e) {
            log.error("校验参数失败", e);
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
    }

    private void checkValue(Object value, VerifyParam verifyParam) throws BusinessException {
        boolean isEmpty = value == null || StringUtils.isEmpty(value.toString());
        int length = value == null ? 0 : value.toString().length();

        //1.校验空
        if (isEmpty && verifyParam.required()) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        //2.校验长度
        if (!isEmpty && ((verifyParam.max() != -1 && verifyParam.max() < length)
                || (verifyParam.min() != -1 && verifyParam.min() > length))) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        //3.校验正则 首先需要校验正则 再看符不符合正则
        if (!isEmpty && !StringUtils.isEmpty(verifyParam.regex().getRegex()) && !VerifyUtils.verify(verifyParam.regex(), String.valueOf(value))) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

    }


}
