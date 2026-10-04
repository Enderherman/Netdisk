package top.enderherman.netdisk.controller;


import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.query.FileShareQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.service.FileShareService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 分享信息 Controller
 */
@RestController("fileShareController")
@RequestMapping("/share")
public class FileShareController extends ABaseController {

    @Resource
    private FileShareService fileShareService;

    /**
     * 查询分享文件
     */
    @RequestMapping("/loadShareList")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> loadDataList(HttpSession session, FileShareQuery query) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        query.setUserId(userDto.getUserId());
        query.setOrderBy("share_time desc");
        //关联查询
        query.setQueryFileName(true);
        PaginationResultVO<FileShare> result = fileShareService.findListByPage(query);
        return getSuccessResponse(result);
    }

    /**
     * 新增分享文件
     */
    @PostMapping("/shareFile")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> shareFile(HttpSession session,
                                     @VerifyParam(required = true) String fileId,
                                     @VerifyParam(required = true) Integer validType,
                                     String code) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        FileShare fileShare = new FileShare();
        fileShare.setUserId(userDto.getUserId());
        fileShare.setFileId(fileId);
        fileShare.setValidType(validType);
        fileShare.setCode(code);
        fileShareService.saveShare(fileShare);
        return getSuccessResponse(fileShare);
    }

    /**
     * 取消分享
     */
    @PostMapping("/cancelShare")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> cancelShare(HttpSession session,
                                       @VerifyParam(required = true) String shareIds) {
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        fileShareService.cancelShare(shareIds,userDto.getUserId());
        return getSuccessResponse(null);
    }


}
