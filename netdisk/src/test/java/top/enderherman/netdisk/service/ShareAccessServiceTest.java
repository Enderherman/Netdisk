package top.enderherman.netdisk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.pojo.FileShare;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.FileShareQuery;
import top.enderherman.netdisk.mapper.FileShareMapper;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareAccessServiceTest {
    @Mock FileShareMapper<FileShare, FileShareQuery> fileShareMapper;
    @Mock FileService fileService;
    @Mock UserService userService;
    @InjectMocks ShareAccessService service;
    FileShare share;

    @BeforeEach
    void setup() {
        share = new FileShare();
        share.setShareId("share");
        share.setUserId("owner");
        share.setFileId("root");
    }

    @Test
    void activeShareRequiresLiveOwnerAndRoot() {
        User owner = new User();
        owner.setStatus(1);
        when(fileShareMapper.selectByShareId("share")).thenReturn(share);
        when(userService.getUserInfoByUserId("owner")).thenReturn(owner);
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(file("root", "0", 1));
        assertSame(share, service.requireActiveShare("share"));
        owner.setStatus(0);
        assertCode(902, () -> service.requireActiveShare("share"));
    }

    @Test
    void revokedShareFailsEvenWhenCallerHasEarlierSnapshot() {
        assertCode(902, () -> service.requireActiveShare("share"));
        verifyNoInteractions(userService, fileService);
    }

    @Test
    void expiredShareFailsBeforeReadingFiles() {
        share.setExpireTime(new Date(System.currentTimeMillis() - 1));
        when(fileShareMapper.selectByShareId("share")).thenReturn(share);
        assertCode(902, () -> service.requireActiveShare("share"));
        verifyNoInteractions(userService, fileService);
    }

    @Test
    void deletedRootInvalidatesShare() {
        User owner = new User();
        owner.setStatus(1);
        FileInfo root = file("root", "0", 1);
        root.setDelFlag(1);
        when(fileShareMapper.selectByShareId("share")).thenReturn(share);
        when(userService.getUserInfoByUserId("owner")).thenReturn(owner);
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(root);
        assertCode(902, () -> service.requireActiveShare("share"));
    }

    @Test
    void allowsRootAndNestedLiveDescendant() {
        FileInfo root = file("root", "0", 1);
        FileInfo child = file("child", "folder", 0);
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(root);
        when(fileService.getFileInfoByFileIdAndUserId("folder", "owner")).thenReturn(file("folder", "root", 1));
        when(fileService.getFileInfoByFileIdAndUserId("child", "owner")).thenReturn(child);
        assertSame(root, service.requireSharedFile(share, "root"));
        assertSame(child, service.requireSharedFile(share, "child"));
    }

    @Test
    void refusesSiblingOutsideShareAndOtherOwnerFile() {
        when(fileService.getFileInfoByFileIdAndUserId("sibling", "owner")).thenReturn(file("sibling", "0", 0));
        assertCode(600, () -> service.requireSharedFile(share, "sibling"));
        assertCode(600, () -> service.requireSharedFile(share, "foreign"));
    }

    @Test
    void refusesChildOfDeletedAncestor() {
        FileInfo folder = file("folder", "root", 1);
        folder.setDelFlag(1);
        when(fileService.getFileInfoByFileIdAndUserId("child", "owner")).thenReturn(file("child", "folder", 0));
        when(fileService.getFileInfoByFileIdAndUserId("folder", "owner")).thenReturn(folder);
        assertCode(600, () -> service.requireSharedFile(share, "child"));
    }

    @Test
    void corruptedCycleTerminatesWithParameterError() {
        when(fileService.getFileInfoByFileIdAndUserId("a", "owner")).thenReturn(file("a", "b", 1));
        when(fileService.getFileInfoByFileIdAndUserId("b", "owner")).thenReturn(file("b", "a", 1));
        assertCode(600, () -> service.requireSharedFile(share, "a"));
        verify(fileService, times(1)).getFileInfoByFileIdAndUserId("a", "owner");
    }

    @Test
    void rejectsPathAndSqlFragmentsBeforeQuery() {
        for (String value : List.of("../file", "file\\name", "x\"),sleep(1)#", "", "root_000.ts/../x")) {
            assertCode(600, () -> service.requireSharedFile(share, value));
        }
        verifyNoInteractions(fileService);
    }

    @Test
    void videoSegmentMustBelongToSharedVideo() {
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(file("root", "0", 1));
        FileInfo video = file("video", "root", 0);
        when(fileService.getFileInfoByFileIdAndUserId("video", "owner")).thenReturn(video);
        assertSame(video, service.requirePreviewFile(share, "video_0001.ts"));
        assertCode(600, () -> service.requirePreviewFile(share, "video_..\\x.ts"));
    }

    @Test
    void copiedVideoSegmentsNeedAnActualCopyInsideShare() {
        FileInfo original = file("original", "0", 0);
        original.setFilePath("202610/original.mp4");
        FileInfo copy = file("copy", "root", 0);
        copy.setFilePath(original.getFilePath());
        when(fileService.getFileInfoByFileIdAndUserId("original", "owner")).thenReturn(null);
        when(fileService.findListByParam(any())).thenReturn(List.of(original), List.of(copy));
        when(fileService.getFileInfoByFileIdAndUserId("copy", "owner")).thenReturn(copy);
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(file("root", "0", 1));
        assertSame(copy, service.requirePreviewFile(share, "original_0001.ts"));
    }

    @Test
    void sameOwnerVideoOutsideShareRequiresAnAuthorizedCopyWithSamePath() {
        FileInfo original = file("original", "0", 0);
        original.setFilePath("202610/original.mp4");
        FileInfo copy = file("copy", "root", 0);
        copy.setFilePath(original.getFilePath());
        when(fileService.getFileInfoByFileIdAndUserId("original", "owner")).thenReturn(original);
        when(fileService.getFileInfoByFileIdAndUserId("copy", "owner")).thenReturn(copy);
        when(fileService.getFileInfoByFileIdAndUserId("root", "owner")).thenReturn(file("root", "0", 1));
        when(fileService.findListByParam(any())).thenReturn(List.of(original, copy), List.of(original));
        assertSame(copy, service.requirePreviewFile(share, "original_0001.ts"));
        assertCode(600, () -> service.requirePreviewFile(share, "original_0001.ts"));
        verify(fileService, times(2)).findListByParam(org.mockito.ArgumentMatchers.argThat(query ->
                "owner".equals(query.getUserId()) && original.getFilePath().equals(query.getFilePath())
                        && Integer.valueOf(2).equals(query.getDelFlag())));
    }

    @Test
    void targetMustBeOwnLiveFolder() {
        service.requireOwnFolder("receiver", "0");
        when(fileService.getFileInfoByFileIdAndUserId("folder", "receiver")).thenReturn(file("folder", "0", 1));
        when(fileService.getFileInfoByFileIdAndUserId("document", "receiver")).thenReturn(file("document", "0", 0));
        when(fileService.getFileInfoByFileIdAndUserId("foreign", "receiver")).thenReturn(null);
        service.requireOwnFolder("receiver", "folder");
        assertCode(600, () -> service.requireOwnFolder("receiver", "foreign"));
        assertCode(600, () -> service.requireOwnFolder("receiver", "document"));
    }

    static FileInfo file(String id, String parent, int folderType) {
        FileInfo file = new FileInfo();
        file.setFileId(id);
        file.setFilePid(parent);
        file.setUserId("owner");
        file.setFolderType(folderType);
        file.setDelFlag(2);
        return file;
    }

    static void assertCode(int code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(BusinessException.class, operation).getCode());
    }
}
