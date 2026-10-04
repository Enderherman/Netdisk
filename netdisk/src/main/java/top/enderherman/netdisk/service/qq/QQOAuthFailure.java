package top.enderherman.netdisk.service.qq;

import top.enderherman.netdisk.common.exceptions.BusinessException;

/** 只把固定失败类别带回登录页，绝不反射提供者错误文本。 */
public final class QQOAuthFailure extends BusinessException {
    public enum Reason { cancelled, expired, unavailable, failed }
    private final Reason reason;

    public QQOAuthFailure(Reason reason, int code, String message) {
        super(code, message);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
