package top.enderherman.netdisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.task.ScheduledTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageCleanupTest {
    @TempDir Path temporary;
    @Mock FileMapper<FileInfo, FileQuery> fileMapper;
    @Mock AppConfig appConfig;
    ScheduledTask task;

    @BeforeEach void setup() {
        task = new ScheduledTask();
        ReflectionTestUtils.setField(task, "fileMapper", fileMapper);
        ReflectionTestUtils.setField(task, "appConfig", appConfig);
        lenient().when(appConfig.getProjectFolder()).thenReturn(temporary.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void retainedReferenceProtectsContentCoverAndVideoSegments(int retainedState) throws Exception {
        FileInfo deleted = video();
        FileInfo retained = video();
        retained.setUserId("bob");
        retained.setDelFlag(retainedState);
        Path content = write(deleted.getFilePath());
        Path cover = write(deleted.getFileCover());
        Path segment = write("202610/video/segment.ts");
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        when(fileMapper.selectStorageReferencesForUpdate(deleted.getFilePath())).thenReturn(List.of(deleted, retained));
        task.autoDeleteFile();
        assertTrue(Files.exists(content));
        assertTrue(Files.exists(cover));
        assertTrue(Files.exists(segment));
        verify(fileMapper).deleteByFileIdAndUserId("video", "alice");
        verify(fileMapper, never()).deleteByFileIdAndUserId(anyString(), eq("bob"));
    }

    @Test void lastDeletedReferenceRemovesContentCoverVideoAndItsRecord() throws Exception {
        FileInfo deleted = video();
        Path content = write(deleted.getFilePath());
        Path cover = write(deleted.getFileCover());
        Path segment = write("202610/video/deep/segment.ts");
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        when(fileMapper.selectStorageReferencesForUpdate(deleted.getFilePath())).thenReturn(List.of(deleted));
        task.autoDeleteFile();
        assertFalse(Files.exists(content));
        assertFalse(Files.exists(cover));
        assertFalse(Files.exists(segment));
        assertFalse(Files.exists(segment.getParent().getParent()));
        verify(fileMapper).deleteByFileIdAndUserId("video", "alice");
    }

    @Test void traversalPathCannotDeleteOutsideStorageAndRecordRemains() throws Exception {
        Path protectedFile = temporary.resolve("protected.txt");
        Files.writeString(protectedFile, "private");
        Files.createDirectories(temporary.resolve("file"));
        FileInfo deleted = video();
        deleted.setFilePath("../protected.txt");
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        when(fileMapper.selectStorageReferencesForUpdate(deleted.getFilePath())).thenReturn(List.of(deleted));
        task.autoDeleteFile();
        assertEquals("private", Files.readString(protectedFile));
        verify(fileMapper, never()).deleteByFileIdAndUserId(anyString(), anyString());
    }

    @Test void invalidCoverIsCheckedBeforeContentIsDeleted() throws Exception {
        FileInfo deleted = video();
        Path content = write(deleted.getFilePath());
        deleted.setFileCover("../outside.png");
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        when(fileMapper.selectStorageReferencesForUpdate(deleted.getFilePath())).thenReturn(List.of(deleted));
        task.autoDeleteFile();
        assertTrue(Files.exists(content));
        verify(fileMapper, never()).deleteByFileIdAndUserId(anyString(), anyString());
    }

    @Test void missingPhysicalFileIsAnIdempotentCleanup() {
        FileInfo deleted = video();
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        when(fileMapper.selectStorageReferencesForUpdate(deleted.getFilePath())).thenReturn(List.of(deleted));
        task.autoDeleteFile();
        verify(fileMapper).deleteByFileIdAndUserId("video", "alice");
    }

    @Test void deletedFolderMetadataIsRemovedWithoutTouchingStorageRoot() throws Exception {
        FileInfo folder = new FileInfo();
        folder.setFileId("folder");
        folder.setUserId("alice");
        folder.setFolderType(1);
        folder.setDelFlag(3);
        Path retained = write("keep.txt");
        when(fileMapper.selectList(any())).thenReturn(List.of(folder));
        task.autoDeleteFile();
        assertTrue(Files.exists(retained));
        verify(fileMapper).deleteByFileIdAndUserId("folder", "alice");
        verify(fileMapper, never()).selectStorageReferencesForUpdate(any());
    }

    @Test void missingStoragePathIsRetainedForInvestigation() {
        FileInfo deleted = video();
        deleted.setFilePath(null);
        when(fileMapper.selectList(any())).thenReturn(List.of(deleted));
        task.autoDeleteFile();
        verify(fileMapper, never()).deleteByFileIdAndUserId(anyString(), anyString());
    }

    private Path write(String relative) throws Exception {
        Path target = temporary.resolve("file").resolve(relative);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, "test bytes");
    }

    private FileInfo video() {
        FileInfo file = new FileInfo();
        file.setFileId("video");
        file.setUserId("alice");
        file.setFolderType(0);
        file.setFileType(1);
        file.setDelFlag(3);
        file.setFilePath("202610/video.mp4");
        file.setFileCover("202610/video.png");
        return file;
    }
}
