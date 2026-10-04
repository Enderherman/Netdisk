package top.enderherman.netdisk.entity.query;

import lombok.Data;

/**
 * 用户信息参数
 */
@Data
public class UserQuery extends BaseParam {

    /**
     * 用户ID
     */
    private String userId;

    private String userIdFuzzy;

    /**
     * 昵称
     */
    private String nickName;

    private String nickNameFuzzy;

    /**
     * 邮箱
     */
    private String email;

    private String emailFuzzy;

    /**
     * QQ_OPenId
     */
    private String qqOpenId;

    private String qqOpenIdFuzzy;

    /**
     * QQ头像
     */
    private String qqAvatar;

    private String qqAvatarFuzzy;

    /**
     * 密码
     */
    private String password;

    private String passwordFuzzy;

    /**
     * 创建时间
     */
    private String createTime;

    private String createTimeStart;

    private String createTimeEnd;

    /**
     * 最后登录时间
     */
    private String lastLoginTime;

    private String lastLoginTimeStart;

    private String lastLoginTimeEnd;

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
