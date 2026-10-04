package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.CopyUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.util.*;
import java.util.stream.Collectors;

/** 复制和分享转存共用目录树、名称、实体引用锁及空间结算规则。 */
@Slf4j
@Service
public class StorageCopyService {
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private RedisComponent redisComponent;
    @Resource private FileUploadService fileUploadService;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void saveShare(String sharedRoot, String fileIds, String destination,
                          String sourceUser, String targetUser) {
        if (sourceUser == null || sourceUser.equals(targetUser)) fail();
        copy(sharedRoot, fileIds, destination, sourceUser, targetUser, false);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public List<FileInfo> copyOwn(String userId, String fileIds, String destination) {
        return copy(null, fileIds, destination, userId, userId, true);
    }

    private List<FileInfo> copy(String sharedRoot, String fileIds, String destination,
                                String sourceUser, String targetUser, boolean ownCopy) {
        if (sourceUser == null || targetUser == null || !sourceUser.matches("[A-Za-z0-9]{1,15}")
                || !targetUser.matches("[A-Za-z0-9]{1,15}")
                || fileIds == null || fileIds.length() > 11000) fail();
        validId(destination);
        String[] selection = fileIds.split(",", -1);
        if (selection.length > 1000) fail();
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(selection));
        selected.forEach(this::validId);
        for (String userId : new TreeSet<>(List.of(sourceUser, targetUser))) {
            if (fileMapper.lockUserForStorage(userId) == null) fail();
        }
        User target = userMapper.selectByUserId(targetUser);
        User source = userMapper.selectByUserId(sourceUser);
        if (!Integer.valueOf(1).equals(target.getStatus()) || !Integer.valueOf(1).equals(source.getStatus())) fail();
        Map<String, FileInfo> sourceFiles = files(sourceUser);
        Map<String, FileInfo> targetFiles = ownCopy ? sourceFiles : files(targetUser);
        requireFolderChain(targetFiles, destination);
        for (String id : selected) {
            FileInfo original = sourceFiles.get(id);
            if (original == null) fail();
            requireFolderChain(sourceFiles, original.getFilePid());
            if (!ownCopy) requireWithin(sourceFiles, id, sharedRoot);
        }
        if (ownCopy && hasSelectedAncestorIncludingSelf(sourceFiles, destination, selected)) {
            throw new BusinessException("不能将目录复制到自身或其子目录");
        }
        List<FileInfo> roots = selected.stream().filter(id -> !hasSelectedAncestor(sourceFiles, id, selected))
                .map(sourceFiles::get).toList();
        if (roots.isEmpty()) fail();
        Map<String, List<FileInfo>> children = sourceFiles.values().stream().filter(file -> file.getFilePid() != null)
                .collect(Collectors.groupingBy(FileInfo::getFilePid));
        Set<String> names = targetFiles.values().stream().filter(file -> destination.equals(file.getFilePid()))
                .map(file -> FileNames.key(FileNames.requireValid(file.getFileName()))).collect(Collectors.toSet());
        Map<String, Set<String>> namesByParent = new HashMap<>();
        namesByParent.put(destination, names);
        Set<String> ids = new HashSet<>(targetFiles.keySet());
        Set<String> visited = new HashSet<>();
        List<FileInfo> copies = new ArrayList<>();
        List<FileInfo> rootCopies = new ArrayList<>();
        Map<String, List<FileInfo>> storageSources = new TreeMap<>();
        record Pending(FileInfo source, String parent, String name) {}
        Deque<Pending> pending = new ArrayDeque<>();
        for (FileInfo root : roots) {
            String name = uniqueName(root, destination, namesByParent);
            pending.add(new Pending(root, destination, name));
        }
        long additionalSize = 0;
        Date now = new Date();
        while (!pending.isEmpty()) {
            Pending item = pending.removeFirst();
            FileInfo original = item.source();
            validId(original.getFileId());
            if (!visited.add(original.getFileId()) || copies.size() >= 10000) fail();
            if (!Integer.valueOf(2).equals(original.getStatus())
                    || (!Integer.valueOf(0).equals(original.getFolderType()) && !Integer.valueOf(1).equals(original.getFolderType()))) fail();
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
            if (destination.equals(item.parent())) rootCopies.add(copy);
            if (Integer.valueOf(1).equals(original.getFolderType())) {
                copy.setFileSize(null);
                for (FileInfo child : children.getOrDefault(original.getFileId(), List.of()).stream()
                        .sorted(Comparator.comparing(FileInfo::getFileId)).toList()) {
                    pending.add(new Pending(child, id, uniqueName(child, id, namesByParent)));
                }
            } else {
                if (original.getFileSize() == null || original.getFileSize() < 0
                        || original.getFilePath() == null || original.getFilePath().isBlank()) fail();
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
        // 先按物理路径锁全部引用，再锁目录及其余源实体，避免跨用户共享内容的锁顺序反转。
        List<String> sourceIds = visited.stream().sorted().toList();
        for (int offset = 0; offset < sourceIds.size(); offset += 500) {
            List<String> batch = sourceIds.subList(offset, Math.min(offset + 500, sourceIds.size()));
            List<FileInfo> locked = fileMapper.selectFilesForUpdate(sourceUser, batch);
            if (locked.size() != batch.size()) fail();
            for (FileInfo current : locked) {
                FileInfo snapshot = sourceFiles.get(current.getFileId());
                if (!Integer.valueOf(2).equals(current.getDelFlag()) || !Integer.valueOf(2).equals(current.getStatus())
                        || !Objects.equals(snapshot.getFilePid(), current.getFilePid())
                        || !Objects.equals(snapshot.getFilePath(), current.getFilePath())
                        || !Objects.equals(snapshot.getFileSize(), current.getFileSize())
                        || !Objects.equals(snapshot.getFileName(), current.getFileName())
                        || !Objects.equals(snapshot.getFolderType(), current.getFolderType())
                        || !Objects.equals(snapshot.getFileMd5(), current.getFileMd5())) fail();
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
        // 零字节副本可能不改变容量，兼容仅返回实际变更行数的 MySQL 连接设置。
        if (!Objects.equals(target.getUseSpace(), update.getUseSpace())
                && userMapper.updateByUserId(update, targetUser) != 1) fail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redisComponent.resetUserSpaceUse(targetUser); }
                catch (Exception ex) { log.warn("复制已提交，容量缓存更新失败，userId={}", targetUser, ex); }
            }
        });
        return rootCopies;
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
            if (file == null || !visited.add(id) || !Integer.valueOf(1).equals(file.getFolderType())
                    || !Integer.valueOf(2).equals(file.getStatus())) fail();
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

    private String uniqueName(FileInfo file, String parent, Map<String, Set<String>> namesByParent) {
        Set<String> names = namesByParent.computeIfAbsent(parent, ignored -> new HashSet<>());
        String name = FileNames.unique(file.getFileName(), Integer.valueOf(1).equals(file.getFolderType()), names);
        names.add(FileNames.key(name));
        return name;
    }

    private boolean hasSelectedAncestorIncludingSelf(Map<String, FileInfo> files, String id, Set<String> selected) {
        Set<String> visited = new HashSet<>();
        while (!"0".equals(id) && visited.add(id)) {
            if (selected.contains(id)) return true;
            FileInfo file = files.get(id);
            if (file == null) fail();
            id = file.getFilePid();
        }
        return false;
    }

    private void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{1,10}")) fail();
    }

    private void fail() { throw new BusinessException(ResponseCodeEnum.CODE_600); }
}
