package top.enderherman.netdisk;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.multipart.MultipartFile;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.controller.FileController;

import static org.junit.jupiter.api.Assertions.*;

class DependencyCompatibilityTest {
    @Test void mvcArgumentsRetainTheirRuntimeNames() throws Exception {
        var method = FileController.class.getMethod("uploadFile", HttpSession.class, String.class,
                MultipartFile.class, String.class, String.class, String.class, Integer.class, Integer.class);
        assertTrue(method.getParameters()[3].isNamePresent());
        assertEquals("fileName", method.getParameters()[3].getName());
        assertEquals("chunkIndex", method.getParameters()[6].getName());
    }

    @Test void mysqlDriverCanBeLoadedByTheConfiguredJavaRuntime() {
        assertDoesNotThrow(() -> Class.forName("com.mysql.cj.jdbc.Driver"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 10, 20, 50})
    void secureCodesKeepExistingDatabaseAndUrlFormats(int length) {
        String token = StringUtils.getRandomString(length);
        String digits = StringUtils.getRandomNumber(length);
        assertEquals(length, token.length());
        assertTrue(token.matches("[A-Za-z0-9]*"));
        assertEquals(length, digits.length());
        assertTrue(digits.matches("[0-9]*"));
    }

    @Test void invalidCodeLengthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtils.getRandomString(-1));
        assertThrows(IllegalArgumentException.class, () -> StringUtils.getRandomNumber(null));
    }
}
