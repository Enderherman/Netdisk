package top.enderherman.netdisk.entity.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户信息
 */
@Data
public class User implements Serializable {


    /**
     * 用户ID
     */
    private String userId;

    /**
     * 昵称
     */
    private String nickName;

    /**
     * 邮箱
     */
    private String email;

    /**
     * QQ_OPenId
     */
    private String qqOpenId;

    /**
     * QQ头像
     */
    private String qqAvatar;

    /**
     * 密码
     */
    @JsonIgnore
    @ToString.Exclude
    private String password;

    @JsonIgnore
    private Long sessionVersion = 0L;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 最后登录时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastLoginTime;

    /**
     * 用户状态: 0:禁用 1:启用
     */
    private Integer status;

    /**
     * 使用空间: byte
     */
    private Long useSpace;

    /**
     * 总空间:byte
     */
    private Long totalSpace;
}
