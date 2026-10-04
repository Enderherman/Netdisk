package top.enderherman.netdisk.service;

public interface EmailCodeService {
    /**
     * 发送邮箱验证码
     */
    void sendEmailCode(String email, Integer type);

    void checkEmailCode(String email, String code, Integer purpose);
}
