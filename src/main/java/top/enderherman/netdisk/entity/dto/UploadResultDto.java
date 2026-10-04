package top.enderherman.netdisk.entity.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class UploadResultDto implements Serializable {
    private String fileId;
    private String status;
}
