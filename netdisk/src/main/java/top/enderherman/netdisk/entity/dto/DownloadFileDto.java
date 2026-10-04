package top.enderherman.netdisk.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadFileDto {
    private String downloadCode;
    private String fileId;
    private String fileName;
    private String filePath;
    /** 非空表示分享下载；所有下载入口都必须再次验证该分享。 */
    private String shareId;
    private String userId;
}
