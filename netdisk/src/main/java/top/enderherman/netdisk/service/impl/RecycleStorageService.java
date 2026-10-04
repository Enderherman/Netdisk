package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.util.*;
import java.util.stream.Collectors;

/** 回收操作使用同一用户行锁和数据库事务，所有树节点均来自当前用户。 */
@Slf4j
@Service
public class RecycleStorageService {
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private RedisComponent redisComponent;

    @Transactional(rollbackFor = Exception.class)
    public void recycle(String userId, String fileIds) {
        Map<String, FileInfo> files = lockedFiles(userId);
        List<FileInfo> roots = selectedRoots(files, fileIds, FileDeleteFlagEnum.USING.getFlag());
        List<FileInfo> tree = descendants(files, roots, FileDeleteFlagEnum.USING.getFlag());
        Set<String> rootIds = roots.stream().map(FileInfo::getFileId).collect(Collectors.toSet());
        Date now = new Date();
        setState(userId, tree.stream().filter(f -> !rootIds.contains(f.getFileId())).toList(),
                FileDeleteFlagEnum.DELETE.getFlag(), now, null);
        setState(userId, roots, FileDeleteFlagEnum.RECYCLE.getFlag(), now, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recover(String userId, String fileIds) {
        Map<String, FileInfo> files = lockedFiles(userId);
        List<FileInfo> roots = selectedRoots(files, fileIds, FileDeleteFlagEnum.RECYCLE.getFlag());
        List<FileInfo> tree = descendants(files, roots, FileDeleteFlagEnum.DELETE.getFlag());
        Set<String> rootIds = roots.stream().map(FileInfo::getFileId).collect(Collectors.toSet());
        setState(userId, tree.stream().filter(f -> !rootIds.contains(f.getFileId())).toList(),
                FileDeleteFlagEnum.USING.getFlag(), null, null);

        Set<String> names = files.values().stream()
                .filter(f -> FileDeleteFlagEnum.USING.getFlag().equals(f.getDelFlag())
                        && Constants.ZERO_STR.equals(f.getFilePid()))
                .map(FileInfo::getFileName).collect(Collectors.toSet());
        for (FileInfo root : roots) {
            String name = recoveredName(root, names);
            if (!name.equals(root.getFileName())) {
                FileInfo update = new FileInfo();
                update.setFileName(name);
                fileMapper.updateByFileIdAndUserId(update, root.getFileId(), userId);
            }
            names.add(name);
        }
        // 保持现有接口约定：恢复项回到根目录，内部层级不变。
        setState(userId, roots, FileDeleteFlagEnum.USING.getFlag(), null, Constants.ZERO_STR);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId, String fileIds, boolean adminOp) {
        Map<String, FileInfo> files = lockedFiles(userId);
        List<FileInfo> roots = selectedRoots(files, fileIds,
                adminOp ? null : FileDeleteFlagEnum.RECYCLE.getFlag());
        // 永久删除须包含目录里先前独立回收的项，避免留下不可恢复的孤儿。
        setState(userId, descendants(files, roots, null), FileDeleteFlagEnum.FINAL_DELETE.getFlag(),
                new Date(), null);
        User update = new User();
        update.setUseSpace(fileMapper.selectUseSpace(userId));
        userMapper.updateByUserId(update, userId);
        refreshSpaceAfterCommit(userId);
    }

    private Map<String, FileInfo> lockedFiles(String userId) {
        if (StringUtils.isEmpty(userId) || fileMapper.lockUserForStorage(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        return fileMapper.selectList(query).stream().collect(Collectors.toMap(
                FileInfo::getFileId, f -> f, (a, b) -> a, LinkedHashMap::new));
    }

    private List<FileInfo> selectedRoots(Map<String, FileInfo> files, String fileIds, Integer state) {
        if (StringUtils.isEmpty(fileIds)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(fileIds.split(",", -1)));
        for (String id : ids) {
            FileInfo file = files.get(id);
            if (file == null || (state != null && !state.equals(file.getDelFlag()))
                    || FileDeleteFlagEnum.FINAL_DELETE.getFlag().equals(file.getDelFlag())) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        // 独立回收的子项不属于父项的 DELETE 子树；用户同时选中时须分别恢复。
        if (FileDeleteFlagEnum.RECYCLE.getFlag().equals(state)) {
            return ids.stream().map(files::get).toList();
        }
        List<FileInfo> roots = new ArrayList<>();
        for (String id : ids) {
            FileInfo item = files.get(id);
            Set<String> visited = new HashSet<>();
            String parent = item.getFilePid();
            boolean covered = false;
            while (parent != null && !Constants.ZERO_STR.equals(parent) && visited.add(parent)) {
                if (ids.contains(parent)) {
                    covered = true;
                    break;
                }
                FileInfo ancestor = files.get(parent);
                parent = ancestor == null ? null : ancestor.getFilePid();
            }
            if (!covered) roots.add(item);
        }
        if (roots.isEmpty()) throw new BusinessException(ResponseCodeEnum.CODE_600);
        return roots;
    }

    private List<FileInfo> descendants(Map<String, FileInfo> files, List<FileInfo> roots, Integer state) {
        Map<String, List<FileInfo>> children = new HashMap<>();
        for (FileInfo file : files.values()) {
            children.computeIfAbsent(file.getFilePid(), ignored -> new ArrayList<>()).add(file);
        }
        Deque<FileInfo> pending = new ArrayDeque<>(roots);
        Map<String, FileInfo> result = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            FileInfo item = pending.removeFirst();
            if (result.putIfAbsent(item.getFileId(), item) != null) continue;
            if (FileFolderTypeEnum.FOLDER.getType().equals(item.getFolderType())) {
                for (FileInfo child : children.getOrDefault(item.getFileId(), List.of())) {
                    if (state == null || state.equals(child.getDelFlag())) pending.addLast(child);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private void setState(String userId, List<FileInfo> files, int state, Date recoveryTime, String parent) {
        if (files.isEmpty()) return;
        fileMapper.updateRecycleState(userId, files.stream().map(FileInfo::getFileId).toList(),
                state, recoveryTime, new Date(), parent);
    }

    private String recoveredName(FileInfo item, Set<String> names) {
        String original = item.getFileName();
        if (!names.contains(original)) return original;
        boolean folder = FileFolderTypeEnum.FOLDER.getType().equals(item.getFolderType());
        String suffix = folder ? "" : StringUtils.getFileSuffix(original);
        String stem = folder ? original : StringUtils.getFileNameWithoutSuffix(original);
        // 超长扩展名亦不能使新名称超出数据库 200 字符限制。
        if (suffix.length() > 150) suffix = suffix.substring(0, 150);
        for (int number = 1; ; number++) {
            String marker = " (" + number + ")";
            String candidate = stem.substring(0, Math.min(stem.length(), 200 - suffix.length() - marker.length()))
                    + marker + suffix;
            if (!names.contains(candidate)) return candidate;
        }
    }

    private void refreshSpaceAfterCommit(String userId) {
        Runnable refresh = () -> {
            try {
                redisComponent.resetUserSpaceUse(userId);
            } catch (Exception ex) {
                log.warn("文件删除已提交，空间缓存刷新失败，userId={}", userId, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { refresh.run(); }
            });
        } else {
            refresh.run();
        }
    }
}
