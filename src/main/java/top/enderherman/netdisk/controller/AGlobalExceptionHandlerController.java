package top.enderherman.netdisk.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.SafeRequestDiagnostics;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;

@RestControllerAdvice
public class AGlobalExceptionHandlerController extends ABaseController {
    private static final Logger logger = LoggerFactory.getLogger(AGlobalExceptionHandlerController.class);

    @ExceptionHandler(value = Exception.class)
    Object handleException(Exception e, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (response.isCommitted()) throw e;
        SafeRequestDiagnostics.log(logger, SafeRequestDiagnostics.Phase.REQUEST, request, e);
        BaseResponse<?> ajaxResponse = new BaseResponse<>();
        //404
        if (e instanceof HttpRequestMethodNotSupportedException) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            ajaxResponse.setCode(405);
            ajaxResponse.setMessage("请求方法不支持，请使用指定的方法");
            ajaxResponse.setStatus(STATUS_ERROR);
        } else if (e instanceof NoHandlerFoundException) {
            ajaxResponse.setCode(ResponseCodeEnum.CODE_404.getCode());
            ajaxResponse.setMessage(ResponseCodeEnum.CODE_404.getMsg());
            ajaxResponse.setStatus(STATUS_ERROR);
        } else if (e instanceof BusinessException biz) {
            // 业务错误
            ajaxResponse.setCode(biz.getCode() == null ? ResponseCodeEnum.CODE_600.getCode() : biz.getCode());
            ajaxResponse.setMessage(biz.getMessage());
            ajaxResponse.setStatus(STATUS_ERROR);
        } else if (e instanceof BindException || e instanceof MethodArgumentTypeMismatchException) {
            // 参数类型错误
            ajaxResponse.setCode(ResponseCodeEnum.CODE_600.getCode());
            ajaxResponse.setMessage(ResponseCodeEnum.CODE_600.getMsg());
            ajaxResponse.setStatus(STATUS_ERROR);
        } else if (e instanceof DuplicateKeyException) {
            // 主键冲突
            ajaxResponse.setCode(ResponseCodeEnum.CODE_601.getCode());
            ajaxResponse.setMessage(ResponseCodeEnum.CODE_601.getMsg());
            ajaxResponse.setStatus(STATUS_ERROR);
        } else {
            // 全局服务异常
            ajaxResponse.setCode(ResponseCodeEnum.CODE_500.getCode());
            ajaxResponse.setMessage(ResponseCodeEnum.CODE_500.getMsg());
            ajaxResponse.setStatus(STATUS_ERROR);
        }
        return ajaxResponse;
    }
}
