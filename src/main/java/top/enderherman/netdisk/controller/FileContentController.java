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
import top.enderherman.netdisk.entity.pojo.FileInfo;
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

    @GetMapping("/file/thumbnail/{fileId}")
    @GlobalInterceptor
    public void ownThumbnail(HttpSession session, HttpServletRequest request, HttpServletResponse response,
                             @PathVariable String fileId) {
        thumbnail(request, response, fileService.getFileInfoByFileIdAndUserId(fileId, getUserInfoFromSession(session).getUserId()));
    }

    @GetMapping("/showShare/content/{shareId}/{fileId}")
    public void shared(HttpSession session, HttpServletRequest request, HttpServletResponse response,
                       @PathVariable String shareId, @PathVariable String fileId,
                       @RequestParam(defaultValue = "false") boolean download) {
        content.send(request, response, sharedFile(session, shareId, fileId), download);
    }

    @GetMapping("/showShare/thumbnail/{shareId}/{fileId}")
    public void sharedThumbnail(HttpSession session, HttpServletRequest request, HttpServletResponse response,
                                @PathVariable String shareId, @PathVariable String fileId) {
        thumbnail(request, response, sharedFile(session, shareId, fileId));
    }

    private FileInfo sharedFile(HttpSession session, String shareId, String fileId) {
        var extracted = getSessionShareFromSession(session, shareId);
        if (extracted == null) throw new BusinessException(ResponseCodeEnum.CODE_903);
        FileShare share = shareAccess.requireActiveShare(shareId);
        if (!share.getUserId().equals(extracted.getShareUserId()) || !share.getFileId().equals(extracted.getFileId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_903);
        }
        return shareAccess.requireSharedFile(share, fileId);
    }

    @GetMapping("/admin/content/{userId}/{fileId}")
    @GlobalInterceptor(checkAdmin = true)
    public void admin(HttpServletRequest request, HttpServletResponse response,
                      @PathVariable String userId, @PathVariable String fileId,
                      @RequestParam(defaultValue = "false") boolean download) {
        content.send(request, response, fileService.getFileInfoByFileIdAndUserId(fileId, userId), download);
    }

    @GetMapping("/admin/thumbnail/{userId}/{fileId}")
    @GlobalInterceptor(checkAdmin = true)
    public void adminThumbnail(HttpServletRequest request, HttpServletResponse response,
                               @PathVariable String userId, @PathVariable String fileId) {
        thumbnail(request, response, fileService.getFileInfoByFileIdAndUserId(fileId, userId));
    }

    private void thumbnail(HttpServletRequest request, HttpServletResponse response, FileInfo file) {
        content.requireUsable(file);
        if (file.getFileCover() == null || file.getFileCover().isBlank()) throw new BusinessException(ResponseCodeEnum.CODE_404);
        content.sendPath(request, response, file.getFileCover(), "thumbnail.jpg", false);
    }
}
