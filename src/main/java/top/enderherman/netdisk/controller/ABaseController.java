package top.enderherman.netdisk.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionShareDto;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Enderherman
 * @date 2024/12/24
 * 控制器基类
 */
@Slf4j
public class ABaseController {

    protected static final String STATUS_SUCCESS = "success";

    protected static final String STATUS_ERROR = "error";

    /**
     * 响应成功
     *
     * @param t 返回数据
     * @return restful返回类型数据
     * @date 2024/12/24
     */
    protected <T> BaseResponse<T> getSuccessResponse(T t) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setStatus(STATUS_SUCCESS);
        response.setCode(ResponseCodeEnum.CODE_200.getCode());
        response.setMessage(ResponseCodeEnum.CODE_200.getMsg());
        response.setData(t);
        return response;
    }

    /**
     * 业务异常响应失败 请求参数错误
     *
     * @param t 返回数据
     * @param e 异常堆栈
     * @return restful格式返回类型数据
     * @date 2024/12/24
     */
    protected <T> BaseResponse<T> getBusinessErrorResponse(BusinessException e, T t) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setStatus(STATUS_ERROR);
        if (e.getCode() == null) {
            response.setCode(ResponseCodeEnum.CODE_600.getCode());
        } else {
            response.setCode(e.getCode());
        }
        response.setMessage(e.getMessage());
        response.setData(t);
        return response;
    }

    /**
     * 服务器异常
     *
     * @param t 返回数据
     * @return restful格式返回类型数据
     */
    protected <T> BaseResponse<T> getServerErrorResponse(T t) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setStatus(STATUS_ERROR);
        response.setCode(ResponseCodeEnum.CODE_500.getCode());
        response.setMessage(ResponseCodeEnum.CODE_500.getMsg());
        response.setData(t);
        return response;
    }

    /**
     * pojoList -> voList
     * @param sourceVO pojoList
     * @param tClass vo类
     * @param <S> pojoList
     * @param <T> voList
     * @return voList
     */
    protected <S, T> PaginationResultVO<T> convert2PaginationVO(PaginationResultVO<S> sourceVO, Class<T> tClass) {
        PaginationResultVO<T> resultVO = new PaginationResultVO<>();
        resultVO.setList(CopyUtils.copyList(sourceVO.getList(), tClass));
        resultVO.setPageNo(sourceVO.getPageNo());
        resultVO.setPageSize(sourceVO.getPageSize());
        resultVO.setPageTotal(sourceVO.getPageTotal());
        resultVO.setTotalCount(sourceVO.getTotalCount());
        return resultVO;
    }

    /**
     * 将指定路径的文件写入到 HttpServletResponse 输出流中，以实现文件下载或展示功能。
     * @param response HttpServletResponse，用于向客户端写出文件内容。
     * @param filePath 文件的完整路径，用于定位需要输出的文件。
     */
    protected void writeFile(HttpServletResponse response, String filePath) {
        // 1. 校验路径是否合
        if (!StringUtils.verifyPath(filePath)) {
            return;
        }
        OutputStream out = null; // 定义输出流，用于将文件数据写入响应。
        FileInputStream in = null; // 定义输入流，用于读取文件内容。
        try {
            // 2. 检查文件是否存在
            File file = new File(filePath);
            if (!file.exists()) {
                return;
            }
            // 3. 打开文件输入流，准备读取文件内容
            in = new FileInputStream(file);
            byte[] data = new byte[1024]; // 定义缓冲区，用于读取文件内容
            out = response.getOutputStream(); // 获取响应的输出流，用于写入文件内容到客户端。
            int length;
            // 4. 循环读取文件内容，并将数据写入输出流
            while ((length = in.read(data)) != -1) {
                out.write(data, 0, length); // 将读取的内容写入到响应输出流
            }
            out.flush(); // 输出流中数据全部写入客户端
        } catch (Exception e) {
            // 5. 捕获异常，记录错误日志
            log.error("读取文件异常", e);
        } finally {
            // 5. 关闭资源
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    log.error("IO异常", e);
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.error("IO异常", e);
                }
            }
        }
    }


    /**
     * 从session里获取用户信息
     */
    protected SessionWebUserDto getUserInfoFromSession(HttpSession session) {
        return (SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY);
    }

    /**
     * 从session里获取文件分享的登录信息
     */
    protected SessionShareDto getSessionShareFromSession(HttpSession session, String shareId) {
        SessionShareDto sessionShareDto = (SessionShareDto) session.getAttribute(Constants.SESSION_SHARE_KEY + shareId);
        return sessionShareDto;
    }
}
