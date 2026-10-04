package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.UploadTaskDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.enums.UserStatusEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.SimplePage;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class UploadTaskService {
    private static final Set<String> STATES = Set.of("uploading", "completed", "cancelled", "expired");
    @Resource private FileUploadService storage;
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;

    @Transactional(rollbackFor = Exception.class)
    public PaginationResultVO<UploadTaskDto> list(String userId, Integer pageNo, Integer pageSize, String state) {
        requireEnabled(userId, true);
        int page = pageNo == null ? 1 : pageNo;
        int size = pageSize == null ? 20 : pageSize;
        if (page < 1 || size < 1 || (state != null && !state.isBlank() && !STATES.contains(state))) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        size = Math.min(size, 100);
        try {
            Path root = storage.storageRoot();
            Path directory = storage.safe(root, "temp/" + userId);
            List<UploadTaskDto> tasks = new ArrayList<>();
            FileQuery query = new FileQuery(); query.setUserId(userId);
            Map<String, FileInfo> registered = fileMapper.selectList(query).stream()
                    .collect(Collectors.toMap(FileInfo::getFileId, f -> f));
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path task : entries.toList()) {
                        String id = task.getFileName().toString();
                        if (!id.matches("[A-Za-z0-9]{10}") || !Files.isDirectory(task, LinkOption.NOFOLLOW_LINKS)) continue;
                        try {
                            var manifest = storage.readManifest(root, task, userId, id);
                            UploadTaskDto dto = describe(root, task, manifest, registered.get(id), false, registered);
                            if (state == null || state.isBlank() || state.equals(dto.getState())) tasks.add(dto);
                        } catch (IOException | BusinessException invalid) {
                            log.warn("跳过无法读取的上传任务，userId={}，fileId={}", userId, id);
                        }
                    }
                }
            }
            tasks.sort(Comparator.comparingLong(UploadTaskDto::getUpdatedAt).reversed().thenComparing(UploadTaskDto::getFileId));
            SimplePage pagination = new SimplePage(page, tasks.size(), size);
            int from = pagination.getStart();
            List<UploadTaskDto> results = tasks.subList(from, Math.min(tasks.size(), from + size));
            return new PaginationResultVO<>(tasks.size(), size, pagination.getPageNo(), pagination.getPageTotal(), results);
        } catch (IOException ex) { throw new BusinessException("无法读取上传任务列表", ex); }
    }

    @Transactional(rollbackFor = Exception.class)
    public UploadTaskDto detail(String userId, String id) {
        requireEnabled(userId, true);
        validId(id);
        try {
            Path root = storage.storageRoot();
            Path task = storage.safe(root, "temp/" + userId + "/" + id);
            return describe(root, task, storage.readManifest(root, task, userId, id),
                    fileMapper.selectByFileIdAndUserId(id, userId), true, null);
        } catch (IOException ex) { throw new BusinessException("上传任务不存在或无法读取", ex); }
    }

    public UploadTaskDto cancel(String userId, String id) {
        return storage.withLockedTask(userId, id, (root, task, manifest) -> {
            requireEnabled(userId, false);
            FileInfo file = fileMapper.selectByFileIdAndUserId(id, userId);
            if (file != null || manifest.getCompletedStatus() != null) {
                throw new BusinessException("上传已完成，取消任务不会删除网盘原件");
            }
            storage.closeTask(root, task, manifest, "cancelled");
            return describe(root, task, manifest, null, true, null);
        });
    }

    /** 每个任务独立加锁/提交，损坏或越界任务保留且不影响其他任务清理。 */
    public CleanupResult cleanupExpiredTasks() {
        int expired = 0, removed = 0, failed = 0;
        try {
            Path root = storage.storageRoot();
            Path temporary = storage.safe(root, "temp");
            if (!Files.isDirectory(temporary, LinkOption.NOFOLLOW_LINKS)) return new CleanupResult(0, 0, 0);
            try (Stream<Path> users = Files.list(temporary)) {
                for (Path directory : users.toList()) {
                    String userId = directory.getFileName().toString();
                    if (!userId.matches("[A-Za-z0-9]{1,15}") || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (Stream<Path> tasks = Files.list(directory)) {
                        for (Path task : tasks.toList()) {
                            String id = task.getFileName().toString();
                            if (!id.matches("[A-Za-z0-9]{10}") || !Files.isDirectory(task, LinkOption.NOFOLLOW_LINKS)
                                    || !Files.exists(task.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) continue;
                            try {
                                String outcome = storage.withLockedTask(userId, id, (actualRoot, actualTask, manifest) -> {
                                    long age = System.currentTimeMillis() - storage.lastActivity(manifest);
                                    boolean completed = manifest.getCompletedStatus() != null
                                            || fileMapper.selectByFileIdAndUserId(id, userId) != null;
                                    if (completed || manifest.getTerminalState() != null) {
                                        if (age >= storage.receiptTtlMillis()) {
                                            storage.deleteReceipt(actualRoot, actualTask);
                                            return "removed";
                                        }
                                        if (!completed) storage.closeTask(actualRoot, actualTask, manifest, manifest.getTerminalState());
                                    } else if (age >= storage.taskTtlMillis()) {
                                        storage.closeTask(actualRoot, actualTask, manifest, "expired");
                                        return "expired";
                                    }
                                    return "kept";
                                });
                                if (outcome.equals("expired")) expired++;
                                if (outcome.equals("removed")) removed++;
                            } catch (RuntimeException error) {
                                failed++;
                                log.warn("上传任务清理失败，保留待检查，userId={}，fileId={}", userId, id, error);
                            }
                        }
                    } catch (IOException error) { failed++; log.warn("无法扫描用户临时目录：{}", userId, error); }
                }
            }
        } catch (IOException error) { failed++; log.warn("无法扫描上传临时目录", error); }
        return new CleanupResult(expired, removed, failed);
    }

    private UploadTaskDto describe(Path root, Path task, FileUploadService.Manifest manifest,
                                   FileInfo file, boolean detailed, Map<String, FileInfo> registered) throws IOException {
        boolean completed = file != null || manifest.getCompletedStatus() != null;
        String state = completed ? "completed" : manifest.getTerminalState();
        long now = System.currentTimeMillis();
        if (state == null) state = now - storage.lastActivity(manifest) >= storage.taskTtlMillis() ? "expired" : "uploading";
        List<UploadTaskDto.ReceivedChunk> received = new ArrayList<>();
        if (completed || manifest.getTerminalState() == null) {
            for (var entry : manifest.getReceived().entrySet()) {
                Path chunk = storage.safe(root, root.relativize(task.resolve(entry.getKey() + ".chunk")).toString());
                if (completed || (Files.isRegularFile(chunk, LinkOption.NOFOLLOW_LINKS) && Files.size(chunk) == entry.getValue().getSize())) {
                    received.add(new UploadTaskDto.ReceivedChunk(entry.getKey(), entry.getValue().getSize()));
                }
            }
        }
        long temporaryBytes = 0;
        if (!completed) {
            try (Stream<Path> entries = Files.list(task)) {
                for (Path path : entries.toList()) {
                    String name = path.getFileName().toString();
                    if (!name.equals("manifest.json") && !name.equals("task.lock") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        storage.safe(root, root.relativize(path).toString());
                        temporaryBytes = Math.addExact(temporaryBytes, Files.size(path));
                    }
                }
            }
        }
        UploadTaskDto dto = new UploadTaskDto();
        dto.setFileId(manifest.getFileId()); dto.setFileName(manifest.getFileName()); dto.setFilePid(manifest.getFilePid());
        dto.setFileMd5(manifest.getMd5()); dto.setChunks(manifest.getChunks()); dto.setState(state);
        dto.setUploadStatus(manifest.getCompletedStatus()); dto.setReceivedCount(received.size());
        dto.setReceivedBytes(received.stream().mapToLong(UploadTaskDto.ReceivedChunk::size).sum());
        dto.setReceivedChunks(detailed ? received : List.of()); dto.setTemporaryBytes(temporaryBytes);
        dto.setFileSize(file == null ? null : file.getFileSize());
        dto.setFileAvailable(file != null && Integer.valueOf(2).equals(file.getDelFlag()) && Integer.valueOf(2).equals(file.getStatus()));
        if (dto.isFileAvailable()) {
            dto.setActualFileName(file.getFileName());
            dto.setNavigationPath(navigationPath(file, registered));
        }
        dto.setCreatedAt(manifest.getCreatedAt()); dto.setUpdatedAt(storage.lastActivity(manifest));
        dto.setExpiresAt(storage.lastActivity(manifest) + (completed || manifest.getTerminalState() != null
                ? storage.receiptTtlMillis() : storage.taskTtlMillis()));
        return dto;
    }

    /** 返回网盘目录 ID 链，不返回磁盘路径；兼容完成后重命名、移动及自动重名。 */
    private String navigationPath(FileInfo file, Map<String, FileInfo> registered) {
        List<String> parents = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String id = file.getFilePid();
        while (!"0".equals(id)) {
            if (id == null || !visited.add(id) || parents.size() >= 200) return null;
            FileInfo parent = registered == null ? fileMapper.selectByFileIdAndUserId(id, file.getUserId()) : registered.get(id);
            if (parent == null || !Integer.valueOf(1).equals(parent.getFolderType()) || !Integer.valueOf(2).equals(parent.getDelFlag())) return null;
            parents.add(id);
            id = parent.getFilePid();
        }
        Collections.reverse(parents);
        return parents.isEmpty() ? "0" : String.join("/", parents);
    }

    private void requireEnabled(String userId, boolean lock) {
        if (userId == null || !userId.matches("[A-Za-z0-9]{1,15}")
                || (lock && fileMapper.lockUserForStorage(userId) == null)) throw new BusinessException(ResponseCodeEnum.CODE_901);
        User user = userMapper.selectByUserId(userId);
        if (user == null || !UserStatusEnum.ENABLE.getStatus().equals(user.getStatus())) throw new BusinessException(ResponseCodeEnum.CODE_901);
    }
    private void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{10}")) throw new BusinessException(ResponseCodeEnum.CODE_600);
    }
    public record CleanupResult(int expiredTasks, int removedReceipts, int failedTasks) { }
}
