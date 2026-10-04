package top.enderherman.netdisk.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.service.FileContentService;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.ShareAccessService;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoCopyRetentionTest {
    @TempDir Path storage;
    FileService files = mock(FileService.class);
    ACommonFileController controller = new ACommonFileController();
    ShareAccessService shares = new ShareAccessService();
    FileInfo copy;
    FileShare share;

    @BeforeEach void setup() throws Exception {
        FileContentService content = new FileContentService();
        AppConfig config = new AppConfig(); config.setProjectFolder(storage.toString());
        ReflectionTestUtils.setField(content, "appConfig", config);
        ReflectionTestUtils.setField(controller, "contentService", content);
        ReflectionTestUtils.setField(controller, "fileInfoService", files);
        ReflectionTestUtils.setField(shares, "fileService", files);
        Path segment = storage.resolve("file/202610/sourceUseroldvideo01/oldvideo01_0000.ts");
        Files.createDirectories(segment.getParent()); Files.write(segment, new byte[]{1, 2, 3});
        copy = new FileInfo(); copy.setFileId("copyVideo1"); copy.setUserId("owner"); copy.setFilePid("root");
        copy.setFilePath("202610/sourceUseroldvideo01.mp4"); copy.setDelFlag(2); copy.setStatus(2);
        copy.setFolderType(0); copy.setFileCategory(1);
        FileInfo root = new FileInfo(); root.setFileId("root"); root.setUserId("owner"); root.setFilePid("0"); root.setDelFlag(2); root.setFolderType(1);
        when(files.getFileInfoByFileIdAndUserId("copyVideo1", "owner")).thenReturn(copy);
        when(files.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(root);
        when(files.findListByParam(any())).thenAnswer(invocation -> {
            FileQuery query = invocation.getArgument(0);
            if ("owner".equals(query.getUserId()) && Integer.valueOf(2).equals(query.getDelFlag())
                    && (copy.getFilePath().equals(query.getFilePath()) || "oldvideo01".equals(query.getFilePathFuzzy()))) return List.of(copy);
            return List.of();
        });
        share = new FileShare(); share.setFileId("root"); share.setUserId("owner"); share.setShareId("share");
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void activeCopyKeepsSegmentsAfterOriginalRecycledOrPhysicallyRemoved(boolean recycled) throws Exception {
        if (recycled) {
            FileInfo original = new FileInfo(); original.setFileId("oldvideo01"); original.setDelFlag(1);
            original.setFilePath(copy.getFilePath());
            when(files.getFileInfoByFileIdAndUserId("oldvideo01", "owner")).thenReturn(original);
        }
        assertSame(copy, shares.requirePreviewFile(share, "oldvideo01_0000.ts"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.getFile(response, "oldvideo01_0000.ts", "owner");
        assertArrayEquals(new byte[]{1,2,3}, response.getContentAsByteArray());
    }

    @Test void retainedVideoOutsideShareDoesNotGrantAccess() {
        copy.setFilePid("0");
        assertThrows(BusinessException.class, () -> shares.requirePreviewFile(share, "oldvideo01_0000.ts"));
    }
}
