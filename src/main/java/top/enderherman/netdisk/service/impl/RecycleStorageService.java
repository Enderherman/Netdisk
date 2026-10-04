package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.RecyclePolicySettings;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.enums.UserStatusEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Predicate;

/** 回收操作使用同一用户行锁和数据库事务，所有树节点均来自当前用户。 */
@Slf4j
@Service
public class RecycleStorageService {
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private RedisComponent redisComponent;
    @Resource private RecyclePolicySettings recyclePolicy;

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
        Set<String> restoring = tree.stream().map(FileInfo::getFileId).collect(Collectors.toSet());
        Map<String, String> parents = restoreParents(files, roots, restoring, rootIds);
        Map<String, Set<String>> names = new HashMap<>();
        for (FileInfo existing : files.values()) {
            if (FileDeleteFlagEnum.USING.getFlag().equals(existing.getDelFlag()) && !restoring.contains(existing.getFileId())) {
                names.computeIfAbsent(existing.getFilePid(), ignored -> new HashSet<>()).add(FileNames.key(existing.getFileName()));
            }
        }
        for (FileInfo item : tree) {
            String parent = parents.getOrDefault(item.getFileId(), item.getFilePid());
            Set<String> occupied = names.computeIfAbsent(parent, ignored -> new HashSet<>());
            String name = FileNames.unique(item.getFileName(), FileFolderTypeEnum.FOLDER.getType().equals(item.getFolderType()), occupied);
            occupied.add(FileNames.key(name));
            FileInfo update = new FileInfo();
            update.setFileName(name);
            update.setFilePid(parent);
            fileMapper.updateByFileIdAndUserId(update, item.getFileId(), userId);
        }
        setState(userId, tree, FileDeleteFlagEnum.USING.getFlag(), null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId, String fileIds, boolean adminOp) {
        Map<String, FileInfo> files = lockedFiles(userId, adminOp);
        List<FileInfo> roots = selectedRoots(files, fileIds,
                adminOp ? null : FileDeleteFlagEnum.RECYCLE.getFlag());
        permanentlyDelete(userId, files, roots, adminOp);
    }

    @Transactional(rollbackFor = Exception.class)
    public int clear(String userId) {
        Map<String, FileInfo> files = lockedFiles(userId);
        List<FileInfo> roots = files.values().stream()
                .filter(f -> FileDeleteFlagEnum.RECYCLE.getFlag().equals(f.getDelFlag())).toList();
        return permanentlyDelete(userId, files, roots, false);
    }

    /** 截止时刻包含等号；重新锁定/查询，避免清理扫描后已恢复的文件。 */
    @Transactional(rollbackFor = Exception.class)
    public int expireUserTrash(String userId, Date cutoff) {
        if (!recyclePolicy.policy().autoCleanupEnabled()) return 0;
        if (cutoff == null) throw new IllegalArgumentException("缺少过期截止时刻");
        Map<String, FileInfo> files = lockedFiles(userId, true);
        List<FileInfo> roots = files.values().stream().filter(f -> FileDeleteFlagEnum.RECYCLE.getFlag().equals(f.getDelFlag())
                && f.getRecoveryTime() != null && !f.getRecoveryTime().after(cutoff)).toList();
        return permanentlyDelete(userId, files, roots, false);
    }

    private int permanentlyDelete(String userId, Map<String, FileInfo> files, List<FileInfo> roots, boolean includeActive) {
        if (roots.isEmpty()) return 0;
        // 自动过期/清空只沿回收子树遍历；异常数据里的活跃节点也不能被连带清理。
        List<FileInfo> tree = traverse(files, roots, child -> includeActive
                || FileDeleteFlagEnum.DELETE.getFlag().equals(child.getDelFlag())
                || FileDeleteFlagEnum.RECYCLE.getFlag().equals(child.getDelFlag())
                || FileDeleteFlagEnum.FINAL_DELETE.getFlag().equals(child.getDelFlag()));
        List<FileInfo> changes = tree.stream().filter(f -> !FileDeleteFlagEnum.FINAL_DELETE.getFlag().equals(f.getDelFlag())).toList();
        if (changes.isEmpty()) return 0;
        setState(userId, changes, FileDeleteFlagEnum.FINAL_DELETE.getFlag(), new Date(), null);
        User update = new User();
        update.setUseSpace(fileMapper.selectUseSpace(userId));
        userMapper.updateByUserId(update, userId);
        refreshSpaceAfterCommit(userId);
        return changes.size();
    }

    private Map<String, FileInfo> lockedFiles(String userId) {
        return lockedFiles(userId, false);
    }

    private Map<String, FileInfo> lockedFiles(String userId, boolean allowDisabled) {
        if (StringUtils.isEmpty(userId) || fileMapper.lockUserForStorage(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        User account = userMapper.selectByUserId(userId);
        if (account == null || (!allowDisabled && !UserStatusEnum.ENABLE.getStatus().equals(account.getStatus()))) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
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
        return traverse(files, roots, child -> state == null || state.equals(child.getDelFlag()));
    }

    private List<FileInfo> traverse(Map<String, FileInfo> files, List<FileInfo> roots, Predicate<FileInfo> includeChild) {
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
                    if (includeChild.test(child)) pending.addLast(child);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private void setState(String userId, List<FileInfo> files, int state, Date recoveryTime, String parent) {
        if (files.isEmpty()) return;
        List<String> ids = files.stream().map(FileInfo::getFileId).toList();
        Date updated = new Date();
        for (int offset = 0; offset < ids.size(); offset += 500) {
            fileMapper.updateRecycleState(userId, ids.subList(offset, Math.min(offset + 500, ids.size())),
                    state, recoveryTime, updated, parent);
        }
    }

    private Map<String, String> restoreParents(Map<String, FileInfo> files, List<FileInfo> roots,
                                               Set<String> restoring, Set<String> selected) {
        Map<String, String> result = new LinkedHashMap<>();
        for (FileInfo root : roots) {
            boolean valid = true;
            String cursor = root.getFilePid();
            Set<String> seen = new HashSet<>(Set.of(root.getFileId()));
            while (!Constants.ZERO_STR.equals(cursor)) {
                FileInfo parent = files.get(cursor);
                if (parent == null || !FileFolderTypeEnum.FOLDER.getType().equals(parent.getFolderType()) || !seen.add(cursor)
                        || !(FileDeleteFlagEnum.USING.getFlag().equals(parent.getDelFlag()) || restoring.contains(cursor))) {
                    valid = false;
                    break;
                }
                // 同批选中的祖先一定会恢复（必要时回根），不让其无效的旧祖先连带拆散子树。
                if (selected.contains(cursor)) break;
                cursor = parent.getFilePid();
            }
            result.put(root.getFileId(), valid ? root.getFilePid() : Constants.ZERO_STR);
        }
        // 旧数据若存在跨选中项的环，切断一处到根目录，确保最终计划可以抵达根。
        for (FileInfo root : roots) {
            Set<String> seen = new HashSet<>();
            String cursor = root.getFileId();
            while (!Constants.ZERO_STR.equals(cursor) && cursor != null) {
                if (!seen.add(cursor)) { result.put(root.getFileId(), Constants.ZERO_STR); break; }
                FileInfo node = files.get(cursor);
                if (node == null) break;
                cursor = result.getOrDefault(cursor, node.getFilePid());
            }
        }
        return result;
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
