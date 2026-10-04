package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UploadResultDto;
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

import java.util.*;

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

    @Resource
    private FileUploadService fileUploadService;

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
    public UploadResultDto uploadFile(SessionWebUserDto userDto, String fileId,
                                      MultipartFile file, String fileName, String filePid,
                                      String fileMd5, Integer chunkIndex, Integer chunks) {
        return fileUploadService.upload(userDto, fileId, file, fileName, filePid, fileMd5, chunkIndex, chunks);
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
