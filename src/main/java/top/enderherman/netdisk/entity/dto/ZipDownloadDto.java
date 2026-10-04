package top.enderherman.netdisk.entity.dto;

import lombok.Data;
import java.util.List;

/** Redis 中保存有界的归档清单，不保存文件内容或整个 ZIP。 */
@Data
public class ZipDownloadDto {
    private String userId;
    private Long sessionVersion;
    private long expiresAt;
    private String archiveName;
    private List<String> rootFileIds;
    private List<Entry> entries;

    @Data
    public static class Entry {
        private String fileId;
        private String filePid;
        private String sourceName;
        private String entryName;
        private String relativePath;
        private String fileMd5;
        private boolean directory;
        private long size;
        private long modifiedAt;
    }
}
