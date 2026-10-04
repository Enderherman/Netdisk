package top.enderherman.netdisk.service;

import org.springframework.stereotype.Service;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 带随机盐的密码存储；MD5 仅用于识别并迁移旧数据库记录。 */
@Service
public class PasswordService {
    private static final int ITERATIONS = 600_000;
    private static final String PREFIX = "pbkdf2-sha256$";
    private final SecureRandom random = new SecureRandom();

    public void validateNewPassword(String password) {
        if (password == null || !password.matches("^(?=.*[0-9])(?=.*[a-zA-Z]).{8,64}$")) {
            throw new BusinessException("密码须为 8 至 64 位，至少包含英文字母和数字");
        }
    }

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return PREFIX + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public boolean matches(String password, String encoded) {
        if (password == null || password.isBlank() || password.length() > 64 || encoded == null) {
            return false;
        }
        if (encoded.matches("(?i)[0-9a-f]{32}")) {
            return MessageDigest.isEqual(StringUtils.encodingByMd5(password).getBytes(StandardCharsets.US_ASCII),
                    encoded.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        }
        if (!encoded.startsWith(PREFIX)) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length < 16 || salt.length > 64 || expected.length != 32) return false;
            return MessageDigest.isEqual(derive(password, salt, iterations), expected);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean needsUpgrade(String encoded) {
        return encoded == null || !encoded.startsWith(PREFIX + ITERATIONS + "$");
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境不支持安全密码存储", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
