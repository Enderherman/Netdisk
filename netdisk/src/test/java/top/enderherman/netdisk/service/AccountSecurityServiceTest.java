package top.enderherman.netdisk.service;

import org.junit.jupiter.api.Test;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class AccountSecurityServiceTest {
    @Test
    void adminRequiresExactEmailWithWhitespaceAndCaseNormalization() {
        AppConfig config = new AppConfig();
        config.setAdminEmails(" Admin@Example.test , second@example.test ");
        AccountSecurityService service = new AccountSecurityService(config);
        assertTrue(service.isAdmin("admin@example.test"));
        assertTrue(service.isAdmin("second@example.test"));
        assertFalse(service.isAdmin("min@example.test"));
        assertFalse(service.isAdmin("admin@example.test.evil"));
        assertFalse(service.isAdmin(null));
    }

    @Test
    void capabilitiesDoNotAdvertiseUnconfiguredMail() {
        AppConfig config = new AppConfig();
        AccountSecurityService service = new AccountSecurityService(config);
        assertFalse(service.isEmailVerificationEnabled());
        config.setSendUserName("sender@example.test");
        assertFalse(service.isEmailVerificationEnabled());
        config.setSendUserPassword("configured");
        assertTrue(service.isEmailVerificationEnabled());
    }

    @Test
    void emailIsCanonicalizedAndInvalidEmailFailsEarly() {
        AccountSecurityService service = new AccountSecurityService(new AppConfig());
        assertEquals("user@example.test", service.normalizeEmail(" USER@Example.test "));
        assertThrows(BusinessException.class, () -> service.normalizeEmail("invalid"));
        assertThrows(BusinessException.class, () -> service.normalizeEmail(null));
    }
}
