package top.enderherman.netdisk.service.qq;

/** 只携带身份与展示资料，不携带授权码、access token 或 refresh token。 */
public record QQIdentity(String clientId, String openId, String nickname, String avatarUrl) { }
