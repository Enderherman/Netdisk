package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.service.FileService;

/** 跨目录查看本人的最近更新文件，不把文件夹或回收站内容混入结果。 */
@RestController
public class RecentFilesController extends ABaseController {
    @Resource private FileService files;

    @GetMapping("/file/recent")
    @GlobalInterceptor
    public BaseResponse<?> recent(HttpSession session, Integer pageNo, Integer pageSize) {
        int page = pageNo == null ? 1 : pageNo;
        int size = pageSize == null ? 20 : pageSize;
        if (page < 1 || size < 1 || size > 100) throw new BusinessException(ResponseCodeEnum.CODE_600);
        FileQuery query = new FileQuery();
        query.setUserId(getUserInfoFromSession(session).getUserId());
        query.setDelFlag(2);
        query.setStatus(2);
        query.setFolderType(0);
        query.setPageNo(page);
        query.setPageSize(size);
        query.setOrderBy("last_update_time desc, file_id asc");
        return getSuccessResponse(convert2PaginationVO(files.findListByPage(query), FileInfoVO.class));
    }
}
