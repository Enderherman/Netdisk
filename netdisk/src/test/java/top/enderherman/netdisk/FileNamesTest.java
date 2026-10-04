package top.enderherman.netdisk;

import org.junit.jupiter.api.Test;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.FileNames;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileNamesTest {
    @Test void maximumLengthIsEnforcedWithoutRejectingChineseOrEmoji() {
        assertEquals("中文😀.txt", FileNames.requireValid("中文😀.txt"));
        assertEquals(200, FileNames.requireValid("a".repeat(200)).length());
        assertThrows(BusinessException.class, () -> FileNames.requireValid("a".repeat(201)));
        assertThrows(BusinessException.class, () -> FileNames.requireValid("\u0344".repeat(200)));
        assertThrows(BusinessException.class, () -> FileNames.requireValid("hidden\u0085name"));
    }

    @Test void uniqueNamesPreserveExtensionsAndStayWithinDatabaseLength() {
        String original = "a".repeat(196) + ".txt";
        String unique = FileNames.unique(original, false, Set.of(FileNames.key(original)));
        assertEquals(200, unique.length());
        assertTrue(unique.endsWith(" (1).txt"));
        assertEquals(".env (1)", FileNames.unique(".env", false, Set.of(".env")));
        String longSuffix = "a." + "b".repeat(198);
        assertThrows(BusinessException.class, () -> FileNames.unique(longSuffix, false, Set.of(FileNames.key(longSuffix))));
    }

    @Test void normalizationAndCasePreventAmbiguousDuplicates() {
        assertEquals(FileNames.key("École.txt"), FileNames.key("ecole.TXT"));
        assertEquals("é.txt", FileNames.requireValid("e\u0301.txt"));
        assertEquals("report (3).txt", FileNames.unique("report.txt", false,
                Set.of("report.txt", "report (1).txt", "report (2).txt")));
    }
}
