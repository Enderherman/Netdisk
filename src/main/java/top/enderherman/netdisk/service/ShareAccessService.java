package top.enderherman.netdisk.service;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.enums.UserStatusEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.FileShareQuery;
import top.enderherman.netdisk.mapper.FileShareMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 分享授权必须依据当前数据，不能只信任已经提取的会话快照。 */
@Service
public class ShareAccessService {
    @Resource
    private FileShareMapper<FileShare, FileShareQuery> fileShareMapper;
    @Resource
    private FileService fileService;
    @Resource
    private UserService userService;

    public FileShare requireActiveShare(String shareId) {
        FileShare share = fileShareMapper.selectByShareId(shareId);
        if (share == null || (share.getExpireTime() != null
                && share.getExpireTime().getTime() <= System.currentTimeMillis())) {
            throw new BusinessException(ResponseCodeEnum.CODE_902);
        }
        User owner = userService.getUserInfoByUserId(share.getUserId());
        if (owner == null || !UserStatusEnum.ENABLE.getStatus().equals(owner.getStatus())) {
            throw new BusinessException(ResponseCodeEnum.CODE_902);
        }
        FileInfo root = fileService.getFileInfoByFileIdAndUserId(share.getFileId(), share.getUserId());
        if (!isActive(root)) {
            throw new BusinessException(ResponseCodeEnum.CODE_902);
        }
        return share;
    }

    public FileInfo requireSharedFile(FileShare share, String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9]+")) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Set<String> visited = new HashSet<>();
        FileInfo requested = null;
        String currentId = fileId;
        while (currentId != null && !Constants.ZERO_STR.equals(currentId) && visited.add(currentId)) {
            FileInfo current = fileService.getFileInfoByFileIdAndUserId(currentId, share.getUserId());
            if (!isActive(current)) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
            if (requested == null) {
                requested = current;
            } else if (!FileFolderTypeEnum.FOLDER.getType().equals(current.getFolderType())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
            if (share.getFileId().equals(current.getFileId())) {
                return requested;
            }
            currentId = current.getFilePid();
        }
        throw new BusinessException(ResponseCodeEnum.CODE_600);
    }

    /** 复制的视频沿用原始分片名；只允许当前分享树中确实引用同一视频的条目。 */
    public FileInfo requirePreviewFile(FileShare share, String fileId) {
        if (fileId == null || !fileId.endsWith(".ts")) {
            return requireSharedFile(share, fileId);
        }
        if (!fileId.matches("[A-Za-z0-9]+_[0-9]+\\.ts")) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        String originalId = fileId.substring(0, fileId.indexOf('_'));
        FileInfo ownedOriginal = fileService.getFileInfoByFileIdAndUserId(originalId, share.getUserId());
        if (ownedOriginal != null) {
            try {
                return requireSharedFile(share, originalId);
            } catch (BusinessException ignored) {
                // 秒传和复制沿用原始分片名，原始条目不在分享树内时继续核验副本。
            }
        }
        FileQuery originalQuery = new FileQuery();
        originalQuery.setFileId(originalId);
        List<FileInfo> originals = ownedOriginal == null ? fileService.findListByParam(originalQuery) : List.of(ownedOriginal);
        for (FileInfo original : originals) {
            if (original.getFilePath() == null) {
                continue;
            }
            FileQuery copiesQuery = new FileQuery();
            copiesQuery.setUserId(share.getUserId());
            copiesQuery.setFilePath(original.getFilePath());
            copiesQuery.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
            for (FileInfo copy : fileService.findListByParam(copiesQuery)) {
                try {
                    return requireSharedFile(share, copy.getFileId());
                } catch (BusinessException ignored) {
                    // 同一视频可以有多个副本，仅共享树内的副本提供授权。
                }
            }
        }
        // 原始数据库行可以在其所有者永久删除后消失；仍存在的副本保留原始存储路径。
        FileQuery retained = new FileQuery();
        retained.setUserId(share.getUserId());
        retained.setFilePathFuzzy(originalId);
        retained.setDelFlag(2);
        retained.setStatus(2);
        retained.setFileCategory(1);
        for (FileInfo copy : fileService.findListByParam(retained)) {
            if (copy.getFilePath() == null || !top.enderherman.netdisk.common.utils.StringUtils
                    .getFileNameWithoutSuffix(copy.getFilePath()).endsWith(originalId)) continue;
            try { return requireSharedFile(share, copy.getFileId()); }
            catch (BusinessException ignored) { /* 只能通过分享树内的副本授权。 */ }
        }
        throw new BusinessException(ResponseCodeEnum.CODE_600);
    }

    public void requireOwnFolder(String userId, String folderId) {
        if (Constants.ZERO_STR.equals(folderId)) {
            return;
        }
        Set<String> visited = new HashSet<>();
        String currentId = folderId;
        while (currentId != null && visited.add(currentId)) {
            FileInfo folder = fileService.getFileInfoByFileIdAndUserId(currentId, userId);
            if (!isActive(folder) || !FileFolderTypeEnum.FOLDER.getType().equals(folder.getFolderType())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
            if (Constants.ZERO_STR.equals(folder.getFilePid())) {
                return;
            }
            currentId = folder.getFilePid();
        }
        throw new BusinessException(ResponseCodeEnum.CODE_600);
    }

    private boolean isActive(FileInfo file) {
        return file != null && FileDeleteFlagEnum.USING.getFlag().equals(file.getDelFlag());
    }
}
