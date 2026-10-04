package top.enderherman.netdisk.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.FileTypeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** 仅清理已永久删除且不再被任何用户引用的存储对象。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "netdisk.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTask {
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;
    @Resource private AppConfig appConfig;

    @Scheduled(cron = "0 5 2 5 * ?")
    @Transactional(rollbackFor = Exception.class)
    public void autoDeleteFile() {
        FileQuery query = new FileQuery();
        query.setDelFlag(FileDeleteFlagEnum.FINAL_DELETE.getFlag());
        List<FileInfo> files = fileMapper.selectList(query);
        for (FileInfo file : files) {
            try {
                if (!FileFolderTypeEnum.FOLDER.getType().equals(file.getFolderType())) {
                    if (StringUtils.isEmpty(file.getFilePath())) {
                        log.warn("永久删除文件缺少存储路径，保留记录待检查：{}", file.getFileId());
                        continue;
                    }
                    List<FileInfo> references = fileMapper.selectStorageReferencesForUpdate(file.getFilePath());
                    boolean retained = references.stream().anyMatch(reference ->
                            !FileDeleteFlagEnum.FINAL_DELETE.getFlag().equals(reference.getDelFlag()));
                    if (!retained) {
                        // 先验证所有路径，任一异常均不能先删原文件再发现封面越界。
                        Path content = storagePath(file.getFilePath());
                        Path cover = StringUtils.isEmpty(file.getFileCover()) ? null : storagePath(file.getFileCover());
                        Path video = FileTypeEnum.VIDEO.getType().equals(file.getFileType())
                                ? storagePath(StringUtils.getFileNameWithoutSuffix(file.getFilePath())) : null;
                        deletePath(content);
                        if (cover != null) deletePath(cover);
                        if (video != null && !video.equals(content)) deletePath(video);
                    }
                }
                // 逐项清理；失败的实体保留其记录，下一轮可继续重试。
                fileMapper.deleteByFileIdAndUserId(file.getFileId(), file.getUserId());
            } catch (IOException | IllegalArgumentException ex) {
                log.warn("文件清理失败，保留记录重试，fileId={}，userId={}",
                        file.getFileId(), file.getUserId(), ex);
            }
        }
    }

    private Path storagePath(String relative) throws IOException {
        Path root = Path.of(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE).toAbsolutePath().normalize();
        Path input = Path.of(relative);
        if (input.isAbsolute()) throw new IllegalArgumentException("不能清理绝对路径");
        Path target = root.resolve(input).normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IllegalArgumentException("清理路径不在存储目录内");
        }
        // 防止存储目录内的链接把删除目标导向其他磁盘位置。
        if (Files.exists(root)) {
            Path realRoot = root.toRealPath();
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("清理路径越过真实存储目录");
            }
        }
        return target;
    }

    private void deletePath(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // walkFileTree 默认不跟随符号链接，目录内链接只删除链接本身。
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
