package top.enderherman.netdisk.service;

import org.junit.jupiter.api.Test;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.StringUtils;

import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {
    private final PasswordService passwords = new PasswordService();

    @Test
    void saltedHashesDifferAndValidateOnlyCorrectPassword() {
        String first = passwords.hash("Example password123!");
        String second = passwords.hash("Example password123!");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("pbkdf2-sha256$600000$"));
        assertTrue(passwords.matches("Example password123!", first));
        assertFalse(passwords.matches("Wrong password123!", first));
        assertFalse(passwords.needsUpgrade(first));
    }

    @Test
    void legacyMd5AcceptsPlaintextAndRequestsUpgrade() {
        String legacy = StringUtils.encodingByMd5("Password123");
        assertTrue(passwords.matches("Password123", legacy));
        assertTrue(passwords.matches("Password123", legacy.toUpperCase()));
        assertTrue(passwords.needsUpgrade(legacy));
        assertFalse(passwords.matches(legacy, legacy));
    }

    @Test
    void emptyAndWhitespacePasswordsCannotCrashLegacyVerification() {
        String legacy = StringUtils.encodingByMd5("Password123");
        assertFalse(passwords.matches("", legacy));
        assertFalse(passwords.matches("   ", legacy));
        assertFalse(passwords.matches("\t\n", legacy));
    }

    @Test
    void malformedOrExpensiveStoredHashIsRejected() {
        for (String hash : new String[]{"", "plaintext", "pbkdf2-sha256$bad$x$x", "pbkdf2-sha256$999999999$x$x",
                "pbkdf2-sha256$600000$!$!", "pbkdf2-sha256$600000$YQ==$YQ=="}) {
            assertFalse(passwords.matches("Password123", hash));
        }
        assertFalse(passwords.matches(null, "hash"));
        assertFalse(passwords.matches("Password123", null));
    }

    @Test
    void passwordRulesAllowSymbolsAndRejectWeakOrOverlongValues() {
        passwords.validateNewPassword("a1!@#$%^&*()[]{}?/\\+_-= 中文");
        passwords.validateNewPassword("a1" + "x".repeat(62));
        for (String value : new String[]{"short1", "12345678", "abcdefgh", "a1" + "x".repeat(63), "Password1\n"}) {
            assertThrows(BusinessException.class, () -> passwords.validateNewPassword(value));
        }
        assertThrows(BusinessException.class, () -> passwords.validateNewPassword(null));
    }
}
