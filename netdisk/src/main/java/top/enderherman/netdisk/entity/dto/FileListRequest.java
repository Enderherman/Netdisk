package top.enderherman.netdisk.entity.dto;

import lombok.Data;

/** 浏览接口只接收公开筛选字段，不允许绑定用户、删除标记、物理路径或 SQL。 */
@Data
public class FileListRequest {
    private String filePid;
    private String fileNameFuzzy;
    private Integer folderType;
    private Integer fileType;
    private Integer status;
    private Integer pageNo;
    private Integer pageSize;
    private String sortField;
    private String sortDirection;
}
