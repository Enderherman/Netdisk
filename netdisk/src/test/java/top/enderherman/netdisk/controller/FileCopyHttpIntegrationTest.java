package top.enderherman.netdisk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.service.impl.FileUploadService;
import top.enderherman.netdisk.service.impl.StorageCopyService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:own-copy-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FileCopyHttpIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private StorageCopyService copies;
    @Autowired private FileUploadService uploads;
    @Autowired private AppConfig config;
    @Autowired private FileMapper<FileInfo, FileQuery> files;
    @Autowired private PlatformTransactionManager transactions;
    @TempDir Path storage;
    private MockHttpSession owner;

    @BeforeEach
    void seed() {
        config.setProjectFolder(storage.toString());
        jdbc.update("delete from file_share");
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
        jdbc.update("insert into user_info(user_id,nick_name,email,status,use_space,total_space) values ('copyOwner','Owner','own-copy@example.test',1,180,1000),('copyGuest','Guest','guest-copy@example.test',1,5,1000)");
        folder("tree", "0", "Project", "copyOwner");
        folder("nested", "tree", "Nested", "copyOwner");
        folder("target", "0", "Target", "copyOwner");
        file("report", "tree", "report.txt", 30, 2, "copyOwner");
        file("deep", "nested", "deep.txt", 40, 2, "copyOwner");
        file("peer", "0", "peer.txt", 10, 2, "copyOwner");
        file("trash", "nested", "old.txt", 100, 1, "copyOwner");
        folder("foreignDir", "0", "Foreign", "copyGuest");
        file("foreign", "foreignDir", "foreign.txt", 5, 2, "copyGuest");
        owner = session("copyOwner");
    }

    @Test
    void copiesCompleteTreeOnceAndLeavesEverySourceUnchanged() throws Exception {
        List<Map<String, Object>> originals = jdbc.queryForList("select * from file_info where user_id='copyOwner' order by file_id");
        String body = mvc.perform(post("/file/copyFile").session(owner).param("fileIds", "tree,nested,report,report")
                        .param("filePid", "target").param("userId", "copyGuest"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("Project"))
                .andExpect(jsonPath("$.data[0].filePath").doesNotExist()).andReturn().getResponse().getContentAsString();
        String root = json.readTree(body).path("data").get(0).path("fileId").asText();
        assertNotEquals("tree", root);
        assertEquals(11, count());
        assertEquals(250L, used());
        assertEquals(2, jdbc.queryForObject("select count(*) from file_info where user_id='copyOwner' and file_pid=?", Integer.class, root));
        for (Map<String, Object> original : originals) {
            assertEquals(original, jdbc.queryForMap("select * from file_info where user_id='copyOwner' and file_id=?", original.get("file_id")));
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from file_info where user_id='copyOwner' and file_name='old.txt'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from file_info where user_id='copyOwner' and file_path='202610/report.txt'", Integer.class));
    }

    @Test
    void deeplyNestedTreeIsCopiedWithoutFlattening() {
        String parent = "nested";
        for (int n = 0; n < 12; n++) {
            String id = "level" + n;
            folder(id, parent, "Level " + n, "copyOwner");
            parent = id;
        }
        jdbc.update("update file_info set file_pid=? where user_id='copyOwner' and file_id='deep'", parent);
        int originalCount = count();
        var result = copies.copyOwn("copyOwner", "tree", "target");
        assertEquals(1, result.size());
        assertEquals(originalCount + 16, count());
        String copiedDeep = jdbc.queryForObject("select file_pid from file_info where user_id='copyOwner' and file_id<>'deep' and file_name='deep.txt'", String.class);
        int depth = 0;
        while (!result.get(0).getFileId().equals(copiedDeep)) {
            copiedDeep = jdbc.queryForObject("select file_pid from file_info where user_id='copyOwner' and file_id=?", String.class, copiedDeep);
            depth++;
        }
        assertEquals(13, depth);
    }

    @Test
    void copyingToSameFolderAndAccentedCaseCollisionsUsesCanonicalNames() {
        jdbc.update("update file_info set file_name='RESUME.TXT' where file_id='report' and user_id='copyOwner'");
        jdbc.update("update file_info set file_name='resume.txt' where file_id='deep' and user_id='copyOwner'");
        file("occupied", "target", "résumé.txt", 1, 2, "copyOwner");
        copies.copyOwn("copyOwner", "report,deep", "target");
        List<String> names = jdbc.queryForList("select file_name from file_info where user_id='copyOwner' and file_pid='target'", String.class);
        assertTrue(names.contains("RESUME (1).TXT"));
        assertTrue(names.contains("resume (2).txt"));
        assertEquals(3, names.stream().map(FileNames::key).distinct().count());
        copies.copyOwn("copyOwner", "peer", "0");
        assertEquals(1, jdbc.queryForObject("select count(*) from file_info where user_id='copyOwner' and file_name='peer (1).txt'", Integer.class));
    }

    @Test
    void legacyCaseDuplicatesInsideCopiedFolderAreAlsoResolved() {
        file("variant", "tree", "RÉPORT.TXT", 20, 2, "copyOwner");
        String root = copies.copyOwn("copyOwner", "tree", "target").get(0).getFileId();
        List<String> names = jdbc.queryForList("select file_name from file_info where user_id='copyOwner' and file_pid=?", String.class, root);
        assertTrue(names.contains("RÉPORT (1).TXT"));
        assertEquals(names.size(), names.stream().map(FileNames::key).distinct().count());
    }

    @Test
    void rejectsForeignDeletedUnfinishedAndBadTargetsWithoutPartialCopies() throws Exception {
        for (String selection : new String[]{"foreign", "report,foreign", "trash", "missing", "report,", "../report"}) {
            mvc.perform(post("/file/copyFile").session(owner).param("fileIds", selection).param("filePid", "target"))
                    .andExpect(jsonPath("$.code").value(600));
        }
        for (String target : new String[]{"foreignDir", "report", "missing"}) {
            assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "report", target));
        }
        jdbc.update("update file_info set status=0 where user_id='copyOwner' and file_id='deep'");
        assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "tree", "target"));
        assertEquals(7, count());
        assertEquals(180L, used());
    }

    @Test
    void refusesCopyToSelfDescendantAndBrokenSourceAncestorChain() {
        for (String destination : new String[]{"tree", "nested"}) {
            assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "tree,report", destination));
        }
        jdbc.update("update file_info set del_flag=1 where user_id='copyOwner' and file_id='tree'");
        assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "report", "target"));
        jdbc.update("update file_info set del_flag=2,file_pid='nested' where user_id='copyOwner' and file_id='tree'");
        assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "tree,nested", "target"));
        assertEquals(7, count());
    }

    @Test
    void overQuotaAndLateDatabaseFailureLeaveZeroResidualCopies() throws Exception {
        jdbc.update("update user_info set total_space=249 where user_id='copyOwner'");
        mvc.perform(post("/file/copyFile").session(owner).param("fileIds", "report,deep").param("filePid", "target"))
                .andExpect(jsonPath("$.code").value(904));
        assertEquals(7, count()); assertEquals(180L, used());
        jdbc.update("update user_info set total_space=1000 where user_id='copyOwner'");
        jdbc.execute("alter table user_info add constraint own_copy_failure check (user_id <> 'copyOwner' or use_space <= 180)");
        try {
            assertThrows(DataAccessException.class, () -> copies.copyOwn("copyOwner", "report,deep", "target"));
            assertEquals(7, count()); assertEquals(180L, used());
        } finally { jdbc.execute("alter table user_info drop constraint own_copy_failure"); }
    }

    @Test
    void zeroByteFileCanBeCopiedAtExactQuotaWithoutChangingUsage() throws Exception {
        file("empty", "tree", "empty.txt", 0, 2, "copyOwner");
        jdbc.update("update user_info set total_space=180 where user_id='copyOwner'");
        mvc.perform(post("/file/copyFile").session(owner).param("fileIds", "empty").param("filePid", "target"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].fileSize").value(0));
        assertEquals(9, count());
        assertEquals(180L, used());
    }

    @Test
    void copyQuotaIncludesRealPendingUploadAndExcludesCompletedReceipt() {
        jdbc.update("update user_info set total_space=250 where user_id='copyOwner'");
        SessionWebUserDto user = (SessionWebUserDto) owner.getAttribute(Constants.SESSION_KEY);
        String md5 = DigestUtils.md5Hex(new byte[51]);
        var upload = uploads.upload(user, null, new MockMultipartFile("file", new byte[50]), "pending.bin", "0", md5, 0, 2);
        BusinessException failure = assertThrows(BusinessException.class, () -> copies.copyOwn("copyOwner", "report", "target"));
        assertEquals(904, failure.getCode());
        assertEquals(7, count()); assertEquals(180L, used());
        uploads.upload(user, upload.getFileId(), new MockMultipartFile("file", new byte[1]), "pending.bin", "0", md5, 1, 2);
        jdbc.update("update user_info set total_space=300 where user_id='copyOwner'");
        copies.copyOwn("copyOwner", "report", "target");
        assertEquals(261L, used());
    }

    @Test
    void simultaneousCopiesCannotOverspendOrLoseCharges() throws Exception {
        jdbc.update("update user_info set total_space=210 where user_id='copyOwner'");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try { copies.copyOwn("copyOwner", "report", "target"); return true; }
            catch (BusinessException exception) { assertEquals(904, exception.getCode()); return false; }
        };
        try {
            Future<Boolean> first = pool.submit(attempt), second = pool.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(8, count()); assertEquals(210L, used());
    }

    @Test
    void folderSourceEntityLockIsRequiredBeforeCopiesCanCommit() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<List<FileInfo>>> attempt = new AtomicReference<>();
        try {
            new TransactionTemplate(transactions).execute(status -> {
                files.selectFilesForUpdate("copyOwner", List.of("tree"));
                attempt.set(worker.submit(() -> copies.copyOwn("copyOwner", "tree", "target")));
                assertThrows(TimeoutException.class, () -> attempt.get().get(300, TimeUnit.MILLISECONDS));
                assertEquals(7, count());
                return null;
            });
            assertEquals(1, attempt.get().get(10, TimeUnit.SECONDS).size());
            assertEquals(11, count());
            assertEquals(250L, used());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void existingShareTransferStillUsesPublicContractAndCanonicalNames() throws Exception {
        folder("existing", "0", "PROJECT", "copyGuest");
        jdbc.update("insert into file_share(share_id,file_id,user_id,valid_type,code,share_time) values ('shareTree','tree','copyOwner',3,'abcd',current_timestamp)");
        MockHttpSession guest = session("copyGuest");
        mvc.perform(post("/showShare/checkShareCode").session(guest).param("shareId", "shareTree").param("code", "abcd"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/showShare/saveShare").session(guest).param("shareId", "shareTree").param("shareFileIds", "tree,nested")
                        .param("myFolderId", "0"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1, jdbc.queryForObject("select count(*) from file_info where user_id='copyGuest' and file_name='Project (1)'", Integer.class));
        assertEquals(75L, jdbc.queryForObject("select use_space from user_info where user_id='copyGuest'", Long.class));
    }

    @Test
    void endpointRequiresPostLoginAndTrustedBrowserOrigin() throws Exception {
        mvc.perform(get("/file/copyFile").session(owner)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/file/copyFile").param("fileIds", "report").param("filePid", "target"))
                .andExpect(jsonPath("$.code").value(901));
        mvc.perform(post("/file/copyFile").session(owner).header("Origin", "https://evil.test")
                        .param("fileIds", "report").param("filePid", "target"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/file/copyFile").session(owner).param("fileIds", "report,".repeat(1000) + "report").param("filePid", "target"))
                .andExpect(jsonPath("$.code").value(600));
        assertEquals(7, count());
    }

    private void folder(String id, String parent, String name, String user) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,?,?,?,1,2,2)", id, user, parent, name);
    }
    private void file(String id, String parent, String name, long size, int state, String user) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,file_size,file_path,folder_type,status,del_flag) values (?,?,?,?,?,?,0,2,?)", id, user, parent, name, size, "202610/" + id + ".txt", state);
    }
    private MockHttpSession session(String id) {
        MockHttpSession session = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto(); user.setUserId(id); user.setIsAdmin(false);
        session.setAttribute(Constants.SESSION_KEY, user);
        return session;
    }
    private int count() { return jdbc.queryForObject("select count(*) from file_info where user_id='copyOwner'", Integer.class); }
    private long used() { return jdbc.queryForObject("select use_space from user_info where user_id='copyOwner'", Long.class); }
}
