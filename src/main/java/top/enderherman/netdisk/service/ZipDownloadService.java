package top.enderherman.netdisk.service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.ZipDownloadDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ZipDownloadService {
    public static final String REDIS_PREFIX = "netdisk:zip-download:";
    public static final int MAX_SELECTION = 1000;
    public static final int MAX_ENTRIES = 10000;
    private static final int MAX_PATH_BYTES = 4096;
    private static final long TTL_SECONDS = 300;
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private UserMapper<User, UserQuery> userMapper;
    @Resource private FileContentService content;
    @Resource private RedisUtils<Object> redis;
    @Resource private PlatformTransactionManager transactions;
    private final SecureRandom random = new SecureRandom();

    public String create(String userId, String fileIds) {
        if (fileIds == null || fileIds.length() > 11000) fail("请选择需要打包的文件");
        String[] parts = fileIds.split(",", -1);
        if (parts.length > MAX_SELECTION) fail("每次最多选择 1000 项");
        ZipDownloadDto token = plan(userId, Arrays.asList(parts), null);
        token.setExpiresAt(System.currentTimeMillis() + TTL_SECONDS * 1000);
        byte[] entropy = new byte[32]; random.nextBytes(entropy);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        if (!redis.setEx(REDIS_PREFIX + code, token, TTL_SECONDS)) {
            throw new BusinessException(500, "下载链接保存失败，请稍后重试");
        }
        return code;
    }

    public void download(String code, HttpServletResponse response) throws IOException {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) fail("下载链接无效或已过期");
        Object stored = redis.get(REDIS_PREFIX + code);
        if (!(stored instanceof ZipDownloadDto token) || token.getExpiresAt() <= System.currentTimeMillis()
                || token.getSessionVersion() == null || token.getRootFileIds() == null || token.getEntries() == null) {
            fail("下载链接无效或已过期");
        }
        ZipDownloadDto token = (ZipDownloadDto) stored;
        ZipDownloadDto current = plan(token.getUserId(), token.getRootFileIds(), token.getSessionVersion());
        if (!Objects.equals(token.getEntries(), current.getEntries())) {
            fail("文件或目录已变化，请重新创建下载链接");
        }
        requireOwner(token.getUserId(), token.getSessionVersion());
        response.setContentType("application/zip");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Accept-Ranges", "none");
        response.setHeader("Content-Disposition", "attachment; filename=\"netdisk.zip\"; filename*=UTF-8''"
                + URLEncoder.encode(current.getArchiveName(), StandardCharsets.UTF_8).replace("+", "%20"));
        StreamingZip zip = new StreamingZip(response.getOutputStream());
        byte[] buffer = new byte[64 * 1024];
        try {
            for (ZipDownloadDto.Entry entry : current.getEntries()) {
                requireCurrentEntry(token, entry);
                ZipEntry item = new ZipEntry(entry.getEntryName());
                item.setTime(entry.getModifiedAt());
                if (entry.isDirectory()) {
                    zip.putNextEntry(item); zip.closeEntry();
                    continue;
                }
                Path path = safeFile(entry);
                try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    zip.putNextEntry(item);
                    var digest = org.apache.commons.codec.digest.DigestUtils.getMd5Digest();
                    long remaining = entry.getSize();
                    long recheckAt = System.nanoTime();
                    while (remaining > 0) {
                        if (System.nanoTime() >= recheckAt) {
                            requireCurrentEntry(token, entry);
                            recheckAt = System.nanoTime() + 250_000_000L;
                        }
                        int length = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (length < 0) throw new IOException("源文件在传输期间被截断");
                        digest.update(buffer, 0, length);
                        zip.write(buffer, 0, length);
                        remaining -= length;
                    }
                    if (input.read() != -1) throw new IOException("源文件在传输期间发生变化");
                    if (entry.getFileMd5() != null && entry.getFileMd5().matches("[A-Fa-f0-9]{32}")
                            && !entry.getFileMd5().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
                        throw new IOException("源文件内容校验失败");
                    }
                    safeFile(entry);
                    requireCurrentEntry(token, entry);
                    zip.closeEntry();
                }
            }
            requireOwner(token.getUserId(), token.getSessionVersion());
            // 只在完整成功后写入中央目录，任何异常都不能形成看似成功的部分 ZIP。
            zip.finish();
            zip.flush();
        } catch (IOException | RuntimeException exception) {
            if (!response.isCommitted()) {
                response.reset();
                if (exception instanceof BusinessException business) throw business;
                throw new BusinessException(500, "ZIP 下载失败，请重新创建下载链接");
            }
            throw new IOException("ZIP 下载已中断，归档未完成", exception);
        } finally {
            zip.release();
        }
    }

    private ZipDownloadDto plan(String userId, List<String> selection, Long version) {
        if (userId == null || !userId.matches("[A-Za-z0-9]{1,15}") || selection == null
                || selection.isEmpty() || selection.size() > MAX_SELECTION) fail("打包参数不正确");
        for (String id : selection) {
            if (id == null || !id.matches("[A-Za-z0-9]{1,10}")) fail("打包参数不正确");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            if (fileMapper.lockUserForStorage(userId) == null) throw new BusinessException(ResponseCodeEnum.CODE_901);
            User owner = requireOwner(userId, version);
            FileQuery query = new FileQuery(); query.setUserId(userId); query.setDelFlag(2);
            Map<String, FileInfo> files = fileMapper.selectList(query).stream()
                    .collect(Collectors.toMap(FileInfo::getFileId, file -> file));
            Set<String> selected = new LinkedHashSet<>(selection);
            for (String id : selected) {
                if (!files.containsKey(id)) fail("源文件或目录不存在或已失效");
                requireChain(files, id);
            }
            List<String> roots = selected.stream().filter(id -> Collections.disjoint(selected,
                    requireChain(files, files.get(id).getFilePid()))).toList();
            if (roots.isEmpty()) fail("目录结构不正确");
            Map<String, List<FileInfo>> children = files.values().stream().filter(file -> file.getFilePid() != null)
                    .collect(Collectors.groupingBy(FileInfo::getFilePid));
            Map<String, Set<String>> names = new HashMap<>();
            record Pending(FileInfo file, String parent) {}
            Deque<Pending> pending = new ArrayDeque<>();
            roots.forEach(id -> pending.add(new Pending(files.get(id), "")));
            List<ZipDownloadDto.Entry> entries = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                Pending item = pending.removeFirst(); FileInfo file = item.file();
                if (!visited.add(file.getFileId()) || entries.size() >= MAX_ENTRIES) fail("目录结构异常或超过 10000 个归档条目");
                if (!Integer.valueOf(2).equals(file.getStatus()) || (file.getFolderType() == null
                        || (file.getFolderType() != 0 && file.getFolderType() != 1))) fail("文件尚未完成处理");
                boolean directory = Integer.valueOf(1).equals(file.getFolderType());
                Set<String> occupied = names.computeIfAbsent(item.parent(), ignored -> new HashSet<>());
                String name = FileNames.unique(file.getFileName(), directory, occupied);
                occupied.add(FileNames.key(name));
                String archivePath = item.parent() + name + (directory ? "/" : "");
                if (archivePath.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) fail("归档目录层级或路径过长");
                ZipDownloadDto.Entry entry = new ZipDownloadDto.Entry();
                entry.setFileId(file.getFileId()); entry.setFilePid(file.getFilePid());
                entry.setSourceName(file.getFileName()); entry.setEntryName(archivePath);
                entry.setDirectory(directory); entry.setFileMd5(file.getFileMd5());
                if (directory) {
                    children.getOrDefault(file.getFileId(), List.of()).stream().sorted(Comparator.comparing(FileInfo::getFileId))
                            .forEach(child -> pending.addLast(new Pending(child, archivePath)));
                } else {
                    content.requireUsable(file);
                    entry.setRelativePath(file.getFilePath());
                    try {
                        Path path = content.resolve(file.getFilePath()).toRealPath();
                        long size = Files.size(path);
                        if (!Files.isReadable(path) || file.getFileSize() == null || file.getFileSize() != size) fail("文件实体与记录不一致");
                        entry.setSize(size); entry.setModifiedAt(Files.getLastModifiedTime(path).toMillis());
                    } catch (IOException exception) { throw new BusinessException(ResponseCodeEnum.CODE_404); }
                }
                entries.add(entry);
            }
            List<String> ids = visited.stream().sorted().toList();
            for (int start = 0; start < ids.size(); start += 500) {
                List<String> batch = ids.subList(start, Math.min(start + 500, ids.size()));
                List<FileInfo> locked = fileMapper.selectFilesForUpdate(userId, batch);
                if (locked.size() != batch.size()) fail("文件已发生变化");
                for (FileInfo file : locked) {
                    FileInfo before = files.get(file.getFileId());
                    if (!Integer.valueOf(2).equals(file.getDelFlag()) || !Integer.valueOf(2).equals(file.getStatus())
                            || !Objects.equals(before.getFilePath(), file.getFilePath())
                            || !Objects.equals(before.getFileName(), file.getFileName())
                            || !Objects.equals(before.getFilePid(), file.getFilePid())) fail("文件已发生变化");
                }
            }
            ZipDownloadDto result = new ZipDownloadDto();
            result.setUserId(userId); result.setSessionVersion(owner.getSessionVersion());
            result.setRootFileIds(new ArrayList<>(roots)); result.setEntries(entries);
            result.setArchiveName(roots.size() == 1 ? FileNames.requireValid(files.get(roots.get(0)).getFileName()) + ".zip" : "网盘文件.zip");
            return result;
        });
    }

    /** 返回包括自身的活动祖先链；虚拟根目录 0 不作为归档条目。 */
    private Set<String> requireChain(Map<String, FileInfo> files, String id) {
        Set<String> chain = new HashSet<>(); boolean first = true;
        while (!"0".equals(id)) {
            FileInfo file = files.get(id);
            if (file == null || !chain.add(id) || !Integer.valueOf(2).equals(file.getStatus())
                    || (!first && !Integer.valueOf(1).equals(file.getFolderType()))) fail("源文件或目录不存在或已失效");
            id = file.getFilePid(); first = false;
        }
        return chain;
    }

    private User requireOwner(String userId, Long version) {
        User owner = userMapper.selectByUserId(userId);
        if (owner == null || !Integer.valueOf(1).equals(owner.getStatus()) || owner.getSessionVersion() == null
                || (version != null && !Objects.equals(version, owner.getSessionVersion()))) {
            throw new BusinessException(ResponseCodeEnum.CODE_901);
        }
        return owner;
    }

    private void requireCurrentEntry(ZipDownloadDto token, ZipDownloadDto.Entry entry) {
        requireOwner(token.getUserId(), token.getSessionVersion());
        FileInfo file = fileMapper.selectByFileIdAndUserId(entry.getFileId(), token.getUserId());
        if (file == null || !Integer.valueOf(2).equals(file.getDelFlag()) || !Integer.valueOf(2).equals(file.getStatus())
                || !Objects.equals(entry.getSourceName(), file.getFileName()) || !Objects.equals(entry.getFilePid(), file.getFilePid())
                || !Objects.equals(entry.getFileMd5(), file.getFileMd5())
                || (!entry.isDirectory() && (!Objects.equals(entry.getRelativePath(), file.getFilePath())
                || file.getFileSize() == null || entry.getSize() != file.getFileSize()))) fail("文件在打包期间已发生变化");
    }

    private Path safeFile(ZipDownloadDto.Entry entry) throws IOException {
        Path path = content.resolve(entry.getRelativePath()).toRealPath();
        if (Files.size(path) != entry.getSize() || Files.getLastModifiedTime(path).toMillis() != entry.getModifiedAt()) {
            throw new IOException("文件实体在打包期间已发生变化");
        }
        return path;
    }

    private static class StreamingZip extends ZipOutputStream {
        StreamingZip(OutputStream output) { super(output, StandardCharsets.UTF_8); setLevel(Deflater.BEST_SPEED); }
        void release() { def.end(); }
    }

    private static void fail(String message) { throw new BusinessException(message); }
}
