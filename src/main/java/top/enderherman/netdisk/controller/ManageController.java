package top.enderherman.netdisk.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import top.enderherman.netdisk.annotation.GlobalInterceptor;
import top.enderherman.netdisk.annotation.VerifyParam;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.SystemConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.dto.UserListRequest;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.entity.vo.UserInfoVO;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.UserService;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.Set;

@RequestMapping("/admin")
@RestController("manageController")
public class ManageController extends ACommonFileController {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private UserService userService;
    @Resource
    private FileService fileInfoService;

    /**
     * 获取系统配置
     */
    @RequestMapping("/getSysSettings")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> getSysSettings() {
        return getSuccessResponse(redisComponent.getSystemConfig());
    }

    /**
     * 更新系统配置
     */
    @PostMapping("/saveSysSettings")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> saveSysSettings(
            @VerifyParam(required = true) String registerEmailTitle,
            @VerifyParam(required = true) String registerEmailContent,
            @VerifyParam(required = true) Integer userInitUseSpace) {
        if (registerEmailTitle.length() > 150 || registerEmailTitle.chars().anyMatch(Character::isISOControl)
                || registerEmailContent.length() > 5000 || !registerEmailContent.contains("%s")
                || userInitUseSpace < 1 || userInitUseSpace > 1_048_576) {
            throw new BusinessException("邮件标题不能超过 150 字，模板须包含 %s，初始容量须为 1 至 1048576 MB");
        }
        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setRegisterEMailTitle(registerEmailTitle);
        systemConfig.setRegisterEmailContent(registerEmailContent);
        systemConfig.setUserInitUseSpace(userInitUseSpace);
        redisComponent.saveSystemConfig(systemConfig);
        return getSuccessResponse(null);
    }

    /**
     * 获取所有用户信息
     */
    @RequestMapping("/loadUserList")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> loadUser(UserListRequest request) {
        UserQuery userQuery = request.toQuery();
        PaginationResultVO<User> resultVO = userService.findListByPage(userQuery);
        return getSuccessResponse(convert2PaginationVO(resultVO, UserInfoVO.class));
    }

    /**
     * 更新用户状态
     */
    @PostMapping("/updateUserStatus")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> updateUserStatus(@VerifyParam(required = true) String userId,
                                            @VerifyParam(required = true) Integer status) {
        userService.updateUserStatus(userId, status);
        return getSuccessResponse(null);
    }

    /**
     * 更新用户空间
     */
    @PostMapping("/updateUserSpace")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> updateUserSpace(@VerifyParam(required = true) String userId,
                                           @VerifyParam(required = true) Integer changeSpace
    ) {
        userService.changeUserSpace(userId, changeSpace);
        return getSuccessResponse(null);
    }

    /**
     * 获取所有文件信息
     */
    @RequestMapping("/loadFileList")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> loadDataList(FileQuery query) {
        query.setOrderBy("last_update_time desc");
        query.setQueryNickName(true);
        PaginationResultVO<FileInfo> resultVO = fileInfoService.findListByPage(query);
        return getSuccessResponse(resultVO);
    }

    /**
     * 获取文件夹内信息
     */
    @RequestMapping("/getFolderInfo")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> getFolderInfo(@VerifyParam(required = true) String path) {
        return super.getFolderInfo(path, null);
    }

    /**
     * 获取对应文件
     */
    @RequestMapping("/getFile/{userId}/{fileId}")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public void getFile(HttpServletResponse response,
                        @PathVariable("userId") @VerifyParam(required = true) String userId,
                        @PathVariable("fileId") @VerifyParam(required = true) String fileId) {
        super.getFile(response, fileId, userId);
    }

    /**
     * 获取视频文件
     */
    @RequestMapping("/ts/getVideoInfo/{userId}/{fileId}")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public void getVideoInfo(HttpServletResponse response,
                             @PathVariable("userId") @VerifyParam(required = true) String userId,
                             @PathVariable("fileId") @VerifyParam(required = true) String fileId) {
        super.getFile(response, fileId, userId);
    }

    /**
     * 创建下code
     */
    @PostMapping("/createDownloadUrl/{userId}/{fileId}")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    public BaseResponse<?> createDownloadUrl(@PathVariable("userId") @VerifyParam(required = true) String userId,
                                             @PathVariable("fileId") @VerifyParam(required = true) String fileId) {
        return super.createDownloadUrl(fileId, userId);
    }

    /**
     * 通过code下载文件
     */
    @RequestMapping("/download/{code}")
    @GlobalInterceptor(checkLogin = false, checkParams = true)
    public void download(HttpServletRequest request,
                         HttpServletResponse response,
                         @PathVariable("code") @VerifyParam(required = true) String code) throws Exception {
        super.download(request, response, code);
    }


    /**
     * 彻底删除文件
     */
    @PostMapping("/delFile")
    @GlobalInterceptor(checkParams = true, checkAdmin = true)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BaseResponse<?> delFile(@VerifyParam(required = true) String fileIdAndUserIds) {
        String[] fileIdAndUserIdArray = fileIdAndUserIds.split(",", -1);
        if (fileIdAndUserIdArray.length > 1000) throw new BusinessException(ResponseCodeEnum.CODE_600);
        Map<String, Set<String>> byUser = new TreeMap<>();
        for (String fileIdAndUserId : fileIdAndUserIdArray) {
            if (!fileIdAndUserId.matches("[A-Za-z0-9]{1,10}_[A-Za-z0-9]{1,15}")) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
            String[] itemArray = fileIdAndUserId.split("_", -1);
            byUser.computeIfAbsent(itemArray[1], ignored -> new LinkedHashSet<>()).add(itemArray[0]);
        }
        for (Map.Entry<String, Set<String>> entry : byUser.entrySet()) {
            fileInfoService.deleteFile(entry.getKey(), String.join(",", entry.getValue()), true);
        }
        return getSuccessResponse(null);
    }

}
