package top.enderherman.netdisk.service;

import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UploadResultDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;

import java.util.List;

public interface FileService {
    /**
     * 根据条件查询列表
     */
    List<FileInfo> findListByParam(FileQuery query);
    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(FileQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<FileInfo> findListByPage(FileQuery param);

    /**
     * 新增
     */
    Integer add(FileInfo bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<FileInfo> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<FileInfo> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(FileInfo bean, FileQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(FileQuery param);

    /**
     * 根据FileIdAndUserId查询对象
     */
    FileInfo getFileInfoByFileIdAndUserId(String fileId, String userId);


    /**
     * 根据FileIdAndUserId修改
     */
    Integer updateFileInfoByFileIdAndUserId(FileInfo bean, String fileId, String userId);


    /**
     * 根据FileIdAndUserId删除
     */
    Integer deleteFileInfoByFileIdAndUserId(String fileId, String userId);

    /**
     * 上传文件
     */
    UploadResultDto uploadFile(SessionWebUserDto userDto, String fileId, MultipartFile file, String fileName, String filePid, String fileMd5, Integer chunkIndex, Integer chunks);

    /**
     * 创建目录
     */
    FileInfo newFolder(String filePid, String userId, String fileName);

    /**
     * 文件/文件夹重命名
     */
    FileInfo rename(String fileId, String userId, String fileName);

    /**
     * 文件移动到指定文件夹
     */
    void changeFileFolder(String fileIds, String filePid, String userId);

    /**
     * 文件删除到回收站
     */
    void removeFile2RecycleBatch(String userId, String fileIds);

    /**
     * 从回收站恢复
     */
    void recoverFile(String userId, String fileIds);

    void deleteFile(String userId, String fileIds, boolean adminOp);

    /**
     * 校验路径
     */
    void checkRootFilePid(String fileId, String shareUserId, String filePid);

    /**
     * 保存分享的文件
     */
    void saveShare(String fileId, String shareFileIds, String myFolderId, String shareUserId, String userId);
}
