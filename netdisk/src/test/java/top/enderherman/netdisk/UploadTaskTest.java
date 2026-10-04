package top.enderherman.netdisk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.impl.FileUploadService;
import top.enderherman.netdisk.service.impl.UploadTaskService;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:upload_tasks_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "netdisk.upload.task-ttl-hours=2", "netdisk.upload.receipt-ttl-hours=5"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UploadTaskTest {
    @TempDir Path temporary;
    @Autowired FileService files;
    @Autowired UploadTaskService tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppConfig config;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockBean RedisComponent redis;
    @MockBean JavaMailSender mailSender;
    Path root;

    @BeforeEach void setup() {
        clear();
        config.setProjectFolder(temporary.resolve("task storage 中文").toString());
        root = Path.of(config.getProjectFolder()).resolve("file");
        jdbc.update("insert into user_info(user_id,status,use_space,total_space) values ('alice',1,0,10000),('bob',1,0,10000)");
    }

    @AfterEach void clear() {
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
    }

    @Test void detailReportsAcceptedIndexesAndAllowsActualResume() throws Exception {
        String id = upload("alice", null, "A", 0, 3, "ABC");
        upload("alice", id, "C", 2, 3, "ABC");
        var state = tasks.detail("alice", id);
        assertEquals("uploading", state.getState());
        assertEquals(3, state.getChunks());
        assertEquals(DigestUtils.md5Hex("ABC"), state.getFileMd5());
        assertEquals(List.of(0, 2), state.getReceivedChunks().stream().map(chunk -> chunk.index()).toList());
        assertEquals(2L, state.getReceivedBytes());
        assertEquals(2L, state.getTemporaryBytes());
        upload("alice", id, "B", 1, 3, "ABC");
        assertEquals("completed", tasks.detail("alice", id).getState());
        assertEquals(0L, tasks.detail("alice", id).getTemporaryBytes());
        assertTrue(tasks.detail("alice", id).isFileAvailable());
        assertEquals(3L, tasks.detail("alice", id).getFileSize());
    }

    @Test void missingPartIsExcludedFromResumeStatus() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        Files.delete(task("alice", id).resolve("0.chunk"));
        assertTrue(tasks.detail("alice", id).getReceivedChunks().isEmpty());
        assertEquals(0L, tasks.detail("alice", id).getReceivedBytes());
    }

    @Test void listsAndDetailsOnlyExposeCurrentUsersTasks() {
        String own = upload("alice", null, "A", 0, 2, "AB");
        String foreign = upload("bob", null, "X", 0, 2, "XY");
        var list = tasks.list("alice", 1, 20, null);
        assertEquals(1, list.getTotalCount());
        assertEquals(own, list.getList().get(0).getFileId());
        assertTrue(list.getList().get(0).getReceivedChunks().isEmpty());
        assertThrows(BusinessException.class, () -> tasks.detail("alice", foreign));
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", foreign));
        assertEquals(1L, tasks.detail("bob", foreign).getReceivedBytes());
    }

    @Test void cancellationIsIdempotentReleasesQuotaAndCannotBeResumed() {
        jdbc.update("update user_info set total_space=2 where user_id='alice'");
        String id = upload("alice", null, "AB", 0, 2, "ABC");
        assertEquals("cancelled", tasks.cancel("alice", id).getState());
        assertEquals("cancelled", tasks.cancel("alice", id).getState());
        assertEquals(0L, tasks.detail("alice", id).getTemporaryBytes());
        assertThrows(BusinessException.class, () -> upload("alice", id, "C", 1, 2, "ABC"));
        assertNotNull(upload("alice", null, "OK", 0, 1, "OK"));
        assertEquals(2L, jdbc.queryForObject("select use_space from user_info where user_id='alice'", Long.class));
    }

    @Test void cancellingCompletedTaskNeverDeletesOriginal() throws Exception {
        String id = upload("alice", null, "AB", 0, 1, "AB");
        Path original = root.resolve(files.getFileInfoByFileIdAndUserId(id, "alice").getFilePath());
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", id));
        assertEquals("AB", Files.readString(original));
        assertEquals("completed", tasks.detail("alice", id).getState());
    }

    @Test void configuredInactivityTtlExpiresBytesAndRejectsResume() throws Exception {
        String expired = upload("alice", null, "A", 0, 2, "AB");
        String recent = upload("alice", null, "X", 0, 2, "XY");
        age("alice", expired, 3);
        assertEquals("expired", tasks.detail("alice", expired).getState());
        var result = tasks.cleanupExpiredTasks();
        assertEquals(1, result.expiredTasks());
        assertEquals(0, result.failedTasks());
        assertEquals(0L, tasks.detail("alice", expired).getTemporaryBytes());
        assertEquals(1L, tasks.detail("alice", recent).getTemporaryBytes());
        assertThrows(BusinessException.class, () -> upload("alice", expired, "B", 1, 2, "AB"));
    }

    @Test void expiredRequestClosesItselfEvenBeforeScheduledCleanup() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        age("alice", id, 3);
        assertThrows(BusinessException.class, () -> upload("alice", id, "B", 1, 2, "AB"));
        assertEquals("expired", tasks.detail("alice", id).getState());
        assertEquals(0L, tasks.detail("alice", id).getTemporaryBytes());
    }

    @Test void completedAndCancelledReceiptsExpireWithoutDeletingOriginalsOrRebuildingIds() throws Exception {
        String completed = upload("alice", null, "Done", 0, 1, "Done");
        String cancelled = upload("alice", null, "X", 0, 2, "XY");
        tasks.cancel("alice", cancelled);
        age("alice", completed, 6);
        age("alice", cancelled, 6);
        Path original = root.resolve(files.getFileInfoByFileIdAndUserId(completed, "alice").getFilePath());
        var result = tasks.cleanupExpiredTasks();
        assertEquals(2, result.removedReceipts());
        assertFalse(Files.exists(task("alice", completed)));
        assertFalse(Files.exists(task("alice", cancelled)));
        assertEquals("Done", Files.readString(original));
        assertThrows(BusinessException.class, () -> upload("alice", completed, "Done", 0, 1, "Done"));
        assertThrows(BusinessException.class, () -> upload("alice", cancelled, "X", 0, 2, "XY"));
    }

    @Test void cleanupCanExpireDisabledUsersButInteractiveQueriesCannotAccessThem() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        age("alice", id, 3);
        jdbc.update("update user_info set status=0 where user_id='alice'");
        assertThrows(BusinessException.class, () -> tasks.detail("alice", id));
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", id));
        assertEquals(1, tasks.cleanupExpiredTasks().expiredTasks());
        assertFalse(Files.exists(task("alice", id).resolve("0.chunk")));
    }

    @Test void forgedManifestIdentityPreventsBothCancellationAndScheduledDeletion() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        age("alice", id, 3);
        var manifest = read("alice", id);
        manifest.setUserId("bob");
        write("alice", id, manifest);
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", id));
        assertEquals(1, tasks.cleanupExpiredTasks().failedTasks());
        assertEquals("A", Files.readString(task("alice", id).resolve("0.chunk")));
        assertEquals(0, tasks.list("alice", 1, 20, null).getTotalCount());
    }

    @Test void cleanupDoesNotRecursivelyDeleteUnknownDirectoriesOrTraverseIds() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        age("alice", id, 3);
        Path protectedFile = task("alice", id).resolve("unknown/protected.txt");
        Files.createDirectories(protectedFile.getParent());
        Files.writeString(protectedFile, "preserve");
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", id));
        assertThrows(BusinessException.class, () -> tasks.cancel("alice", "../escape"));
        assertEquals(1, tasks.cleanupExpiredTasks().failedTasks());
        assertEquals("preserve", Files.readString(protectedFile));
        assertTrue(Files.exists(task("alice", id).resolve("0.chunk")));
    }

    @Test void concurrentCompletionAndCancellationHaveExactlyOneSafeOutcome() throws Exception {
        String id = upload("alice", null, "A", 0, 2, "AB");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<Boolean> complete = () -> { start.await(); try { upload("alice", id, "B", 1, 2, "AB"); return true; } catch (BusinessException expected) { return false; } };
            Callable<Boolean> cancel = () -> { start.await(); try { tasks.cancel("alice", id); return true; } catch (BusinessException expected) { return false; } };
            var outcomes = executor.invokeAll(List.of(complete, cancel));
            boolean completed = outcomes.get(0).get(10, TimeUnit.SECONDS);
            boolean cancelled = outcomes.get(1).get(10, TimeUnit.SECONDS);
            assertNotEquals(completed, cancelled);
            var state = tasks.detail("alice", id);
            assertEquals(completed ? "completed" : "cancelled", state.getState());
            if (completed) {
                var original = files.getFileInfoByFileIdAndUserId(id, "alice");
                assertEquals("AB", Files.readString(root.resolve(original.getFilePath())));
            } else {
                assertNull(files.getFileInfoByFileIdAndUserId(id, "alice"));
                assertEquals(0L, state.getTemporaryBytes());
                assertFalse(Files.exists(task("alice", id).resolve("1.chunk")));
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void listFiltersPaginationAndHttpContractsAreUsable() throws Exception {
        String pending = upload("alice", null, "A", 0, 2, "AB");
        String cancelled = upload("alice", null, "X", 0, 2, "XY");
        tasks.cancel("alice", cancelled);
        assertEquals(1, tasks.list("alice", 1, 20, "uploading").getTotalCount());
        assertEquals(100, tasks.list("alice", 99, 999, null).getPageSize());
        assertThrows(BusinessException.class, () -> tasks.list("alice", 0, 20, null));
        assertThrows(BusinessException.class, () -> tasks.list("alice", 1, 20, "invalid"));
        mvc.perform(get("/file/uploadTask/" + pending).session(session("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedChunks[0].index").value(0))
                .andExpect(jsonPath("$.data.receivedChunks[0].size").value(1));
        mvc.perform(get("/file/uploadTasks").session(session("alice")).param("state", "uploading"))
                .andExpect(jsonPath("$.data.totalCount").value(1));
        mvc.perform(post("/file/cancelUpload/" + pending).session(session("alice")))
                .andExpect(jsonPath("$.data.state").value("cancelled"));
        mvc.perform(get("/file/uploadTasks")).andExpect(jsonPath("$.code").value(901));
    }

    private String upload(String user, String id, String bytes, int index, int chunks, String whole) {
        return files.uploadFile(user(user), id, new MockMultipartFile("file", bytes.getBytes(StandardCharsets.UTF_8)),
                "task.txt", "0", DigestUtils.md5Hex(whole), index, chunks).getFileId();
    }
    private SessionWebUserDto user(String id) { var value = new SessionWebUserDto(); value.setUserId(id); value.setIsAdmin(false); return value; }
    private MockHttpSession session(String id) { var session = new MockHttpSession(); session.setAttribute(Constants.SESSION_KEY, user(id)); return session; }
    private Path task(String user, String id) { return root.resolve("temp").resolve(user).resolve(id); }
    private FileUploadService.Manifest read(String user, String id) throws Exception { return json.readValue(task(user, id).resolve("manifest.json").toFile(), FileUploadService.Manifest.class); }
    private void write(String user, String id, FileUploadService.Manifest manifest) throws Exception { json.writeValue(task(user, id).resolve("manifest.json").toFile(), manifest); }
    private void age(String user, String id, long hours) throws Exception {
        var manifest = read(user, id);
        long time = System.currentTimeMillis() - hours * 3_600_000L;
        manifest.setCreatedAt(time); manifest.setUpdatedAt(time);
        write(user, id, manifest);
    }
}
