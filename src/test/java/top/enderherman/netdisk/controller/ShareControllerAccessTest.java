package top.enderherman.netdisk.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.dto.SessionShareDto;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.FileShareService;
import top.enderherman.netdisk.service.ShareAccessService;
import top.enderherman.netdisk.service.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareControllerAccessTest {
    @Mock FileShareService fileShareService;
    @Mock FileService fileService;
    @Mock UserService userService;
    @Mock ShareAccessService shareAccessService;
    @Mock RedisComponent redisComponent;
    @InjectMocks WebShareController controller;
    MockHttpSession session;
    FileShare share;

    @BeforeEach
    void setup() {
        session = new MockHttpSession();
        SessionShareDto extracted = new SessionShareDto();
        extracted.setShareId("share");
        extracted.setShareUserId("owner");
        extracted.setFileId("root");
        session.setAttribute(Constants.SESSION_SHARE_KEY + "share", extracted);
        SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId("receiver");
        session.setAttribute(Constants.SESSION_KEY, user);
        share = new FileShare();
        share.setShareId("share");
        share.setFileId("root");
        share.setUserId("owner");
        // 父类和子类均有共享服务字段，明确绑定当前控制器声明的字段。
        ReflectionTestUtils.setField(controller, WebShareController.class, "shareAccessService", shareAccessService, null);
        ReflectionTestUtils.setField(controller, WebShareController.class, "redisComponent", redisComponent, null);
    }

    @Test
    void unextractedShareCannotBeSaved() {
        session.removeAttribute(Constants.SESSION_SHARE_KEY + "share");
        assertCode(903, () -> controller.saveShareFile(session, "share", "child", "0"));
        verifyNoInteractions(fileService);
    }

    @Test
    void revokedShareBlocksListingAndSaving() {
        when(shareAccessService.requireActiveShare("share")).thenThrow(new BusinessException(ResponseCodeEnum.CODE_902));
        assertCode(902, () -> controller.loadFileList(session, "share", "0", 1, 15));
        assertCode(902, () -> controller.saveShareFile(session, "share", "child", "0"));
        verifyNoInteractions(fileService);
    }

    @Test
    void staleSessionWithChangedRootIsDiscarded() {
        share.setFileId("newRoot");
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        assertCode(903, () -> controller.loadFileList(session, "share", "0", 1, 15));
        assertNull(session.getAttribute(Constants.SESSION_SHARE_KEY + "share"));
    }

    @Test
    void saveChecksEverySourceAndTargetBeforeCopy() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        controller.saveShareFile(session, "share", "one,two", "folder");
        var order = inOrder(shareAccessService, fileService);
        order.verify(shareAccessService).requireActiveShare("share");
        order.verify(shareAccessService).requireOwnFolder("receiver", "folder");
        order.verify(shareAccessService).requireSharedFile(any(), eq("one"));
        order.verify(shareAccessService).requireSharedFile(any(), eq("two"));
        order.verify(fileService).saveShare("root", "one,two", "folder", "owner", "receiver");
    }

    @Test
    void outOfScopeSourceNeverReachesCopy() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        when(shareAccessService.requireSharedFile(any(), eq("foreign")))
                .thenThrow(new BusinessException(ResponseCodeEnum.CODE_600));
        assertCode(600, () -> controller.saveShareFile(session, "share", "foreign", "0"));
        verifyNoInteractions(fileService);
    }

    @Test
    void ownerCannotSaveOwnShare() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        ((SessionWebUserDto) session.getAttribute(Constants.SESSION_KEY)).setUserId("owner");
        assertThrows(BusinessException.class, () -> controller.saveShareFile(session, "share", "root", "0"));
        verifyNoInteractions(fileService);
    }

    @Test
    void shareDirectorySupportsRequestedPage() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        FileInfo folder = new FileInfo();
        folder.setFolderType(1);
        when(shareAccessService.requireSharedFile(any(), eq("root"))).thenReturn(folder);
        when(fileService.findListByPage(any())).thenReturn(new PaginationResultVO<>(20, 15, 2, 2, List.of()));
        controller.loadFileList(session, "share", "root", 2, 15);
        ArgumentCaptor<FileQuery> query = ArgumentCaptor.forClass(FileQuery.class);
        verify(fileService).findListByPage(query.capture());
        assertEquals(2, query.getValue().getPageNo());
        assertEquals(15, query.getValue().getPageSize());
        assertEquals("owner", query.getValue().getUserId());
        assertEquals(2, query.getValue().getDelFlag());
    }

    @Test
    void invalidPageIsRejected() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        assertCode(600, () -> controller.loadFileList(session, "share", "0", 0, 15));
        assertCode(600, () -> controller.loadFileList(session, "share", "0", 1, 101));
    }

    @Test
    void shareDownloadCodeRetainsShareAndFileIdentityForRevalidation() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        FileInfo file = new FileInfo();
        file.setFileId("child");
        file.setFolderType(0);
        file.setFileName("文档.txt");
        file.setFilePath("202610/content.txt");
        when(shareAccessService.requireSharedFile(any(), eq("child"))).thenReturn(file);
        controller.getDownloadUrl(session, "share", "child");
        ArgumentCaptor<DownloadFileDto> token = ArgumentCaptor.forClass(DownloadFileDto.class);
        verify(redisComponent).saveDownloadCode(anyString(), token.capture());
        assertEquals("share", token.getValue().getShareId());
        assertEquals("child", token.getValue().getFileId());
        assertEquals("owner", token.getValue().getUserId());
    }

    @Test
    void folderDownloadCannotIssueCode() {
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        FileInfo folder = new FileInfo();
        folder.setFolderType(1);
        when(shareAccessService.requireSharedFile(any(), eq("root"))).thenReturn(folder);
        assertCode(600, () -> controller.getDownloadUrl(session, "share", "root"));
        verifyNoInteractions(redisComponent);
    }

    static void assertCode(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).getCode());
    }
}
