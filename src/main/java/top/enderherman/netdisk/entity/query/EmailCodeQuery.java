package top.enderherman.netdisk.entity.query;


import lombok.Data;

/**
 * 邮箱验证码参数
 */
@Data
public class EmailCodeQuery extends BaseParam{

    /**
     * 邮箱
     */
    private String email;

    private String emailFuzzy;

    /**
     * 验证码
     */
    private String code;

    private String codeFuzzy;

    /**
     * 创建时间
     */
    private String createTime;

    private String createTimeStart;

    private String createTimeEnd;

    /**
     * 0:未使用 1:已使用
     */
    private Integer status;
}
