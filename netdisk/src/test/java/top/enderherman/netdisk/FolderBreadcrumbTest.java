package top.enderherman.netdisk;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.BaseResponse;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.controller.ACommonFileController;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.FileInfoVO;
import top.enderherman.netdisk.service.FileService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FolderBreadcrumbTest {
    static class Controller extends ACommonFileController {
        BaseResponse<?> folders(String path) { return getFolderInfo(path, "alice"); }
    }
    private final FileService service = mock(FileService.class);
    private final Controller controller = new Controller();
    FolderBreadcrumbTest() { ReflectionTestUtils.setField(controller, "fileInfoService", service); }

    @Test
    void maliciousPathCannotBecomeSql() {
        assertThrows(BusinessException.class, () -> controller.folders("abc\",(select sleep(5)),\""));
        assertThrows(BusinessException.class, () -> controller.folders("../abc"));
        assertThrows(BusinessException.class, () -> controller.folders(null));
        verifyNoInteractions(service);
    }

    @Test
    void ordersBoundIdsInMemoryAndScopesUserAndDeletion() {
        FileInfo a = new FileInfo(); a.setFileId("a");
        FileInfo b = new FileInfo(); b.setFileId("b");
        when(service.findListByParam(any())).thenAnswer(invocation -> {
            FileQuery query = invocation.getArgument(0);
            assertNull(query.getOrderBy());
            assertEquals("alice", query.getUserId());
            assertEquals(2, query.getDelFlag());
            assertEquals(1, query.getFolderType());
            assertArrayEquals(new String[]{"a", "b"}, query.getFileIdArray());
            return List.of(b, a);
        });
        List<FileInfoVO> result = (List<FileInfoVO>) controller.folders("0/a/b").getData();
        assertEquals(List.of("a", "b"), result.stream().map(FileInfoVO::getFileId).toList());
    }

    @Test
    void rootBreadcrumbNeedsNoDatabaseQuery() {
        assertEquals(List.of(), controller.folders("0").getData());
        verifyNoInteractions(service);
    }

    @Test
    void missingOrUnauthorizedFolderFailsExplicitly() {
        when(service.findListByParam(any())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> controller.folders("missing"));
    }
}
