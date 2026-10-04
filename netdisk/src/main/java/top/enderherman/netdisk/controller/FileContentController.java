package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.service.*;

/** 原始内容接口，浏览器可直接预览 PDF、图片和常见音视频。 */
@RestController
public class FileContentController extends ABaseController {
    @Resource private FileService fileService;
    @Resource private FileContentService content;
    @Resource private ShareAccessService shareAccess;

    @GetMapping("/file/content/{fileId}")
    @GlobalInterceptor
    public void own(HttpSession session, HttpServletRequest request, HttpServletResponse response,
                    @PathVariable String fileId, @RequestParam(defaultValue = "false") boolean download) {
        content.send(request, response, fileService.getFileInfoByFileIdAndUserId(fileId, getUserInfoFromSession(session).getUserId()), download);
    }

    @GetMapping("/showShare/content/{shareId}/{fileId}")
    public void shared(HttpSession session, HttpServletRequest request, HttpServletResponse response,
                       @PathVariable String shareId, @PathVariable String fileId,
                       @RequestParam(defaultValue = "false") boolean download) {
        var extracted = getSessionShareFromSession(session, shareId);
        if (extracted == null) throw new BusinessException(ResponseCodeEnum.CODE_903);
        FileShare share = shareAccess.requireActiveShare(shareId);
        if (!share.getUserId().equals(extracted.getShareUserId()) || !share.getFileId().equals(extracted.getFileId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_903);
        }
        content.send(request, response, shareAccess.requireSharedFile(share, fileId), download);
    }

    @GetMapping("/admin/content/{userId}/{fileId}")
    @GlobalInterceptor(checkAdmin = true)
    public void admin(HttpServletRequest request, HttpServletResponse response,
                      @PathVariable String userId, @PathVariable String fileId,
                      @RequestParam(defaultValue = "false") boolean download) {
        content.send(request, response, fileService.getFileInfoByFileIdAndUserId(fileId, userId), download);
    }
}
