package top.enderherman.netdisk;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.UploadResultDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.service.FileService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:upload_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class FileUploadTest {
    @TempDir Path temporary;
    @Autowired FileService files;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppConfig config;
    @MockBean RedisComponent redisComponent;
    @MockBean JavaMailSender mailSender;
    Path root;

    @BeforeEach void setup() {
        clear();
        config.setProjectFolder(temporary.resolve("storage with spaces 中文").toString());
        root = Path.of(config.getProjectFolder()).resolve("file");
        jdbc.update("insert into user_info(user_id,status,use_space,total_space) values ('alice',1,0,1000000),('bob',1,0,1000000)");
    }

    @AfterEach void clear() {
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
    }

    @Test void completeUploadPersistsVerifiedSizeHashBytesAndReadyStatus() throws Exception {
        UploadResultDto first = upload("alice", null, "abc", "data.txt", 0, 2, "abcdef");
        assertEquals("uploading", first.getStatus());
        assertEquals(0, count());
        UploadResultDto done = upload("alice", first.getFileId(), "def", "data.txt", 1, 2, "abcdef");
        FileInfo stored = info("alice", done.getFileId());
        assertEquals("upload_finish", done.getStatus());
        assertEquals(6L, stored.getFileSize());
        assertEquals(DigestUtils.md5Hex("abcdef"), stored.getFileMd5());
        assertEquals(2, stored.getStatus());
        assertEquals("abcdef", Files.readString(root.resolve(stored.getFilePath())));
        assertEquals(6L, space("alice"));
        assertTrue(Files.exists(task("alice", done.getFileId()).resolve("manifest.json")));
        assertFalse(Files.exists(task("alice", done.getFileId()).resolve("0.chunk")));
    }

    @Test void outOfOrderChunksCompleteOnlyAfterAllIndexesArrive() throws Exception {
        String id = upload("alice", null, "A", "order.txt", 0, 3, "ABC").getFileId();
        assertEquals("uploading", upload("alice", id, "C", "order.txt", 2, 3, "ABC").getStatus());
        assertEquals(0, count());
        assertEquals("upload_finish", upload("alice", id, "B", "order.txt", 1, 3, "ABC").getStatus());
        assertEquals("ABC", Files.readString(root.resolve(info("alice", id).getFilePath())));
    }

    @Test void duplicatePartsAndCompletedRequestsDoNotDoubleChargeSpace() {
        quota("alice", 3);
        String id = upload("alice", null, "A", "retry.txt", 0, 3, "ABC").getFileId();
        upload("alice", id, "A", "retry.txt", 0, 3, "ABC");
        upload("alice", id, "C", "retry.txt", 2, 3, "ABC");
        upload("alice", id, "B", "retry.txt", 1, 3, "ABC");
        assertEquals("upload_finish", upload("alice", id, "B", "retry.txt", 1, 3, "ABC").getStatus());
        assertEquals(1, count());
        assertEquals(3L, space("alice"));
    }

    @Test void changedRetryBytesAreRejectedWithoutReplacingAcceptedPart() throws Exception {
        String id = upload("alice", null, "A", "retry.txt", 0, 2, "AB").getFileId();
        assertThrows(BusinessException.class, () -> upload("alice", id, "X", "retry.txt", 0, 2, "AB"));
        assertEquals("A", Files.readString(task("alice", id).resolve("0.chunk")));
        upload("alice", id, "B", "retry.txt", 1, 2, "AB");
        assertThrows(BusinessException.class, () -> upload("alice", id, "X", "retry.txt", 1, 2, "AB"));
        assertEquals(2L, space("alice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "parent", "hash", "chunks"})
    void manifestRejectsMetadataChanges(String changed) {
        String id = upload("alice", null, "A", "immutable.txt", 0, 2, "AB").getFileId();
        String name = changed.equals("name") ? "changed.txt" : "immutable.txt";
        String parent = changed.equals("parent") ? "other" : "0";
        String hash = DigestUtils.md5Hex(changed.equals("hash") ? "different" : "AB");
        int chunks = changed.equals("chunks") ? 3 : 2;
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), id, part("B"), name, parent, hash, 1, chunks));
        assertEquals(0, count());
        assertEquals("upload_finish", upload("alice", id, "B", "immutable.txt", 1, 2, "AB").getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/../../target", "C:\\outside", "ABCDEFGHIJ"})
    void callerCannotCreateAnUploadUsingItsOwnIdentifier(String id) {
        assertThrows(BusinessException.class, () -> upload("alice", id, "A", "safe.txt", 0, 2, "AB"));
        assertEquals(0, count());
        assertFalse(Files.exists(temporary.resolve("escape")));
    }

    @Test void oneUsersTaskCannotBeResumedByAnotherUser() {
        String id = upload("alice", null, "A", "private.txt", 0, 2, "AB").getFileId();
        assertThrows(BusinessException.class, () -> upload("bob", id, "B", "private.txt", 1, 2, "AB"));
        assertEquals(0, count());
    }

    @Test void disabledAccountCannotStartOrPersistAnUpload() throws Exception {
        jdbc.update("update user_info set status=0 where user_id='alice'");
        assertThrows(BusinessException.class, () -> upload("alice", null, "A", "disabled.txt", 0, 1, "A"));
        assertEquals(0, count());
        assertEquals(0L, space("alice"));
        try (Stream<Path> paths = Files.walk(root)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().equals("manifest.json") || p.toString().endsWith(".chunk")));
        }
    }

    @Test void accountDisabledBetweenChunksCannotAcceptMoreBytesOrComplete() throws Exception {
        String id = upload("alice", null, "A", "disabled.txt", 0, 2, "AB").getFileId();
        jdbc.update("update user_info set status=0 where user_id='alice'");
        assertThrows(BusinessException.class, () -> upload("alice", id, "B", "disabled.txt", 1, 2, "AB"));
        assertEquals(0, count());
        assertFalse(Files.exists(task("alice", id).resolve("1.chunk")));
        assertEquals("A", Files.readString(task("alice", id).resolve("0.chunk")));
        jdbc.update("update user_info set status=1 where user_id='alice'");
        assertEquals("upload_finish", upload("alice", id, "B", "disabled.txt", 1, 2, "AB").getStatus());
    }

    @Test void malformedHashesMissingPartsAndInvalidChunkBoundariesAreRejected() {
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), null, null, "x", "0", DigestUtils.md5Hex("x"), 0, 1));
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), null, part("x"), "x", "0", "not-md5", 0, 1));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "x", -1, 1, "x"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "x", 1, 1, "x"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "x", 1, 2, "xy"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "x", 0, 0, "x"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "x", 0, 10001, "x"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "", "x", 0, 2, "x"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "x", "../unsafe", 0, 1, "x"));
    }

    @Test void parentMustBeAnOwnedLiveFolderAndStayLiveUntilCompletion() {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,del_flag,status) values ('folder','alice','0','Folder',1,2,2),('foreign','bob','0','Other',1,2,2)");
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), null, part("A"), "x", "foreign", DigestUtils.md5Hex("AB"), 0, 2));
        String id = files.uploadFile(user("alice"), null, part("A"), "x", "folder", DigestUtils.md5Hex("AB"), 0, 2).getFileId();
        jdbc.update("update file_info set del_flag=1 where file_id='folder'");
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), id, part("B"), "x", "folder", DigestUtils.md5Hex("AB"), 1, 2));
        assertNull(info("alice", id));
        assertEquals(0L, space("alice"));
    }

    @Test void wholeFileHashMismatchNeverCreatesAReadyFileOrChargesDatabaseQuota() {
        String id = upload("alice", null, "A", "wrong.txt", 0, 2, "ZZ").getFileId();
        assertThrows(BusinessException.class, () -> upload("alice", id, "B", "wrong.txt", 1, 2, "ZZ"));
        assertEquals(0, count());
        assertEquals(0L, space("alice"));
        assertThrows(BusinessException.class, () -> upload("alice", null, "bad", "single.txt", 0, 1, "good"));
    }

    @Test void corruptedStoredPartFailsDigestVerification() throws Exception {
        String id = upload("alice", null, "A", "corrupt.txt", 0, 2, "AB").getFileId();
        Files.writeString(task("alice", id).resolve("0.chunk"), "X");
        assertThrows(BusinessException.class, () -> upload("alice", id, "B", "corrupt.txt", 1, 2, "AB"));
        assertEquals(0, count());
    }

    @Test void missingStoredPartCanBeResentAndDoesNotCausePrematureSuccess() throws Exception {
        String id = upload("alice", null, "A", "missing.txt", 0, 2, "AB").getFileId();
        Files.delete(task("alice", id).resolve("0.chunk"));
        assertEquals("uploading", upload("alice", id, "B", "missing.txt", 1, 2, "AB").getStatus());
        assertEquals(0, count());
        assertEquals("upload_finish", upload("alice", id, "A", "missing.txt", 0, 2, "AB").getStatus());
    }

    @Test void instantUploadOnlyUsesVerifiedOwnContentAndAllocatesUniqueName() {
        String first = upload("alice", null, "body", "same.txt", 0, 1, "body").getFileId();
        UploadResultDto own = upload("alice", null, "body", "same.txt", 0, 1, "body");
        assertEquals("upload_seconds", own.getStatus());
        assertEquals(info("alice", first).getFilePath(), info("alice", own.getFileId()).getFilePath());
        assertEquals("same (1).txt", info("alice", own.getFileId()).getFileName());
        UploadResultDto other = upload("bob", null, "body", "same.txt", 0, 1, "body");
        assertEquals("upload_finish", other.getStatus());
        assertNotEquals(info("alice", first).getFilePath(), info("bob", other.getFileId()).getFilePath());
        assertEquals(8L, space("alice"));
        assertEquals(4L, space("bob"));
    }

    @Test void poisonedLegacyHashDoesNotEnableInstantUpload() throws Exception {
        String original = upload("alice", null, "bad!", "poison.txt", 0, 1, "bad!").getFileId();
        jdbc.update("update file_info set file_md5=? where file_id=?", DigestUtils.md5Hex("good"), original);
        UploadResultDto uploaded = upload("alice", null, "good", "good.txt", 0, 1, "good");
        assertEquals("upload_finish", uploaded.getStatus());
        assertEquals("good", Files.readString(root.resolve(info("alice", uploaded.getFileId()).getFilePath())));
    }

    @Test void allIncompleteTasksCountTowardsTemporaryQuota() {
        quota("alice", 5);
        String first = upload("alice", null, "abc", "one.txt", 0, 2, "abcd").getFileId();
        upload("alice", null, "xy", "two.txt", 0, 2, "xyz");
        BusinessException full = assertThrows(BusinessException.class, () -> upload("alice", first, "d", "one.txt", 1, 2, "abcd"));
        assertEquals(904, full.getCode());
        assertEquals("uploading", upload("alice", first, "abc", "one.txt", 0, 2, "abcd").getStatus());
        assertEquals(0L, space("alice"));
        assertEquals(0, count());
    }

    @Test void databaseRollbackPreservesChunksAndCanBeRetriedWithoutLeakingFinalContent() throws Exception {
        String id = upload("alice", null, "ab", "rollback.txt", 0, 2, "abcd").getFileId();
        jdbc.execute("alter table user_info add constraint upload_rollback check (use_space=0)");
        try {
            assertThrows(RuntimeException.class, () -> upload("alice", id, "cd", "rollback.txt", 1, 2, "abcd"));
            assertEquals(0, count());
            assertTrue(Files.exists(task("alice", id).resolve("0.chunk")));
            assertTrue(Files.exists(task("alice", id).resolve("1.chunk")));
            try (Stream<Path> paths = Files.walk(root)) {
                assertEquals(0L, paths.filter(Files::isRegularFile).filter(p -> !p.startsWith(root.resolve("temp"))).count());
            }
        } finally { jdbc.execute("alter table user_info drop constraint upload_rollback"); }
        assertEquals("upload_finish", upload("alice", id, "cd", "rollback.txt", 1, 2, "abcd").getStatus());
        assertEquals(4L, space("alice"));
    }

    @Test void sharedFileCopyRespectsPendingUploadsButDoesNotDoubleCountCompletedReceipts() {
        String source = upload("bob", null, "123456", "shared.txt", 0, 1, "123456").getFileId();
        quota("alice", 8);
        upload("alice", null, "abc", "pending.txt", 0, 2, "abcd");
        BusinessException full = assertThrows(BusinessException.class,
                () -> files.saveShare(source, source, "0", "bob", "alice"));
        assertEquals(904, full.getCode());
        assertEquals(0L, space("alice"));
        quota("alice", 9);
        assertDoesNotThrow(() -> files.saveShare(source, source, "0", "bob", "alice"));
        assertEquals(6L, space("alice"));

        // Bob 的原件完成凭据不能作为待上传空间再次计入。
        quota("alice", 10);
        String other = upload("alice", null, "!", "one.txt", 0, 1, "!").getFileId();
        quota("bob", 7);
        assertDoesNotThrow(() -> files.saveShare(other, other, "0", "alice", "bob"));
        assertEquals(7L, space("bob"));
    }

    @Test void inputFailureDoesNotReturnSuccessOrEraseEarlierParts() throws Exception {
        String id = upload("alice", null, "A", "io.txt", 0, 2, "AB").getFileId();
        MockMultipartFile broken = new MockMultipartFile("file", bytes("B")) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("simulated input failure"); }
        };
        assertThrows(BusinessException.class, () -> files.uploadFile(user("alice"), id, broken, "io.txt", "0", DigestUtils.md5Hex("AB"), 1, 2));
        assertEquals("A", Files.readString(task("alice", id).resolve("0.chunk")));
        assertEquals("upload_finish", upload("alice", id, "B", "io.txt", 1, 2, "AB").getStatus());
    }

    @Test void unusableStorageRootReturnsFailure() throws Exception {
        Path blocked = Files.writeString(temporary.resolve("regular-file"), "not a directory");
        config.setProjectFolder(blocked.toString());
        assertThrows(BusinessException.class, () -> upload("alice", null, "A", "io.txt", 0, 1, "A"));
        assertEquals(0, count());
    }

    @Test void finalStorageWriteFailureKeepsAcceptedPartsForRetry() throws Exception {
        String id = upload("alice", null, "A", "disk.txt", 0, 2, "AB").getFileId();
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        Path blocked = Files.writeString(root.resolve(month), "not a directory");
        assertThrows(BusinessException.class, () -> upload("alice", id, "B", "disk.txt", 1, 2, "AB"));
        assertEquals(0, count());
        assertTrue(Files.exists(task("alice", id).resolve("0.chunk")));
        assertTrue(Files.exists(task("alice", id).resolve("1.chunk")));
        Files.delete(blocked);
        assertEquals("upload_finish", upload("alice", id, "B", "disk.txt", 1, 2, "AB").getStatus());
    }

    @Test void redisFailureAfterCommitDoesNotLoseACompletedUpload() {
        doThrow(new IllegalStateException("Redis offline")).when(redisComponent).resetUserSpaceUse("alice");
        assertEquals("upload_finish", upload("alice", null, "A", "cache.txt", 0, 1, "A").getStatus());
        assertEquals(1, count());
        assertEquals(1L, space("alice"));
    }

    @Test void zeroByteFileIsAValidSingleChunkUpload() throws Exception {
        String id = upload("alice", null, "", "empty.txt", 0, 1, "").getFileId();
        assertEquals(0L, info("alice", id).getFileSize());
        assertEquals(0L, Files.size(root.resolve(info("alice", id).getFilePath())));
    }

    @Test void completedReceiptCannotResurrectPermanentlyDeletedOrPurgedFiles() {
        String id = upload("alice", null, "A", "deleted.txt", 0, 1, "A").getFileId();
        files.removeFile2RecycleBatch("alice", id);
        files.deleteFile("alice", id, false);
        assertThrows(BusinessException.class, () -> upload("alice", id, "A", "deleted.txt", 0, 1, "A"));
        files.deleteFileInfoByFileIdAndUserId(id, "alice");
        assertThrows(BusinessException.class, () -> upload("alice", id, "A", "deleted.txt", 0, 1, "A"));
        assertThrows(BusinessException.class, () -> upload("alice", id, "A", "deleted.txt", 0, 1, "A"));
        assertNull(info("alice", id));
        assertEquals(0L, space("alice"));
    }

    @Test void imageThumbnailIsRealJpegAndOriginalBytesArePreserved() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] data = output.toByteArray();
        UploadResultDto uploaded = files.uploadFile(user("alice"), null,
                new MockMultipartFile("file", "picture.PNG", "image/png", data), "picture.PNG", "0", DigestUtils.md5Hex(data), 0, 1);
        FileInfo file = info("alice", uploaded.getFileId());
        assertEquals(3, file.getFileType());
        assertNotNull(file.getFileCover());
        assertEquals(150, ImageIO.read(root.resolve(file.getFileCover()).toFile()).getWidth());
        assertArrayEquals(data, Files.readAllBytes(root.resolve(file.getFilePath())));
    }

    @Test void unsupportedImageAndVideoKeepReadyOriginalsWithoutFakePreviews() throws Exception {
        String image = upload("alice", null, "not image", "broken.png", 0, 1, "not image").getFileId();
        String video = upload("alice", null, "video bytes", "movie.mp4", 0, 1, "video bytes").getFileId();
        assertNull(info("alice", image).getFileCover());
        assertNull(info("alice", video).getFileCover());
        assertEquals(2, info("alice", image).getStatus());
        assertEquals(2, info("alice", video).getStatus());
        try (Stream<Path> paths = Files.walk(root)) {
            assertFalse(paths.anyMatch(p -> p.toString().endsWith(".m3u8")));
        }
    }

    @Test void completingSameTaskConcurrentlyInsertsExactlyOnce() throws Exception {
        String id = upload("alice", null, "A", "concurrent.txt", 0, 2, "AB").getFileId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<UploadResultDto> finish = () -> upload("alice", id, "B", "concurrent.txt", 1, 2, "AB");
            List<Future<UploadResultDto>> results = pool.invokeAll(List.of(finish, finish));
            for (Future<UploadResultDto> result : results) assertEquals("upload_finish", result.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(1, count());
            assertEquals(2L, space("alice"));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentUploadsCannotExceedDatabaseQuota() throws Exception {
        quota("alice", 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> tryUpload("aaaaaa", "one.txt");
            Callable<Boolean> second = () -> tryUpload("bbbbbb", "two.txt");
            List<Future<Boolean>> results = pool.invokeAll(List.of(first, second));
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, count());
            assertEquals(6L, space("alice"));
        } finally { pool.shutdownNow(); }
    }

    private boolean tryUpload(String data, String name) {
        try { upload("alice", null, data, name, 0, 1, data); return true; }
        catch (BusinessException ex) { assertEquals(904, ex.getCode()); return false; }
    }
    private UploadResultDto upload(String user, String id, String data, String name, int index, int chunks, String full) {
        return files.uploadFile(user(user), id, part(data), name, "0", DigestUtils.md5Hex(full), index, chunks);
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private MockMultipartFile part(String value) { return new MockMultipartFile("file", "part", "application/octet-stream", bytes(value)); }
    private SessionWebUserDto user(String id) { SessionWebUserDto user = new SessionWebUserDto(); user.setUserId(id); user.setIsAdmin(false); return user; }
    private FileInfo info(String user, String id) { return files.getFileInfoByFileIdAndUserId(id, user); }
    private long count() { return jdbc.queryForObject("select count(*) from file_info where folder_type=0", Long.class); }
    private long space(String user) { return jdbc.queryForObject("select use_space from user_info where user_id=?", Long.class, user); }
    private void quota(String user, long value) { jdbc.update("update user_info set total_space=? where user_id=?", value, user); }
    private Path task(String user, String id) { return root.resolve("temp").resolve(user).resolve(id); }
}
