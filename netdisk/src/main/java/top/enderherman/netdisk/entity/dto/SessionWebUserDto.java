package top.enderherman.netdisk.entity.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
public class SessionWebUserDto {
    private String nickName;
    private String userId;
    private Boolean isAdmin;
    private String avatar;
    @JsonIgnore
    private Long sessionVersion = 0L;

}
