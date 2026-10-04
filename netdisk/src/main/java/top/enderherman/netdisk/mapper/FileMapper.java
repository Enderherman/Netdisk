package top.enderherman.netdisk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.enderherman.netdisk.entity.pojo.FileInfo;

import java.util.List;
import java.util.Date;

@Mapper
public interface FileMapper<T,P> extends BaseMapper<T,P>{

    /**
     * 根据FileIdAndUserId更新
     */
    Integer updateByFileIdAndUserId(@Param("bean") T t,@Param("fileId") String fileId,@Param("userId") String userId);


    /**
     * 根据FileIdAndUserId删除
     */
    Integer deleteByFileIdAndUserId(@Param("fileId") String fileId,@Param("userId") String userId);


    /**
     * 根据FileIdAndUserId获取对象
     */
    T selectByFileIdAndUserId(@Param("fileId") String fileId,@Param("userId") String userId);

    Long selectUseSpace(@Param("userId") String userId);

    void updateFileStatusWithOldStatus(@Param("fileId") String fileId, @Param("userId") String userId,
                                       @Param("bean") T t, @Param("oldStatus") Integer oldStatus);

    void updateFileDelFlagBatch(@Param("bean") FileInfo fileInfo, @Param("userId") String userId,
                                @Param("filePidList")List<String> filePidList,
                                @Param("fileIdList") List<String> fileIdList,
                                @Param("oldDelFlag") Integer oldDelFlag);
    void deleteFileByUserId(@Param("userId") String userId);

    String lockUserForStorage(@Param("userId") String userId);

    void updateRecycleState(@Param("userId") String userId, @Param("fileIds") List<String> fileIds,
                            @Param("state") int state, @Param("recoveryTime") Date recoveryTime,
                            @Param("updatedAt") Date updatedAt, @Param("parent") String parent);

    List<T> selectStorageReferencesForUpdate(@Param("filePath") String filePath);

    List<T> selectFilesForUpdate(@Param("userId") String userId, @Param("fileIds") List<String> fileIds);


}
