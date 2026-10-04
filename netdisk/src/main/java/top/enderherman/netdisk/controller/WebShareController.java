package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionShareDto;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.entity.vo.ShareInfoVO;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.FileShareService;
import top.enderherman.netdisk.service.UserService;
import top.enderherman.netdisk.service.ShareAccessService;

import java.util.Date;

@RestController("webShareController")
@RequestMapping("/showShare")
public class WebShareController extends ACommonFileController {

    @Resource
    private FileShareService fileShareService;

    @Resource
    private FileService fileService;

    @Resource
    private UserService userService;
    @Resource
    private ShareAccessService shareAccessService;
    @Resource
    private RedisComponent redisComponent;

    /**
     * 获取用户登录信息
     */
    @RequestMapping("/getShareLoginInfo")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public BaseResponse<?> getShareLoginInfo(HttpSession session,
                                             @VerifyParam(required = true) String shareId) {
        SessionShareDto shareSessionDto = getSessionShareFromSession(session, shareId);
        if (shareSessionDto == null) {
            return getSuccessResponse(null);
        }
        ShareInfoVO shareInfoVO = getShareInfoCommon(shareId);
        //判断是否是当前用户分享的文件
        SessionWebUserDto userDto = getUserInfoFromSession(session);
        shareInfoVO.setCurrentUser(userDto != null && userDto.getUserId().equals(shareSessionDto.getShareUserId()));
        return getSuccessResponse(shareInfoVO);
    }

    /**
     * 获取分享信息
     */
    @RequestMapping("/getShareInfo")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public BaseResponse<?> getShareInfo(@VerifyParam(required = true) String shareId) {
        return getSuccessResponse(getShareInfoCommon(shareId));
    }

    /**
     * 校验验证码
     */
    @RequestMapping("/checkShareCode")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public BaseResponse<?> checkShareCode(HttpSession session,
                                          @VerifyParam(required = true) String shareId,
                                          @VerifyParam(required = true) String code) {
        SessionShareDto shareSessionDto = fileShareService.checkShareCode(shareId, code);
        session.setAttribute(Constants.SESSION_SHARE_KEY + shareId, shareSessionDto);
        return getSuccessResponse(null);
    }

    /**
     * 获取文件信息
     */
    @RequestMapping("/loadFileList")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public BaseResponse<?> loadFileList(HttpSession session,
                                        @VerifyParam(required = true) String shareId,
                                        String filePid, Integer pageNo, Integer pageSize) {
        SessionShareDto shareSessionDto = checkShare(session, shareId);
        if ((pageNo != null && pageNo < 1) || (pageSize != null && (pageSize < 1 || pageSize > 100))) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        FileQuery query = new FileQuery();
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        if (!StringUtils.isEmpty(filePid) && !Constants.ZERO_STR.equals(filePid)) {
            FileInfo folder = shareAccessService.requireSharedFile(toShare(shareSessionDto), filePid);
            if (!FileFolderTypeEnum.FOLDER.getType().equals(folder.getFolderType())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
            query.setFilePid(filePid);
        } else {
            query.setFileId(shareSessionDto.getFileId());
        }
        query.setUserId(shareSessionDto.getShareUserId());
        query.setOrderBy("last_update_time desc");
        query.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        PaginationResultVO<FileInfo> resultVO = fileService.findListByPage(query);
        return getSuccessResponse(convert2PaginationVO(resultVO, FileInfoVO.class));
    }

    /**
     * 获取当前目录下文件信息
     *
     * @param path 路径
     */
    @RequestMapping("/getFolderInfo")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public BaseResponse<?> getFolderInfo(HttpSession session,
                                         @VerifyParam(required = true) String shareId,
                                         @VerifyParam(required = true) String path) {
        SessionShareDto shareSessionDto = checkShare(session, shareId);
        for (String folderId : path.split("/", -1)) {
            FileInfo folder = shareAccessService.requireSharedFile(toShare(shareSessionDto), folderId);
            if (!FileFolderTypeEnum.FOLDER.getType().equals(folder.getFolderType())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        return super.getFolderInfo(path, shareSessionDto.getShareUserId());
    }

    /**
     * 获取非视频文件信息
     */
    @RequestMapping("/getFile/{shareId}/{fileId}")
    public void getFile(HttpServletResponse response, HttpSession session,
                        @PathVariable("shareId") @VerifyParam(required = true) String shareId,
                        @PathVariable("fileId") @VerifyParam(required = true) String fileId) {
        SessionShareDto shareSessionDto = checkShare(session, shareId);
        shareAccessService.requirePreviewFile(toShare(shareSessionDto), fileId);
        super.getFile(response, fileId, shareSessionDto.getShareUserId());
    }

    /**
     * 获取视频文件信息
     */
    @RequestMapping("/ts/getVideoInfo/{shareId}/{fileId}")
    public void getVideoInfo(HttpServletResponse response,
                             HttpSession session,
                             @PathVariable("shareId") @VerifyParam(required = true) String shareId,
                             @PathVariable("fileId") @VerifyParam(required = true) String fileId) {
        SessionShareDto shareSessionDto = checkShare(session, shareId);
        shareAccessService.requirePreviewFile(toShare(shareSessionDto), fileId);
        super.getFile(response, fileId, shareSessionDto.getShareUserId());
    }

    /**
     * 创建下载链接 短时时间串
     */
    @RequestMapping("/createDownloadUrl/{shareId}/{fileId}")
    @GlobalInterceptor(checkParams = true, checkLogin = false)
    public BaseResponse<?> getDownloadUrl(HttpSession session,
                                          @VerifyParam(required = true) @PathVariable("shareId") String shareId,
                                          @VerifyParam(required = true) @PathVariable("fileId") String fileId) {
        SessionShareDto shareSessionDto = checkShare(session, shareId);
        FileInfo file = shareAccessService.requireSharedFile(toShare(shareSessionDto), fileId);
        if (FileFolderTypeEnum.FOLDER.getType().equals(file.getFolderType())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        String code = StringUtils.getRandomString(Constants.LENGTH_50);
        DownloadFileDto download = new DownloadFileDto();
        download.setDownloadCode(code);
        download.setFileId(file.getFileId());
        download.setFileName(file.getFileName());
        download.setFilePath(file.getFilePath());
        download.setShareId(shareId);
        download.setUserId(shareSessionDto.getShareUserId());
        redisComponent.saveDownloadCode(code, download);
        return getSuccessResponse(code);
    }

    /**
     * 使用code进行下载
     */
    @RequestMapping("/download/{code}")
    @GlobalInterceptor(checkParams = true, checkLogin = false)
    public void download(HttpServletRequest request,
                         HttpServletResponse response,
                         @VerifyParam(required = true) @PathVariable("code") String code
    ) throws Exception {
        super.download(request, response, code);
    }

    /**
     * 保存分享文件
     */
    @RequestMapping("/saveShare")
    @GlobalInterceptor(checkParams = true)
    public BaseResponse<?> saveShareFile(HttpSession session,
                              @VerifyParam(required = true) String shareId,
                              @VerifyParam(required = true) String shareFileIds,
                              @VerifyParam(required = true) String myFolderId) {
        SessionShareDto shareDto = checkShare(session, shareId);
        SessionWebUserDto webUserDto = getUserInfoFromSession(session);
        if (shareDto.getShareUserId().equals(webUserDto.getUserId())) {
            throw new BusinessException("自己分享的文件无法保存到自己的网盘");
        }
        shareAccessService.requireOwnFolder(webUserDto.getUserId(), myFolderId);
        for (String fileId : shareFileIds.split(",", -1)) {
            shareAccessService.requireSharedFile(toShare(shareDto), fileId);
        }
        fileService.saveShare(shareDto.getFileId(),shareFileIds,myFolderId,shareDto.getShareUserId(), webUserDto.getUserId());
        return getSuccessResponse(null);
    }


    /**
     * 获取分享文件信息
     */
    private ShareInfoVO getShareInfoCommon(String shareId) {
        FileShare share = shareAccessService.requireActiveShare(shareId);
        ShareInfoVO shareInfoVO = CopyUtils.copy(share, ShareInfoVO.class);
        FileInfo fileInfo = fileService.getFileInfoByFileIdAndUserId(share.getFileId(), share.getUserId());
        if (fileInfo == null || !FileDeleteFlagEnum.USING.getFlag().equals(fileInfo.getDelFlag())) {
            throw new BusinessException(ResponseCodeEnum.CODE_902);
        }
        shareInfoVO.setFileName(fileInfo.getFileName());
        User userInfo = userService.getUserInfoByUserId(share.getUserId());
        shareInfoVO.setNickName(userInfo.getNickName());
        shareInfoVO.setAvatar(userInfo.getQqAvatar());
        shareInfoVO.setUserId(userInfo.getUserId());
        //置为空
        shareInfoVO.setFileId(null);
        return shareInfoVO;
    }

    /**
     * 校验分享是否失效
     */
    private SessionShareDto checkShare(HttpSession session, String shareId) {
        SessionShareDto shareSessionDto = getSessionShareFromSession(session, shareId);
        if (shareSessionDto == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_903);
        }
        FileShare share = shareAccessService.requireActiveShare(shareId);
        if (!share.getUserId().equals(shareSessionDto.getShareUserId())
                || !share.getFileId().equals(shareSessionDto.getFileId())) {
            session.removeAttribute(Constants.SESSION_SHARE_KEY + shareId);
            throw new BusinessException(ResponseCodeEnum.CODE_903);
        }
        shareSessionDto.setExpireTime(share.getExpireTime());
        return shareSessionDto;
    }

    private FileShare toShare(SessionShareDto session) {
        FileShare share = new FileShare();
        share.setShareId(session.getShareId());
        share.setUserId(session.getShareUserId());
        share.setFileId(session.getFileId());
        return share;
    }
}
