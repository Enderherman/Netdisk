package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.DateUtils;
import top.enderherman.netdisk.common.utils.ProcessUtils;
import top.enderherman.netdisk.common.utils.ScaleFiler;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UploadResultDto;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;
import top.enderherman.netdisk.entity.enums.*;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.SimplePage;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.mapper.UserMapper;
import top.enderherman.netdisk.service.FileService;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service("fileService")
public class FileServiceImpl implements FileService {

    @Resource
    private StorageCopyService storageCopyService;

    @Resource
    private FileMapper<FileInfo, FileQuery> fileMapper;

    @Resource
    private UserMapper<User, UserQuery> userMapper;

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private AppConfig appConfig;

    @Resource
    @Lazy
    private FileServiceImpl fileService;

    @Resource
    private RecycleStorageService recycleStorageService;

    @Resource
    private FileOrganizationService fileOrganizationService;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<FileInfo> findListByParam(FileQuery param) {
        return fileMapper.selectList(param);
    }

    /**
     * 根据条件查询数量
     */
    @Override
    public Integer findCountByParam(FileQuery param) {
        return fileMapper.selectCount(param);
    }

    /**
     * 分页查询 可以设置条件查询等参数
     *
     * @param param 条件查询阐述
     * @return 文件List
     */
    @Override
    public PaginationResultVO<FileInfo> findListByPage(FileQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSizeEnum.SIZE15.getSize()
                : Math.max(1, Math.min(100, param.getPageSize()));

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<FileInfo> list = this.findListByParam(param);
        PaginationResultVO<FileInfo> resultVO = new PaginationResultVO<>(count,
                page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return resultVO;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(FileInfo bean) {
        return this.fileMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<FileInfo> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.fileMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<FileInfo> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.fileMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(FileInfo bean, FileQuery param) {
        StringUtils.checkParam(param);
        return this.fileMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(FileQuery param) {
        StringUtils.checkParam(param);
        return this.fileMapper.deleteByParam(param);
    }

    /**
     * 根据FileIdAndUserId获取对象
     */
    @Override
    public FileInfo getFileInfoByFileIdAndUserId(String fileId, String userId) {
        return this.fileMapper.selectByFileIdAndUserId(fileId, userId);
    }

    /**
     * 根据FileIdAndUserId修改
     */
    @Override
    public Integer updateFileInfoByFileIdAndUserId(FileInfo bean, String fileId, String userId) {
        return this.fileMapper.updateByFileIdAndUserId(bean, fileId, userId);
    }

    /**
     * 根据FileIdAndUserId删除
     */
    @Override
    public Integer deleteFileInfoByFileIdAndUserId(String fileId, String userId) {
        return this.fileMapper.deleteByFileIdAndUserId(fileId, userId);
    }


    /**
     * 文件上传
     *
     * @param userDto    用户信息
     * @param fileId     (非必传) 第一个分片的时候后端会反给前端fileId，下个分片上传时要携带
     * @param file       需要上传的文件
     * @param fileName   文件名
     * @param filePid    父级目录
     * @param fileMd5    切片后的文件
     * @param chunkIndex 当前传输的第几个分片
     * @param chunks     分片的总数量
     * @return 上传结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResultDto uploadFile(SessionWebUserDto userDto, String fileId,
                                      MultipartFile file, String fileName, String filePid,
                                      String fileMd5, Integer chunkIndex, Integer chunks) {
        UploadResultDto resultDto = new UploadResultDto();
        boolean uploadStatus = true;
        File tempFolder = null;
        try {
            //1.首次上传设置文件id
            if (StringUtils.isEmpty(fileId)) {
                fileId = StringUtils.getRandomString(Constants.LENGTH_10);
            }
            resultDto.setFileId(fileId);

            Date curDate = new Date();
            UserSpaceDto spaceDto = redisComponent.getUserSpace(userDto.getUserId());

            //2.首次上传
            if (chunkIndex == 0) {
                FileQuery fileQuery = new FileQuery();
                fileQuery.setFileMd5(fileMd5);
                fileQuery.setSimplePage(new SimplePage(0, 1));
                fileQuery.setStatus(FileStatusEnum.USING.getStatus());
                List<FileInfo> dbFileList = fileMapper.selectList(fileQuery);
                //2.1秒传
                if (!dbFileList.isEmpty()) {
                    FileInfo dbFile = dbFileList.get(0);
                    //2.1.1判断文件大小
                    if (dbFile.getFileSize() + spaceDto.getUseSpace() > spaceDto.getTotalSpace()) {
                        throw new BusinessException(ResponseCodeEnum.CODE_904);
                    }
                    //2.1.2copy一份
                    dbFile.setFileId(fileId);
                    dbFile.setFilePid(filePid);
                    dbFile.setUserId(userDto.getUserId());
                    dbFile.setCreateTime(curDate);
                    dbFile.setStatus(FileStatusEnum.USING.getStatus());
                    dbFile.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
                    dbFile.setFileMd5(fileMd5);
                    //文件重名
                    fileName = autoRename(filePid, userDto.getUserId(),
                            fileName);
                    dbFile.setFileName(fileName);
                    this.fileMapper.insert(dbFile);
                    //更新用户使用空间
                    updateUSeSpace(userDto, dbFile.getFileSize());

                    resultDto.setStatus(UploadStatusEnum.UPLOAD_SECONDS.getCode());
                    return resultDto;
                }
            }
            //3.1判断磁盘空间
            Long currentTempSize = redisComponent.getFileTemplateSize(userDto.getUserId(), fileId);
            if (file.getSize() + currentTempSize + spaceDto.getUseSpace() > spaceDto.getTotalSpace()) {
                throw new BusinessException(ResponseCodeEnum.CODE_904);
            }
            //3.2暂存临时目录
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_TEMP;
            String currentUserFolderName = userDto.getUserId() + fileId;
            //3.3创建临时目录
            tempFolder = new File(tempFolderName + currentUserFolderName);
            if (!tempFolder.exists()) {
                tempFolder.mkdirs();
            }


            File newFile = new File(tempFolder.getPath() + "/" + chunkIndex);
            file.transferTo(newFile);

            //保存临时大小
            redisComponent.saveFileTempSize(userDto.getUserId(), fileId, file.getSize());
            //不是最后一个分片，直接返回
            if (chunkIndex < chunks - 1) {
                resultDto.setStatus(UploadStatusEnum.UPLOADING.getCode());
                return resultDto;
            }
            //4.最后一个分片上传文件 异步合并并记录到数据库
            String curMonth = DateUtils.format(curDate, DateTimePatternEnum.YYYY_MM.getPattern());
            String fileSuffix = StringUtils.getFileSuffix(fileName);

            //真实文件名
            String realFileName = currentUserFolderName + fileSuffix;
            FileTypeEnum fileTypeEnum = FileTypeEnum.getFileTypeBySuffix(fileSuffix);
            //文件重名
            fileName = autoRename(filePid, userDto.getUserId(), fileName);

            //更新数据库
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileId(fileId);
            fileInfo.setUserId(userDto.getUserId());
            fileInfo.setFileMd5(fileMd5);
            fileInfo.setFilePid(filePid);
            fileInfo.setFileName(fileName);
            fileInfo.setFilePath(curMonth + "/" + realFileName);
            fileInfo.setCreateTime(curDate);
            fileInfo.setLastUpdateTime(curDate);
            fileInfo.setFolderType(FileFolderTypeEnum.FILE.getType());
            fileInfo.setFileCategory(fileTypeEnum.getCategory().getCategory());
            fileInfo.setFileType(fileTypeEnum.getType());
            fileInfo.setStatus(FileStatusEnum.TRANSFER.getStatus());
            fileInfo.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
            fileMapper.insert(fileInfo);

            //更新用户空间
            Long totalSize = redisComponent.getFileTemplateSize(userDto.getUserId(), fileId);
            updateUSeSpace(userDto, totalSize);
            resultDto.setStatus(UploadStatusEnum.UPLOAD_FINISH.getCode());

            //异步转码
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            fileService.transferFile(fileInfo.getFileId(), userDto);
                        }
                    });
            return resultDto;
        } catch (BusinessException e) {
            log.error("文件上传失败", e);
            uploadStatus = false;
            throw e;
        } catch (Exception e) {
            log.error("文件上传失败", e);
            uploadStatus = false;
        } finally {
            if (!uploadStatus && tempFolder != null) {
                try {
                    FileUtils.deleteDirectory(tempFolder);
                } catch (IOException e) {
                    log.error("删除临时文件目录失败", e);
                }
            }
        }
        return null;
    }

    /**
     * 创建目录
     *
     * @param filePid    父级id
     * @param userId     用户id
     * @param folderName 文件夹名
     * @return 文件信息
     */
    @Override
    public FileInfo newFolder(String filePid, String userId, String folderName) {
        return fileOrganizationService.newFolder(userId, filePid, folderName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo rename(String fileId, String userId, String fileName) {
        return fileOrganizationService.rename(userId, fileId, fileName);
    }

    /**
     * 移动所选文件到指定文件夹
     *
     * @param fileIds 所选文件id
     * @param filePid 目标文件夹id
     * @param userId  用户id
     */
    @Override
    public void changeFileFolder(String fileIds, String filePid, String userId) {
        fileOrganizationService.move(userId, fileIds, filePid);
    }

    /**
     * 文件删除
     */
    @Override
    public void removeFile2RecycleBatch(String userId, String fileIds) {
        recycleStorageService.recycle(userId, fileIds);
    }

    /**
     * 从回收站恢复文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recoverFile(String userId, String fileIds) {
        recycleStorageService.recover(userId, fileIds);
    }

    /**
     * 彻底删除回收站内文件`
     *
     * @param adminOp 是否为管理员
     */
    @Override
    public void deleteFile(String userId, String fileIds, boolean adminOp) {
        recycleStorageService.delete(userId, fileIds, adminOp);
    }

    /**
     * 只给看已经分享的 根目录其它的不给看
     */
    @Override
    public void checkRootFilePid(String rootFilePid, String userId, String fileId) {
        if (StringUtils.isEmpty(fileId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (rootFilePid.equals(fileId)) {
            return;
        }
        checkFilePid(rootFilePid, fileId, userId);
    }

    /**
     * 保存分享的文件
     */
    @Override
    public void saveShare(String shareRootFilePid, String shareFileIds, String myFolderId, String shareUserId, String currentUserId) {
        storageCopyService.saveShare(shareRootFilePid, shareFileIds, myFolderId, shareUserId, currentUserId);
    }

    /**
     * 自动重命名文件
     *
     * @param filePid  文件id
     * @param userId   用户id
     * @param fileName 文件名
     * @return 重命名后的文件名
     */
    private String autoRename(String filePid, String userId, String fileName) {
        FileQuery fileInfoQuery = new FileQuery();
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setFileId(filePid);
        fileInfoQuery.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        fileInfoQuery.setFileName(fileName);
        Integer count = fileMapper.selectCount(fileInfoQuery);
        if (count > 0) {
            fileName = StringUtils.rename(fileName);
        }
        return fileName;
    }

    /**
     * 更新用户空间 先更新数据库校验容量是否正确再更新redis
     *
     * @param sessionWebUserDto 用户信息
     * @param useSpace          新增使用空间
     */
    private void updateUSeSpace(SessionWebUserDto sessionWebUserDto, Long useSpace) {
        Integer count = userMapper.updateUserSpace(sessionWebUserDto.getUserId(), useSpace, null);
        if (count == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_904);
        }

        UserSpaceDto spaceDto = redisComponent.getUserSpace(sessionWebUserDto.getUserId());
        spaceDto.setUseSpace(spaceDto.getUseSpace() + useSpace);
        redisComponent.saveUserSpaceDto(sessionWebUserDto.getUserId(), spaceDto);
    }

    /**
     * 转码
     */
    @Async
    public void transferFile(String fileId, SessionWebUserDto userDto) {
        Boolean transferSuccess = true;
        String targetFilePath = null;
        String cover = null;
        FileTypeEnum fileTypeEnum = null;
        FileInfo fileInfo = fileMapper.selectByFileIdAndUserId(fileId, userDto.getUserId());
        try {
            if (fileInfo == null || !FileStatusEnum.TRANSFER.getStatus().equals(fileInfo.getStatus())) {
                return;
            }

            //临时目录
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_TEMP;
            String currentUserFolderName = userDto.getUserId() + fileId;
            File fileFolder = new File(tempFolderName + currentUserFolderName);
            String fileSuffix = StringUtils.getFileSuffix(fileInfo.getFileName());
            String curMonth = DateUtils.format(fileInfo.getCreateTime(), DateTimePatternEnum.YYYY_MM.getPattern());

            //目标目录
            String targetFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE;
            File targetFolder = new File(targetFolderName + curMonth);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }
            //真实文件名
            String realFileName = currentUserFolderName + fileSuffix;
            targetFilePath = targetFolder.getPath() + "/" + realFileName;

            //合并文件
            mergeFile(fileFolder.getPath(), targetFilePath, fileInfo.getFileName(), true);

            //视频文件切割
            fileTypeEnum = FileTypeEnum.getFileTypeBySuffix(StringUtils.getFileSuffix(fileInfo.getFileName()));
            if (FileTypeEnum.VIDEO.equals(fileTypeEnum)) {
                cutFile4Video(fileId, targetFilePath);
                //视频生成缩略图
                cover = curMonth + "/" + currentUserFolderName + Constants.IMAGE_PNG_SUFFIX;
                String coverPath = targetFolderName + "/" + cover;
                ScaleFiler.createCover4Video(new File(targetFilePath), Constants.LENGTH_150, new File(coverPath));
            } else if (FileTypeEnum.IMAGE.equals(fileTypeEnum)) {
                //生成缩略图
                cover = curMonth + "/" + realFileName.replace(".", "_.");
                String coverPath = targetFolderName + "/" + cover;
                Boolean created = ScaleFiler.createThumbnailWidthFFmpeg(new File(targetFilePath),
                        Constants.LENGTH_150, new File(coverPath), false);
                //没生成了copy一份当缩略图 有点抽象
                if (!created) {
                    FileUtils.copyFile(new File(targetFilePath), new File(coverPath));
                }
            }

        } catch (Exception e) {
            log.error("文件转码失败,文件ID:{},userId:{}", fileId, userDto.getUserId(), e);
            transferSuccess = false;
        } finally {
            FileInfo updateInfo = new FileInfo();
            updateInfo.setFileSize(new File(targetFilePath).length());
            updateInfo.setFileCover(cover);
            updateInfo.setStatus(transferSuccess ? FileStatusEnum.USING.getStatus() : FileStatusEnum.TRANSFER_FAIL.getStatus());
            //乐观锁 一个状态到另一个状态
            fileMapper.updateFileStatusWithOldStatus(fileId, userDto.getUserId(), updateInfo, FileStatusEnum.TRANSFER.getStatus());
        }

    }


    private void mergeFile(String srcPath, String destPath, String fileName, Boolean deleteSource) {
        File src = new File(srcPath);
        if (!src.exists()) {
            throw new BusinessException("目录不存在");
        }

        File[] fileList = src.listFiles();

        File targetFile = new File(destPath);
        RandomAccessFile writeFile = null;
        try {
            writeFile = new RandomAccessFile(targetFile, "rw");
            byte[] bytes = new byte[1024 * 10];
            for (int i = 0; i < fileList.length; i++) {
                int len = -1;
                File chunkFile = new File(srcPath + "/" + i);
                RandomAccessFile readFile = null;
                try {
                    readFile = new RandomAccessFile(chunkFile, "r");
                    while ((len = readFile.read(bytes)) != -1) {
                        writeFile.write(bytes, 0, len);
                    }
                } catch (Exception e) {
                    log.error("合并分片失败", e);
                    throw new BusinessException("合并分片失败");
                } finally {
                    if (readFile != null) {
                        readFile.close();
                    }
                }
            }
        } catch (Exception e) {
            log.error("合并文件: {}失败", fileName, e);
            throw new BusinessException("合并文件失败:" + fileName);
        } finally {
            if (writeFile != null) {
                try {
                    writeFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (deleteSource && src.exists()) {
                try {
                    FileUtils.deleteDirectory(src);
                } catch (IOException e) {
                    log.error("原目录删除失败", e);
                }
            }
        }
    }

    private void cutFile4Video(String fileId, String videoFilePath) {
        //创建同名切片目录
        File tsFolder = new File(videoFilePath.substring(0, videoFilePath.lastIndexOf(".")));
        if (!tsFolder.exists()) {
            tsFolder.mkdirs();
        }
        //ffmpeg命令
        final String CMD_TRANSFER_2TS = "ffmpeg -y -i %s  -vcodec copy -acodec copy -vbsf h264_mp4toannexb %s";
        final String CMD_CUT_TS = "ffmpeg -i %s -c copy -map 0 -f segment -segment_list %s -segment_time 30 %s/%s_%%4d.ts";
        String tsPath = tsFolder + "/" + Constants.TS_NAME;

        //生成.ts
        String cmd = String.format(CMD_TRANSFER_2TS, videoFilePath, tsPath);
        ProcessUtils.executeCommand(cmd, false);
        //生成索引文件.m3u8 和切片.ts
        cmd = String.format(CMD_CUT_TS, tsPath, tsFolder.getPath() + "/" + Constants.M3U8_NAME, tsFolder.getPath(), fileId);
        ProcessUtils.executeCommand(cmd, false);
        //删除index.ts
        new File(tsPath).delete();
    }

    /**
     * 检查重名文件
     */
    private void checkFileName(String filePid, String userId, String fileName, Integer folderType) {
        FileQuery fileInfoQuery = new FileQuery();
        fileInfoQuery.setFolderType(folderType);
        fileInfoQuery.setFileName(fileName);
        fileInfoQuery.setFilePid(filePid);
        fileInfoQuery.setUserId(userId);
        fileInfoQuery.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        Integer count = this.fileMapper.selectCount(fileInfoQuery);
        if (count > 0) {
            throw new BusinessException("此目录下已经存在同名文件，请修改名称");
        }
    }




    /**
     * 找到所有的子文件
     */
    private void findAllSubFile(List<FileInfo> copyFileList,
                                FileInfo fileInfo,
                                String sourceUserId,
                                String currentUserId,
                                Date curDate,
                                String newFilePid) {
        String sourceFileId = fileInfo.getFileId();
        fileInfo.setCreateTime(curDate);
        fileInfo.setLastUpdateTime(curDate);
        fileInfo.setFilePid(newFilePid);
        fileInfo.setUserId(currentUserId);
        String newFileId = StringUtils.getRandomString(Constants.LENGTH_10);
        fileInfo.setFileId(newFileId);
        copyFileList.add(fileInfo);
        //目录的话继续递归
        if (FileFolderTypeEnum.FOLDER.getType().equals(fileInfo.getFolderType())) {
            FileQuery query = new FileQuery();
            query.setFilePid(sourceFileId);
            query.setUserId(sourceUserId);
            List<FileInfo> sourceFileList = fileMapper.selectList(query);
            for (FileInfo item : sourceFileList) {
                findAllSubFile(copyFileList, item, sourceUserId, currentUserId, curDate, newFileId);
            }
        }
    }

    /**
     * 校验父级id
     */
    private void checkFilePid(String rootFilePid, String fileId, String userId) {
        FileInfo fileInfo = this.fileMapper.selectByFileIdAndUserId(fileId, userId);
        if (fileInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        //不可能分享根目录吧都
        if (Constants.ZERO_STR.equals(fileInfo.getFilePid())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        //recursion找到分享的父级id
        if (fileInfo.getFilePid().equals(rootFilePid)) {
            return;
        }
        checkFilePid(rootFilePid, fileInfo.getFilePid(), userId);
    }
}
