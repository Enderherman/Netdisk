package top.enderherman.netdisk.entity.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import top.enderherman.netdisk.entity.enums.DateTimePatternEnum;
import top.enderherman.netdisk.common.utils.DateUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 邮箱验证码
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailCode implements Serializable {


    /**
     * 邮箱
     */
    private String email;

    /**
     * 验证码
     */
    private String code;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 0:未使用 1:已使用
     */
    private Integer status;
    /** 0 注册，1 找回密码。 */
    private Integer purpose;

    @Override
    public String toString() {
        return "邮箱:" + (email == null ? "空" : email) +
                "，验证码:[已隐藏]" +
                "，创建时间:" + (createTime == null ? "空" : DateUtils.format(createTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern())) + "，0:未使用 1:已使用:" + (status == null ? "空" : status);
    }


}
