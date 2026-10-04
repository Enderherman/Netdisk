package top.enderherman.netdisk.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.common.utils.ScaleFiler;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UploadResultDto;
import top.enderherman.netdisk.entity.enums.*;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 任务凭据与分片持久化到磁盘；校验完整原件后才原子落库。 */
@Slf4j
@Service
public class FileUploadService {
    private static final int MAX_CHUNKS = 10_000;
    private static final long MAX_CHUNK_BYTES = 100L * 1024 * 1024;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    static { Arrays.setAll(LOCKS, ignored -> new ReentrantLock()); }
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private RedisComponent redisComponent;
    @Resource private AppConfig appConfig;
    @Resource private ObjectMapper objectMapper;
    @Resource private PlatformTransactionManager transactionManager;

    public UploadResultDto upload(SessionWebUserDto user, String requestedId, MultipartFile part,
                                  String name, String parent, String md5, Integer index, Integer chunks) {
        String fileName = FileNames.requireValid(name);
        validate(user, requestedId, part, parent, md5, index, chunks);
        boolean fresh = requestedId == null || requestedId.isEmpty();
        String id = fresh ? StringUtils.getRandomString(Constants.LENGTH_10) : requestedId;
        String userId = user.getUserId();
        ReentrantLock stripe = LOCKS[Math.floorMod((userId + id).hashCode(), LOCKS.length)];
        stripe.lock();
        Attempt attempt = new Attempt();
        try {
            Path root = storageRoot();
            Path task = safe(root, "temp/" + userId + "/" + id);
            if (fresh) {
                Files.createDirectories(task.getParent());
                Files.createDirectory(task); // 绝不复用客户端指定目录或覆盖碰撞任务。
            } else if (!Files.isDirectory(task, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(task.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException("上传任务不存在，请重新开始上传");
            }
            safe(root, root.relativize(task).toString());
            Path lockPath = safe(root, root.relativize(task.resolve("task.lock")).toString());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                // 让数据库提交/回滚完整发生在任务文件锁释放前，重试不会观察半提交状态。
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                UploadResultDto result;
                boolean failed = true;
                try {
                    result = transaction.execute(status -> {
                        try {
                            return accept(root, task, fresh, userId, id, part, fileName,
                                    parent, md5.toLowerCase(Locale.ROOT), index, chunks, attempt);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
                    failed = false;
                } finally {
                    cleanAttempt(transaction, root, userId, id, attempt, failed);
                }
                if (!UploadStatusEnum.UPLOADING.getCode().equals(result.getStatus())) {
                    try {
                        transaction.execute(status -> {
                            fileMapper.lockUserForStorage(userId);
                            clearCommittedChunks(root, task);
                            return null;
                        });
                    } catch (RuntimeException cleanupFailure) {
                        log.warn("上传已提交，分片清理暂不可用，fileId={}", id, cleanupFailure);
                    }
                    try { redisComponent.resetUserSpaceUse(userId); }
                    catch (Exception ex) { log.warn("上传已提交，空间缓存刷新失败，userId={}", userId, ex); }
                }
                return result;
            }
        } catch (IOException | UncheckedIOException ex) {
            log.warn("上传存储失败，userId={}，fileId={}", userId, id, ex);
            throw new BusinessException("上传失败，无法读写存储文件，请稍后重试", ex);
        } finally {
            stripe.unlock();
        }
    }

    /** 调用者须已开始事务；转存/管理员缩容与上传使用同一用户锁保护预留字节。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long pendingUploadBytes(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9]{1,15}")
                || fileMapper.lockUserForStorage(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        try { return pendingBytes(storageRoot(), userId, null); }
        catch (IOException ex) { throw new BusinessException("无法读取待完成上传的占用空间", ex); }
    }

    private UploadResultDto accept(Path root, Path task, boolean fresh, String userId, String id,
                                   MultipartFile part, String name, String parent, String md5,
                                   int index, int chunks, Attempt attempt) throws IOException {
        if (fileMapper.lockUserForStorage(userId) == null) throw new BusinessException(ResponseCodeEnum.CODE_901);
        User account = userMapper.selectByUserId(userId);
        // 请求鉴权之后可能等待过用户行锁；锁内再次确认账户未被管理员禁用。
        if (account == null || !UserStatusEnum.ENABLE.getStatus().equals(account.getStatus())) {
            throw new BusinessException("账户已禁用，不能继续上传");
        }
        if (account.getTotalSpace() == null || account.getTotalSpace() < 0) {
            throw new BusinessException("用户空间配置无效");
        }
        Manifest manifest;
        if (fresh) {
            if (fileMapper.selectByFileIdAndUserId(id, userId) != null) throw new BusinessException("上传编号冲突，请重试");
            checkParent(userId, parent);
            manifest = new Manifest();
            manifest.userId = userId;
            manifest.fileId = id;
            manifest.fileName = name;
            manifest.filePid = parent;
            manifest.md5 = md5;
            manifest.chunks = chunks;
            manifest.createdAt = System.currentTimeMillis();
            saveManifest(task, manifest);
        } else {
            manifest = objectMapper.readValue(task.resolve("manifest.json").toFile(), Manifest.class);
            if (!userId.equals(manifest.userId) || !id.equals(manifest.fileId) || !name.equals(manifest.fileName)
                    || !parent.equals(manifest.filePid) || !md5.equals(manifest.md5) || chunks != manifest.chunks) {
                throw new BusinessException("上传任务参数已改变，请重新开始上传");
            }
        }
        clearScratch(root, task);
        Path incoming = Files.createTempFile(task, "incoming-", ".part");
        attempt.scratch.add(incoming);
        Chunk incomingInfo = writePart(part, incoming);
        if (incomingInfo.size == 0 && chunks != 1) throw new BusinessException("多分片上传不能包含空分片");
        Chunk accepted = manifest.received.get(index);
        if (accepted != null && !accepted.equals(incomingInfo)) {
            throw new BusinessException("同一分片的内容已改变，请重新开始上传");
        }
        FileInfo completed = fileMapper.selectByFileIdAndUserId(id, userId);
        if (completed != null) {
            if (manifest.completedStatus == null || accepted == null || !md5.equals(completed.getFileMd5())
                    || !FileDeleteFlagEnum.USING.getFlag().equals(completed.getDelFlag())) {
                throw new BusinessException("该上传任务已经结束或文件已被删除");
            }
            return result(id, manifest.completedStatus);
        }
        if (manifest.completedStatus != null) {
            // 已完成文件被永久清理后，旧回执不能用于重新插入相同 ID。
            throw new BusinessException("上传任务已经结束且原件不存在，请重新开始上传");
        }
        checkParent(userId, parent);
        long used = fileMapper.selectUseSpace(userId);
        long pending = pendingBytes(root, userId, incoming);

        if (chunks == 1 && !md5.equals(incomingInfo.md5)) {
            throw new BusinessException("文件完整性校验失败，MD5 与实际内容不符");
        }
        if (fresh && index == 0) {
            FileInfo source = verifiedOwnSource(root, userId, md5);
            if (source != null) {
                ensureQuota(account, used, pending, source.getFileSize());
                FileInfo copy = copyForUpload(source, userId, id, parent, uniqueName(userId, parent, name));
                fileMapper.insert(copy);
                updateSpace(userId, used + copy.getFileSize());
                manifest.received.put(index, incomingInfo);
                manifest.completedStatus = UploadStatusEnum.UPLOAD_SECONDS.getCode();
                attempt.preparedCompletion = true;
                saveManifest(task, manifest);
                return result(id, manifest.completedStatus);
            }
        }

        Path chunkPath = safe(root, root.relativize(task.resolve(index + ".chunk")).toString());
        if (Files.exists(chunkPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(chunkPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(chunkPath) != incomingInfo.size || !digest(chunkPath).equals(incomingInfo.md5)) {
                throw new BusinessException("已保存分片损坏或与重试内容不一致");
            }
            ensureQuota(account, used, pending, 0);
        } else {
            ensureQuota(account, used, pending, incomingInfo.size);
            Files.move(incoming, chunkPath, StandardCopyOption.ATOMIC_MOVE);
        }
        manifest.received.put(index, incomingInfo);
        saveManifest(task, manifest);
        for (int chunk = 0; chunk < chunks; chunk++) {
            Chunk info = manifest.received.get(chunk);
            Path path = safe(root, root.relativize(task.resolve(chunk + ".chunk")).toString());
            if (info == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != info.size) {
                return result(id, UploadStatusEnum.UPLOADING.getCode());
            }
        }

        Path assembly = safe(root, root.relativize(task.resolve("assembly.part")).toString());
        attempt.scratch.add(assembly);
        Chunk merged = assemble(task, manifest, assembly);
        if (!md5.equals(merged.md5)) throw new BusinessException("文件完整性校验失败，MD5 与实际内容不符");
        String suffix = StringUtils.getFileSuffix(name).toLowerCase(Locale.ROOT);
        String diskSuffix = suffix.matches("\\.[a-z0-9]{1,12}") ? suffix : ".bin";
        String relative = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"))
                + "/" + userId + "/" + id + diskSuffix;
        Path target = safe(root, relative);
        Files.createDirectories(target.getParent());
        safe(root, relative);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // 只接受前次失败留下的完整同内容原件，绝不覆盖已有其他内容。
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) != merged.size || !digest(target).equals(md5)) {
                throw new BusinessException("目标存储文件已存在且内容不一致");
            }
        } else {
            Files.move(assembly, target, StandardCopyOption.ATOMIC_MOVE);
            attempt.created.add(target);
        }
        FileTypeEnum type = FileTypeEnum.getFileTypeBySuffix(suffix);
        FileInfo file = new FileInfo();
        file.setFileId(id);
        file.setUserId(userId);
        file.setFilePid(parent);
        file.setFileName(uniqueName(userId, parent, name));
        file.setFileMd5(md5);
        file.setFileSize(merged.size);
        file.setFilePath(relative);
        file.setFolderType(FileFolderTypeEnum.FILE.getType());
        file.setFileCategory(type.getCategory().getCategory());
        file.setFileType(type.getType());
        file.setCreateTime(new Date());
        file.setLastUpdateTime(file.getCreateTime());
        file.setStatus(FileStatusEnum.USING.getStatus());
        file.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        if (type == FileTypeEnum.IMAGE) {
            String coverRelative = relative + ".thumb.jpg";
            Path cover = safe(root, coverRelative);
            if (ScaleFiler.createThumbnail(target, cover, 150)) {
                attempt.created.add(cover);
                file.setFileCover(coverRelative);
            }
        }
        fileMapper.insert(file);
        updateSpace(userId, used + merged.size);
        manifest.completedStatus = UploadStatusEnum.UPLOAD_FINISH.getCode();
        attempt.preparedCompletion = true;
        saveManifest(task, manifest);
        return result(id, manifest.completedStatus);
    }

    private void validate(SessionWebUserDto user, String id, MultipartFile file, String parent,
                          String md5, Integer index, Integer chunks) {
        if (user == null || user.getUserId() == null || !user.getUserId().matches("[A-Za-z0-9]{1,15}")
                || parent == null || !parent.matches("[A-Za-z0-9]{1,10}")
                || md5 == null || !md5.matches("[a-fA-F0-9]{32}") || file == null
                || index == null || chunks == null || chunks < 1 || chunks > MAX_CHUNKS
                || index < 0 || index >= chunks || file.getSize() > MAX_CHUNK_BYTES
                || (file.getSize() == 0 && chunks != 1)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (id == null || id.isEmpty()) {
            if (index != 0) throw new BusinessException("首个请求必须从第 0 个分片开始");
        } else if (!id.matches("[A-Za-z0-9]{10}")) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
    }

    private void checkParent(String userId, String parent) {
        Set<String> seen = new HashSet<>();
        while (!Constants.ZERO_STR.equals(parent)) {
            if (parent == null || !seen.add(parent)) throw new BusinessException("目录结构无效");
            FileInfo folder = fileMapper.selectByFileIdAndUserId(parent, userId);
            if (folder == null || !FileFolderTypeEnum.FOLDER.getType().equals(folder.getFolderType())
                    || !FileDeleteFlagEnum.USING.getFlag().equals(folder.getDelFlag())) {
                throw new BusinessException("上传目录不存在或已被删除");
            }
            parent = folder.getFilePid();
        }
    }

    private FileInfo verifiedOwnSource(Path root, String userId, String md5) throws IOException {
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        query.setFileMd5(md5);
        query.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        query.setStatus(FileStatusEnum.USING.getStatus());
        query.setFolderType(FileFolderTypeEnum.FILE.getType());
        for (FileInfo candidate : fileMapper.selectList(query)) {
            if (StringUtils.isEmpty(candidate.getFilePath())) continue;
            List<FileInfo> references = fileMapper.selectStorageReferencesForUpdate(candidate.getFilePath());
            FileInfo source = references.stream().filter(f -> userId.equals(f.getUserId())
                    && candidate.getFileId().equals(f.getFileId())
                    && FileDeleteFlagEnum.USING.getFlag().equals(f.getDelFlag())
                    && FileStatusEnum.USING.getStatus().equals(f.getStatus())).findFirst().orElse(null);
            if (source == null) continue;
            Path content = safe(root, source.getFilePath());
            if (Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS) && source.getFileSize() != null
                    && source.getFileSize() == Files.size(content) && md5.equals(digest(content))) {
                source.setFileMd5(md5);
                return source;
            }
        }
        return null;
    }

    private FileInfo copyForUpload(FileInfo source, String userId, String id, String parent, String name) {
        FileInfo copy = new FileInfo();
        copy.setFileId(id); copy.setUserId(userId); copy.setFilePid(parent); copy.setFileName(name);
        copy.setFilePath(source.getFilePath()); copy.setFileSize(source.getFileSize()); copy.setFileMd5(source.getFileMd5());
        copy.setFileCover(source.getFileCover()); copy.setFileType(source.getFileType()); copy.setFileCategory(source.getFileCategory());
        copy.setFolderType(FileFolderTypeEnum.FILE.getType()); copy.setStatus(FileStatusEnum.USING.getStatus());
        copy.setDelFlag(FileDeleteFlagEnum.USING.getFlag()); copy.setCreateTime(new Date()); copy.setLastUpdateTime(copy.getCreateTime());
        return copy;
    }

    private String uniqueName(String userId, String parent, String name) {
        FileQuery query = new FileQuery();
        query.setUserId(userId); query.setFilePid(parent); query.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        Set<String> occupied = fileMapper.selectList(query).stream().map(f -> FileNames.key(f.getFileName())).collect(Collectors.toSet());
        return FileNames.unique(name, false, occupied);
    }

    private void updateSpace(String userId, long useSpace) {
        User update = new User(); update.setUseSpace(useSpace); userMapper.updateByUserId(update, userId);
    }

    private void ensureQuota(User user, long used, long pending, long extra) {
        if (used < 0 || pending < 0 || extra < 0 || used > user.getTotalSpace()
                || pending > user.getTotalSpace() - used || extra > user.getTotalSpace() - used - pending) {
            throw new BusinessException(ResponseCodeEnum.CODE_904);
        }
    }

    private long pendingBytes(Path root, String userId, Path exclude) throws IOException {
        Path userTasks = safe(root, "temp/" + userId);
        if (!Files.isDirectory(userTasks, LinkOption.NOFOLLOW_LINKS)) return 0;
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        Set<String> completedIds = fileMapper.selectList(query).stream().map(FileInfo::getFileId).collect(Collectors.toSet());
        long total = 0;
        try (Stream<Path> tasks = Files.list(userTasks)) {
            for (Path task : tasks.toList()) {
                if (!Files.isDirectory(task, LinkOption.NOFOLLOW_LINKS)) continue;
                if (completedIds.contains(task.getFileName().toString())) continue;
                try (Stream<Path> entries = Files.list(task)) {
                    for (Path path : entries.toList()) {
                        String name = path.getFileName().toString();
                        if (!path.equals(exclude) && !name.equals("manifest.json") && !name.equals("task.lock")
                                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            total = Math.addExact(total, Files.size(path));
                        }
                    }
                }
            }
        }
        return total;
    }

    private Chunk writePart(MultipartFile part, Path path) throws IOException {
        MessageDigest digest = DigestUtils.getMd5Digest();
        long size = 0;
        try (InputStream in = part.getInputStream(); OutputStream out = Files.newOutputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > MAX_CHUNK_BYTES) throw new BusinessException("单个分片不能超过 100 MB");
                out.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
        }
        return new Chunk(size, HexFormat.of().formatHex(digest.digest()));
    }

    private Chunk assemble(Path task, Manifest manifest, Path assembly) throws IOException {
        MessageDigest fullDigest = DigestUtils.getMd5Digest();
        long total = 0;
        try (OutputStream out = Files.newOutputStream(assembly, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < manifest.chunks; i++) {
                MessageDigest partDigest = DigestUtils.getMd5Digest();
                long size = 0;
                try (InputStream in = Files.newInputStream(task.resolve(i + ".chunk"))) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count); fullDigest.update(buffer, 0, count); partDigest.update(buffer, 0, count);
                        size += count;
                    }
                }
                Chunk actual = new Chunk(size, HexFormat.of().formatHex(partDigest.digest()));
                if (!actual.equals(manifest.received.get(i))) throw new BusinessException("分片校验失败，请重新开始上传");
                total = Math.addExact(total, size);
            }
        }
        return new Chunk(total, HexFormat.of().formatHex(fullDigest.digest()));
    }

    private String digest(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return DigestUtils.md5Hex(in); }
    }

    private void saveManifest(Path task, Manifest manifest) throws IOException {
        Path temporary = Files.createTempFile(task, "manifest-", ".part");
        try {
            objectMapper.writeValue(temporary.toFile(), manifest);
            Files.move(temporary, task.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path storageRoot() throws IOException {
        if (StringUtils.isEmpty(appConfig.getProjectFolder())) throw new BusinessException("未配置文件存储目录");
        Path root = Path.of(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root.toRealPath();
    }

    private Path safe(Path root, String relative) throws IOException {
        Path input = Path.of(relative);
        Path path = root.resolve(input).normalize();
        if (input.isAbsolute() || path.equals(root) || !path.startsWith(root)) throw new IOException("路径越过存储目录");
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(root)) throw new IOException("真实路径越过存储目录");
        if (Files.isSymbolicLink(path)) throw new IOException("不允许上传路径为符号链接");
        return path;
    }

    private void clearScratch(Path root, Path task) throws IOException {
        try (Stream<Path> entries = Files.list(task)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (name.equals("assembly.part") || name.startsWith("incoming-") || name.startsWith("manifest-")) {
                    Files.deleteIfExists(safe(root, root.relativize(path).toString()));
                }
            }
        }
    }

    private void clearCommittedChunks(Path root, Path task) {
        try (Stream<Path> entries = Files.list(task)) {
            for (Path path : entries.filter(p -> p.getFileName().toString().matches("[0-9]+\\.chunk")).toList()) deleteKnown(root, path);
        } catch (IOException ex) { log.warn("上传已提交，清理分片失败，task={}", task, ex); }
    }

    private void cleanAttempt(TransactionTemplate transaction, Path root, String userId, String id,
                              Attempt attempt, boolean rollback) {
        try {
            transaction.execute(status -> {
                if (fileMapper.lockUserForStorage(userId) == null) return null;
                // 提交结果不确定时先读库；已有原件记录则绝不能清理已提交的内容。
                if (rollback && fileMapper.selectByFileIdAndUserId(id, userId) == null) {
                    try {
                        Path task = safe(root, "temp/" + userId + "/" + id);
                        Path receipt = safe(root, root.relativize(task.resolve("manifest.json")).toString());
                        if (attempt.preparedCompletion && Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
                            Manifest manifest = objectMapper.readValue(receipt.toFile(), Manifest.class);
                            if (userId.equals(manifest.userId) && id.equals(manifest.fileId) && manifest.completedStatus != null) {
                                manifest.completedStatus = null;
                                saveManifest(task, manifest);
                            }
                        }
                    } catch (IOException ex) {
                        log.warn("无法重置已回滚的上传凭据，保留为结束状态，fileId={}", id, ex);
                    }
                    for (Path created : attempt.created) deleteKnown(root, created);
                }
                for (Path scratch : attempt.scratch) deleteKnown(root, scratch);
                return null;
            });
        } catch (RuntimeException cleanupFailure) {
            log.warn("无法确认任务清理状态，保留文件等待重试，fileId={}", id, cleanupFailure);
        }
    }

    private void deleteKnown(Path root, Path path) {
        try { Files.deleteIfExists(safe(root, root.relativize(path).toString())); }
        catch (IOException ex) { log.warn("保留无法清理的任务文件：{}", path, ex); }
    }

    private UploadResultDto result(String id, String status) {
        UploadResultDto result = new UploadResultDto(); result.setFileId(id); result.setStatus(status); return result;
    }

    @Data public static class Manifest {
        private String userId;
        private String fileId;
        private String fileName;
        private String filePid;
        private String md5;
        private int chunks;
        private long createdAt;
        private String completedStatus;
        private Map<Integer, Chunk> received = new TreeMap<>();
    }

    @Data public static class Chunk {
        private long size;
        private String md5;
        public Chunk() { }
        public Chunk(long size, String md5) { this.size = size; this.md5 = md5; }
    }

    private static class Attempt {
        private boolean preparedCompletion;
        private final List<Path> created = new ArrayList<>();
        private final List<Path> scratch = new ArrayList<>();
    }
}
