package top.enderherman.netdisk.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.enums.FileDeleteFlagEnum;
import top.enderherman.netdisk.entity.enums.FileFolderTypeEnum;
import top.enderherman.netdisk.entity.enums.FileTypeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;

import java.io.File;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "netdisk.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTask {

    @Resource
    private FileMapper<FileInfo, FileQuery> fileMapper;

    @Resource
    private AppConfig appConfig;

    /**
     * 每月五号自动删除已缓存文件
     */
    @Scheduled(cron = "0 5 2 5 * ?")
    public void autoDeleteFile() {
        Date cur = new Date();
        log.info("当前时间是: {},现在进行文件自动删除", cur);

        //1.查询要删除的文件
        FileQuery query = new FileQuery();
        query.setFolderType(FileFolderTypeEnum.FILE.getType());
        query.setDelFlag(FileDeleteFlagEnum.FINAL_DELETE.getFlag());
        List<FileInfo> files = fileMapper.selectList(query);

        if (files.isEmpty()) {
            log.info("没有待删除的文件，任务结束");
            return;
        }

        // 2. 删除文件及其相关数据
        String srcPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "/";
        for (FileInfo fileInfo : files) {
            String filePath = srcPath + fileInfo.getFilePath();
            deleteFileOrDir(filePath); // 删除文件

            if (FileTypeEnum.IMAGE.getType().equals(fileInfo.getFileType())) {
                String coverPath = srcPath + fileInfo.getFileCover();
                deleteFileOrDir(coverPath); // 删除封面
            }

            if (FileTypeEnum.VIDEO.getType().equals(fileInfo.getFileType())) {
                String coverPath = srcPath + fileInfo.getFileCover();
                deleteFileOrDir(coverPath); // 删除封面
                String tsPath = srcPath + StringUtils.getFileNameWithoutSuffix(fileInfo.getFilePath());
                deleteFileOrDir(tsPath); // 删除分片视频文件夹
            }
        }

        // 3. 删除数据库记录
        fileMapper.deleteByParam(query);
    }

    /**
     * 递归删除文件或文件夹
     *
     * @param path 要删除的文件或文件夹路径
     */
    private void deleteFileOrDir(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            log.warn("文件不存在: {}", path);
            return;
        }

        if (file.isDirectory()) {
            // 递归删除文件夹下的所有内容
            File[] files = file.listFiles();
            if (files != null) {
                for (File subFile : files) {
                    deleteFileOrDir(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空文件夹
        if (file.delete()) {
            log.info("成功删除: {}", path);
        } else {
            log.error("删除失败: {}", path);
        }
    }
}
