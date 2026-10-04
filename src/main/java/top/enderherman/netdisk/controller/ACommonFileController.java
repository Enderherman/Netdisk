package top.enderherman.netdisk.controller;


import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.enums.FileCategoryEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.ShareAccessService;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.common.utils.StringUtils;


import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ACommonFileController extends ABaseController {
    @Resource
    private top.enderherman.netdisk.service.FileContentService contentService;
    @Resource
    private top.enderherman.netdisk.service.UserService contentUserService;

    private HttpServletRequest currentRequest() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }



    @Resource
    private AppConfig appConfig;

    @Resource
    private FileService fileInfoService;

    @Resource
    private RedisComponent redisComponent;
    @Resource
    private ShareAccessService shareAccessService;

    /**
     * 获取缩略图
     *
     * @param response    响应流
     * @param imageFolder 缩略图文件夹
     * @param imageName   缩略图名称
     */
    protected void getImage(HttpServletResponse response, String imageFolder, String imageName) {
        HttpServletRequest request = currentRequest();
        var session = request == null ? null : request.getSession(false);
        var user = session == null ? null : getUserInfoFromSession(session);
        if (user == null) throw new BusinessException(ResponseCodeEnum.CODE_901);
        if (imageFolder == null || !imageFolder.matches("[0-9]{6}") || imageName == null
                || !imageName.matches("[A-Za-z0-9_.-]+")) throw new BusinessException(ResponseCodeEnum.CODE_600);
        String path = imageFolder + "/" + imageName;
        FileQuery query = new FileQuery();
        query.setUserId(user.getUserId());
        query.setFileCover(path);
        query.setDelFlag(2);
        query.setStatus(2);
        if (fileInfoService.findCountByParam(query) == 0) throw new BusinessException(ResponseCodeEnum.CODE_404);
        contentService.sendPath(request, response, path, imageName, false);
    }


    /**
     * 获取文件
     *
     * @param response 响应流
     * @param fileId   文件id
     * @param userId   用户id
     */
    protected void getFile(HttpServletResponse response, String fileId, String userId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9]{1,10}(?:_[0-9]+\\.ts)?")) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (fileId.endsWith(".ts")) {
            String originalId = fileId.substring(0, fileId.indexOf('_'));
            FileInfo file = fileInfoService.getFileInfoByFileIdAndUserId(originalId, userId);
            if (file == null || !Integer.valueOf(2).equals(file.getDelFlag()) || !Integer.valueOf(2).equals(file.getStatus())) {
                file = null;
                FileQuery copies = new FileQuery();
                copies.setUserId(userId); copies.setFilePathFuzzy(originalId);
                copies.setDelFlag(2); copies.setStatus(2); copies.setFileCategory(1);
                for (FileInfo candidate : fileInfoService.findListByParam(copies)) {
                    if (candidate.getFilePath() != null
                            && StringUtils.getFileNameWithoutSuffix(candidate.getFilePath()).endsWith(originalId)) {
                        file = candidate; break;
                    }
                }
            }
            contentService.requireUsable(file);
            if (!Integer.valueOf(1).equals(file.getFileCategory())) throw new BusinessException(ResponseCodeEnum.CODE_600);
            contentService.sendPath(currentRequest(), response,
                    StringUtils.getFileNameWithoutSuffix(file.getFilePath()) + "/" + fileId, fileId, false);
        } else {
            FileInfo file = fileInfoService.getFileInfoByFileIdAndUserId(fileId, userId);
            contentService.requireUsable(file);
            if (FileCategoryEnum.VIDEO.getCategory().equals(file.getFileCategory())) {
                contentService.sendPath(currentRequest(), response,
                        StringUtils.getFileNameWithoutSuffix(file.getFilePath()) + "/" + Constants.M3U8_NAME,
                        Constants.M3U8_NAME, false);
            } else {
                contentService.send(currentRequest(), response, file, false);
            }
        }
    }

    //获取当前目录
    protected BaseResponse<?> getFolderInfo(String path, String userId) {
        if (path == null || path.length() > 2200 || !path.matches("[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*")) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        String[] pathArray = java.util.Arrays.stream(path.split("/"))
                .filter(id -> !Constants.ZERO_STR.equals(id)).distinct().toArray(String[]::new);
        if (pathArray.length == 0) {
            return getSuccessResponse(java.util.Collections.emptyList());
        }
        for (String id : pathArray) {
            if (id.length() > 10) throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        FileQuery fileInfoQuery = new FileQuery();
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setFolderType(FileFolderTypeEnum.FOLDER.getType());
        fileInfoQuery.setDelFlag(top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum.USING.getFlag());
        fileInfoQuery.setFileIdArray(pathArray);
        List<FileInfo> fileInfoList = fileInfoService.findListByParam(fileInfoQuery);
        java.util.Map<String, FileInfo> byId = new java.util.HashMap<>();
        fileInfoList.forEach(file -> byId.put(file.getFileId(), file));
        java.util.List<FileInfo> ordered = new java.util.ArrayList<>();
        for (String id : pathArray) {
            FileInfo file = byId.get(id);
            if (file == null) throw new BusinessException(ResponseCodeEnum.CODE_600);
            ordered.add(file);
        }
        return getSuccessResponse(CopyUtils.copyList(ordered, FileInfoVO.class));
    }

    /**
     * 有时效性的获取下载链接
     */
    protected BaseResponse<?> createDownloadUrl(String fileId, String userId) {
        var owner = contentUserService.getUserInfoByUserId(userId);
        if (owner == null || !Integer.valueOf(1).equals(owner.getStatus()) || owner.getSessionVersion() == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        FileInfo fileInfo = fileInfoService.getFileInfoByFileIdAndUserId(fileId, userId);
        contentService.requireUsable(fileInfo);
        contentService.resolve(fileInfo.getFilePath());

        //token
        String code = StringUtils.getRandomString(Constants.LENGTH_50);
        DownloadFileDto fileDto = new DownloadFileDto();
        fileDto.setDownloadCode(code);
        fileDto.setFileId(fileId);
        fileDto.setUserId(userId);
        fileDto.setSessionVersion(owner.getSessionVersion());
        fileDto.setFileName(fileInfo.getFileName());
        fileDto.setFilePath(fileInfo.getFilePath());
        redisComponent.saveDownloadCode(code, fileDto);
        return getSuccessResponse(code);
    }

    protected void download(HttpServletRequest request, HttpServletResponse response, String code) throws Exception {
        DownloadFileDto downloadFileDto = redisComponent.getDownloadCode(code);
        if (downloadFileDto == null){
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (downloadFileDto.getShareId() != null) {
            FileShare share = shareAccessService.requireActiveShare(downloadFileDto.getShareId());
            if (!share.getUserId().equals(downloadFileDto.getUserId())) {
                throw new BusinessException(ResponseCodeEnum.CODE_902);
            }
            FileInfo file = shareAccessService.requireSharedFile(share, downloadFileDto.getFileId());
            if (!java.util.Objects.equals(file.getFilePath(), downloadFileDto.getFilePath())) {
                throw new BusinessException(ResponseCodeEnum.CODE_902);
            }
        }
        if (downloadFileDto.getUserId() == null || downloadFileDto.getFileId() == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        var owner = contentUserService.getUserInfoByUserId(downloadFileDto.getUserId());
        if (owner == null || !Integer.valueOf(1).equals(owner.getStatus())) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        if (downloadFileDto.getShareId() == null && (downloadFileDto.getSessionVersion() == null
                || !java.util.Objects.equals(owner.getSessionVersion(), downloadFileDto.getSessionVersion()))) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        FileInfo current = fileInfoService.getFileInfoByFileIdAndUserId(downloadFileDto.getFileId(), downloadFileDto.getUserId());
        contentService.requireUsable(current);
        if (!java.util.Objects.equals(current.getFilePath(), downloadFileDto.getFilePath())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        contentService.send(request, response, current, true);
    }

}
