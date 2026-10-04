package top.enderherman.netdisk.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.service.ShareAccessService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareDownloadRevocationTest {
    ACommonFileController controller;
    RedisComponent redis;
    ShareAccessService access;
    DownloadFileDto token;
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setup() {
        controller = new ACommonFileController();
        redis = mock(RedisComponent.class);
        access = mock(ShareAccessService.class);
        ReflectionTestUtils.setField(controller, "redisComponent", redis);
        ReflectionTestUtils.setField(controller, "shareAccessService", access);
        token = new DownloadFileDto();
        token.setShareId("share");
        token.setUserId("owner");
        token.setFileId("file");
        token.setFilePath("original.txt");
    }

    @Test
    void revokedShareCodeCannotBeRedeemedAtAnyInheritedDownloadEntry() {
        when(redis.getDownloadCode("token")).thenReturn(token);
        when(access.requireActiveShare("share")).thenThrow(new BusinessException(ResponseCodeEnum.CODE_902));
        ShareControllerAccessTest.assertCode(902, () -> controller.download(request, response, "token"));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void mismatchedOwnerIsRejectedBeforeRead() {
        FileShare share = new FileShare();
        share.setUserId("different");
        when(redis.getDownloadCode("token")).thenReturn(token);
        when(access.requireActiveShare("share")).thenReturn(share);
        ShareControllerAccessTest.assertCode(902, () -> controller.download(request, response, "token"));
        verify(access, never()).requireSharedFile(any(), any());
    }

    @Test
    void replacedFileCannotReuseOldCredential() {
        FileShare share = new FileShare();
        share.setUserId("owner");
        FileInfo file = new FileInfo();
        file.setFilePath("changed.txt");
        when(redis.getDownloadCode("token")).thenReturn(token);
        when(access.requireActiveShare("share")).thenReturn(share);
        when(access.requireSharedFile(share, "file")).thenReturn(file);
        ShareControllerAccessTest.assertCode(902, () -> controller.download(request, response, "token"));
    }

    @Test
    void expiredCodeReturnsExplicitError() {
        ShareControllerAccessTest.assertCode(600, () -> controller.download(request, response, "expired"));
        verifyNoInteractions(access);
    }
}
