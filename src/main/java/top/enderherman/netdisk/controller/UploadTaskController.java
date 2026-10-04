package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.service.impl.UploadTaskService;

@RestController
@RequestMapping("/file")
public class UploadTaskController extends ABaseController {
    @Resource private UploadTaskService uploadTasks;

    @GetMapping("/uploadTasks")
    @GlobalInterceptor
    public BaseResponse<?> list(HttpSession session, Integer pageNo, Integer pageSize, String state) {
        return getSuccessResponse(uploadTasks.list(getUserInfoFromSession(session).getUserId(), pageNo, pageSize, state));
    }

    @GetMapping("/uploadTask/{fileId}")
    @GlobalInterceptor
    public BaseResponse<?> detail(HttpSession session, @PathVariable String fileId) {
        return getSuccessResponse(uploadTasks.detail(getUserInfoFromSession(session).getUserId(), fileId));
    }

    @PostMapping("/cancelUpload/{fileId}")
    @GlobalInterceptor
    public BaseResponse<?> cancel(HttpSession session, @PathVariable String fileId) {
        return getSuccessResponse(uploadTasks.cancel(getUserInfoFromSession(session).getUserId(), fileId));
    }
}
