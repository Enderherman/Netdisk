package top.enderherman.netdisk.entity.dto;

import lombok.Data;
import java.util.List;

@Data
public class UploadTaskDto {
    private String fileId;
    private String fileName;
    private String filePid;
    private String fileMd5;
    private int chunks;
    private String state;
    private String uploadStatus;
    private int receivedCount;
    private long receivedBytes;
    private long temporaryBytes;
    private Long fileSize;
    private boolean fileAvailable;
    private long createdAt;
    private long updatedAt;
    private long expiresAt;
    private List<ReceivedChunk> receivedChunks;

    public record ReceivedChunk(int index, long size) { }
}
