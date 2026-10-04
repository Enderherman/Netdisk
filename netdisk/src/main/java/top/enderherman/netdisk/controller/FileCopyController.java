package top.enderherman.netdisk.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.service.impl.StorageCopyService;

@RestController
@RequestMapping("/file")
public class FileCopyController extends ABaseController {
    private final StorageCopyService storageCopyService;

    public FileCopyController(StorageCopyService storageCopyService) {
        this.storageCopyService = storageCopyService;
    }

    @PostMapping("/copyFile")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> copyFile(HttpSession session,
                                    @VerifyParam(required = true, max = 11000) String fileIds,
                                    @VerifyParam(required = true, max = 10) String filePid) {
        return getSuccessResponse(CopyUtils.copyList(storageCopyService.copyOwn(
                getUserInfoFromSession(session).getUserId(), fileIds, filePid), FileInfoVO.class));
    }
}
