package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.enums.FileCategoryEnum;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.service.FileService;

@RestController
@RequestMapping("/recycle")
public class RecycleController extends ABaseController {
    @Resource
    private FileService fileService;

    /**
     * 查询回收站文件
     */
    @RequestMapping("loadRecycleList")
    @GlobalInterceptor
    public BaseResponse<?> loadRecycleList(HttpSession session, Integer pageNo, Integer pageSize) {
        //设置查询相关信息
        FileQuery query = new FileQuery();
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        query.setUserId(getUserInfoFromSession(session).getUserId());
        query.setOrderBy("recovery_time desc");
        query.setDelFlag(FileDeleteFlagEnum.RECYCLE.getFlag());
        //设置返回结果
        PaginationResultVO<?> resultVO = fileService.findListByPage(query);
        return getSuccessResponse(convert2PaginationVO(resultVO, FileInfoVO.class));
    }

    /**
     * 还原重命名文件
     */
    @PostMapping("recoverFile")
    @GlobalInterceptor
    public BaseResponse<?> recoverFile(HttpSession session,
                                       @VerifyParam(required = true) String fileIds) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        fileService.recoverFile(userDto.getUserId(), fileIds);
        return getSuccessResponse(null);
    }

    /**
     * 彻底删除文件
     */
    @PostMapping("/delFile")
    @GlobalInterceptor
    public BaseResponse<?> deleteFile(HttpSession session,
                                       @VerifyParam(required = true) String fileIds) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        fileService.deleteFile(userDto.getUserId(), fileIds, false);
        return getSuccessResponse(null);
    }
}
