package top.enderherman.netdisk.service;

import org.springframework.stereotype.Service;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.VerifyRegexEnum;

import java.util.Arrays;
import java.util.Locale;

@Service
public class AccountSecurityService {
    private final AppConfig config;

    public AccountSecurityService(AppConfig config) {
        this.config = config;
    }

    public String normalizeEmail(String email) {
        if (email == null || email.length() > 150 || !email.trim().matches(VerifyRegexEnum.EMAIL.getRegex())) {
            throw new BusinessException("邮箱格式不正确");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isAdmin(String email) {
        if (email == null || config.getAdminEmails() == null) return false;
        return Arrays.stream(config.getAdminEmails().split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .anyMatch(value -> value.equalsIgnoreCase(email));
    }

    public boolean isEmailVerificationEnabled() {
        return config.getSendUserName() != null && !config.getSendUserName().isBlank()
                && config.getSendUserPassword() != null && !config.getSendUserPassword().isBlank();
    }
}
