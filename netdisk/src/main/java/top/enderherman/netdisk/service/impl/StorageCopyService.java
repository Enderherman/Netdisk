package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.util.*;
import java.util.stream.Collectors;

/** 转存先校验完整目录树与容量，再在同一事务内插入文件和结算空间。 */
@Slf4j
@Service
public class StorageCopyService {
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private RedisComponent redisComponent;
    @Resource private FileUploadService fileUploadService;

    @Transactional(rollbackFor = Exception.class)
    public void saveShare(String sharedRoot, String fileIds, String destination,
                          String sourceUser, String targetUser) {
        if (sourceUser == null || targetUser == null || sourceUser.equals(targetUser)
                || fileIds == null || fileIds.length() > 11000) fail();
        for (String userId : new TreeSet<>(List.of(sourceUser, targetUser))) {
            if (fileMapper.lockUserForStorage(userId) == null) fail();
        }
        User target = userMapper.selectByUserId(targetUser);
        User source = userMapper.selectByUserId(sourceUser);
        if (!Integer.valueOf(1).equals(target.getStatus()) || !Integer.valueOf(1).equals(source.getStatus())) fail();
        Map<String, FileInfo> sourceFiles = files(sourceUser);
        Map<String, FileInfo> targetFiles = files(targetUser);
        requireFolderChain(targetFiles, destination);
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(fileIds.split(",", -1)));
        if (selected.size() > 1000) fail();
        for (String id : selected) requireWithin(sourceFiles, id, sharedRoot);
        List<FileInfo> roots = selected.stream().filter(id -> !hasSelectedAncestor(sourceFiles, id, selected))
                .map(sourceFiles::get).toList();
        Map<String, List<FileInfo>> children = sourceFiles.values().stream()
                .collect(Collectors.groupingBy(FileInfo::getFilePid));
        Set<String> names = targetFiles.values().stream().filter(file -> destination.equals(file.getFilePid()))
                .map(FileInfo::getFileName).collect(Collectors.toSet());
        Set<String> ids = new HashSet<>(targetFiles.keySet());
        Set<String> visited = new HashSet<>();
        List<FileInfo> copies = new ArrayList<>();
        Map<String, List<FileInfo>> storageSources = new TreeMap<>();
        record Pending(FileInfo source, String parent, String name) {}
        Deque<Pending> pending = new ArrayDeque<>();
        for (FileInfo root : roots) {
            String name = uniqueName(root, names);
            names.add(name);
            pending.add(new Pending(root, destination, name));
        }
        long additionalSize = 0;
        Date now = new Date();
        while (!pending.isEmpty()) {
            Pending item = pending.removeFirst();
            FileInfo original = item.source();
            if (!visited.add(original.getFileId()) || copies.size() >= 10000) fail();
            if (!Integer.valueOf(2).equals(original.getStatus())) fail();
            FileInfo copy = CopyUtils.copy(original, FileInfo.class);
            String id;
            do { id = StringUtils.getRandomString(10); } while (!ids.add(id));
            copy.setFileId(id);
            copy.setUserId(targetUser);
            copy.setFilePid(item.parent());
            copy.setFileName(item.name());
            copy.setCreateTime(now);
            copy.setLastUpdateTime(now);
            copy.setRecoveryTime(null);
            copies.add(copy);
            if (Integer.valueOf(1).equals(original.getFolderType())) {
                for (FileInfo child : children.getOrDefault(original.getFileId(), List.of())) {
                    pending.add(new Pending(child, id, child.getFileName()));
                }
            } else {
                if (original.getFileSize() == null || original.getFileSize() < 0
                        || original.getFilePath() == null) fail();
                try { additionalSize = Math.addExact(additionalSize, original.getFileSize()); }
                catch (ArithmeticException ex) { throw new BusinessException(ResponseCodeEnum.CODE_904); }
                storageSources.computeIfAbsent(original.getFilePath(), ignored -> new ArrayList<>()).add(original);
            }
        }
        // 清理任务也锁定这些行；必须复核当前引用，不能复制已永久删除的陈旧快照。
        for (var entry : storageSources.entrySet()) {
            List<FileInfo> references = fileMapper.selectStorageReferencesForUpdate(entry.getKey());
            for (FileInfo original : entry.getValue()) {
                boolean alive = references.stream().anyMatch(file -> sourceUser.equals(file.getUserId())
                        && original.getFileId().equals(file.getFileId()) && Integer.valueOf(2).equals(file.getDelFlag())
                        && Integer.valueOf(2).equals(file.getStatus()));
                if (!alive) fail();
            }
        }
        long used = Optional.ofNullable(fileMapper.selectUseSpace(targetUser)).orElse(0L);
        long uploadReserved = fileUploadService.pendingUploadBytes(targetUser);
        if (target.getTotalSpace() == null || used > target.getTotalSpace()
                || uploadReserved > target.getTotalSpace() - used
                || additionalSize > target.getTotalSpace() - used - uploadReserved) {
            throw new BusinessException(ResponseCodeEnum.CODE_904);
        }
        for (int offset = 0; offset < copies.size(); offset += 500) {
            fileMapper.insertBatch(copies.subList(offset, Math.min(offset + 500, copies.size())));
        }
        User update = new User();
        update.setUseSpace(used + additionalSize);
        userMapper.updateByUserId(update, targetUser);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redisComponent.resetUserSpaceUse(targetUser); }
                catch (Exception ex) { log.warn("转存已提交，容量缓存更新失败，userId={}", targetUser, ex); }
            }
        });
    }

    private Map<String, FileInfo> files(String userId) {
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        query.setDelFlag(2);
        return fileMapper.selectList(query).stream().collect(Collectors.toMap(FileInfo::getFileId, file -> file));
    }

    private void requireFolderChain(Map<String, FileInfo> files, String id) {
        Set<String> visited = new HashSet<>();
        while (!"0".equals(id)) {
            FileInfo file = files.get(id);
            if (file == null || !visited.add(id) || !Integer.valueOf(1).equals(file.getFolderType())) fail();
            id = file.getFilePid();
        }
    }

    private void requireWithin(Map<String, FileInfo> files, String id, String root) {
        Set<String> visited = new HashSet<>();
        boolean first = true;
        while (id != null && visited.add(id)) {
            FileInfo file = files.get(id);
            if (file == null || (!first && !Integer.valueOf(1).equals(file.getFolderType()))) fail();
            if (id.equals(root)) return;
            id = file.getFilePid();
            first = false;
        }
        fail();
    }

    private boolean hasSelectedAncestor(Map<String, FileInfo> files, String id, Set<String> selected) {
        String parent = files.get(id).getFilePid();
        Set<String> visited = new HashSet<>();
        while (files.containsKey(parent) && visited.add(parent)) {
            if (selected.contains(parent)) return true;
            parent = files.get(parent).getFilePid();
        }
        return false;
    }

    private String uniqueName(FileInfo file, Set<String> names) {
        String original = file.getFileName();
        if (!names.contains(original)) return original;
        String suffix = Integer.valueOf(1).equals(file.getFolderType()) ? "" : StringUtils.getFileSuffix(original);
        if (suffix.length() > 150) suffix = suffix.substring(0, 150);
        String stem = Integer.valueOf(1).equals(file.getFolderType()) ? original : StringUtils.getFileNameWithoutSuffix(original);
        for (int i = 1; ; i++) {
            String marker = " (" + i + ")";
            String name = stem.substring(0, Math.min(stem.length(), 200 - marker.length() - suffix.length())) + marker + suffix;
            if (!names.contains(name)) return name;
        }
    }

    private void fail() { throw new BusinessException(ResponseCodeEnum.CODE_600); }
}
