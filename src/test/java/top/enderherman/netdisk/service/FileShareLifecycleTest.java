package top.enderherman.netdisk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.SessionShareDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.query.FileShareQuery;
import top.enderherman.netdisk.mapper.FileShareMapper;
import top.enderherman.netdisk.service.impl.FileShareServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileShareLifecycleTest {
    @Mock FileShareMapper<FileShare, FileShareQuery> fileShareMapper;
    @Mock FileService fileService;
    @Mock ShareAccessService shareAccessService;
    @InjectMocks FileShareServiceImpl service;

    @Test
    void cannotCreateShareForUnknownOrForeignFile() {
        FileShare share = share();
        assertThrows(BusinessException.class, () -> service.saveShare(share));
        verifyNoInteractions(fileShareMapper);
    }

    @Test
    void cannotCreateShareForDeletedFile() {
        FileInfo file = ShareAccessServiceTest.file("file", "0", 0);
        file.setDelFlag(1);
        when(fileService.getFileInfoByFileIdAndUserId("file", "owner")).thenReturn(file);
        assertThrows(BusinessException.class, () -> service.saveShare(share()));
        verifyNoInteractions(fileShareMapper);
    }

    @Test
    void createsOneDayAndPermanentSharesWithUsableCodes() {
        when(fileService.getFileInfoByFileIdAndUserId("file", "owner"))
                .thenReturn(ShareAccessServiceTest.file("file", "0", 0));
        FileShare oneDay = share();
        service.saveShare(oneDay);
        assertNotNull(oneDay.getExpireTime());
        assertTrue(oneDay.getCode().matches("[A-Za-z0-9]{5}"));
        assertEquals(20, oneDay.getShareId().length());
        FileShare forever = share();
        forever.setValidType(3);
        forever.setCode("aB12");
        service.saveShare(forever);
        assertNull(forever.getExpireTime());
        assertEquals("aB12", forever.getCode());
        verify(fileShareMapper).insert(oneDay);
        verify(fileShareMapper).insert(forever);
    }

    @Test
    void rejectsInvalidCodeAndValidityBeforeInsert() {
        when(fileService.getFileInfoByFileIdAndUserId("file", "owner"))
                .thenReturn(ShareAccessServiceTest.file("file", "0", 0));
        FileShare share = share();
        share.setCode("x");
        assertThrows(BusinessException.class, () -> service.saveShare(share));
        share.setCode("abcd");
        share.setValidType(99);
        assertThrows(BusinessException.class, () -> service.saveShare(share));
        verifyNoInteractions(fileShareMapper);
    }

    @Test
    void onlyCorrectCodeIssuesSessionAndIncrementsViews() {
        FileShare share = share();
        share.setShareId("share");
        share.setCode("abcd");
        when(shareAccessService.requireActiveShare("share")).thenReturn(share);
        assertThrows(BusinessException.class, () -> service.checkShareCode("share", "bad"));
        verifyNoInteractions(fileShareMapper);
        SessionShareDto session = service.checkShareCode("share", "abcd");
        assertEquals("owner", session.getShareUserId());
        assertEquals("file", session.getFileId());
        verify(fileShareMapper).updateShareShowCount("share");
    }

    private FileShare share() {
        FileShare share = new FileShare();
        share.setFileId("file");
        share.setUserId("owner");
        share.setValidType(0);
        return share;
    }
}
