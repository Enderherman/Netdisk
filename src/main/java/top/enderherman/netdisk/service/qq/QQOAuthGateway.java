package top.enderherman.netdisk.service.qq;

public interface QQOAuthGateway {
    QQIdentity authenticate(String authorizationCode);
}
