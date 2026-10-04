package top.enderherman.netdisk;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.config.RecyclePolicySettings;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.impl.RecycleStorageService;
import top.enderherman.netdisk.task.RecycleRetentionTask;
import top.enderherman.netdisk.task.ScheduledTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recycle_policy_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecyclePolicyTest {
    @TempDir Path temporary;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileService files;
    @Autowired RecycleStorageService recycle;
    @Autowired FileMapper<FileInfo, FileQuery> mapper;
    @Autowired RecyclePolicySettings policy;
    @Autowired AppConfig config;
    @Autowired MockMvc mvc;
    @MockBean RedisComponent redis;
    @MockBean JavaMailSender mailSender;
    RecycleRetentionTask retention;
    static final Date NOW = Date.from(Instant.parse("2026-10-04T04:00:00Z"));
    static final Date CUTOFF = new Date(NOW.getTime() - 30L * 86_400_000L);

    @BeforeEach void seed() {
        clearData();
        jdbc.update("insert into user_info(user_id,status,use_space,total_space) values ('alice',1,100,1000),('bob',1,100,1000)");
        folder("outer", "alice", "0", "原目录", 2);
        folder("parent", "alice", "outer", "文件夹", 2);
        file("child", "alice", "parent", "report.txt", 60, 2, null);
        file("keep", "alice", "0", "keep.txt", 40, 2, null);
        file("foreign", "bob", "0", "private.txt", 100, 1, CUTOFF);
        config.setProjectFolder(temporary.toString());
        ReflectionTestUtils.setField(policy, "retentionDays", 30);
        // 调度 Bean 仍由 test profile 禁用；测试只显式调用独立任务实例。
        ReflectionTestUtils.setField(policy, "cleanupEnabled", true);
        retention = new RecycleRetentionTask();
        ReflectionTestUtils.setField(retention, "policy", policy);
        ReflectionTestUtils.setField(retention, "fileMapper", mapper);
        ReflectionTestUtils.setField(retention, "recycleStorage", recycle);
    }

    @AfterEach void clearData() { jdbc.update("delete from file_info"); jdbc.update("delete from user_info"); }

    @Test void restoreReturnsToOriginalLiveDirectory() {
        files.removeFile2RecycleBatch("alice", "child");
        files.recoverFile("alice", "child");
        assertEquals("parent", parent("child"));
        assertEquals(2, state("child", "alice"));
        assertEquals("report.txt", name("child"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted-ancestor", "non-folder"})
    void invalidOriginalParentChainFallsBackToRoot(String fault) {
        files.removeFile2RecycleBatch("alice", "child");
        if (fault.equals("missing")) jdbc.update("delete from file_info where file_id='parent' and user_id='alice'");
        if (fault.equals("deleted-ancestor")) jdbc.update("update file_info set del_flag=1 where file_id='outer' and user_id='alice'");
        if (fault.equals("non-folder")) jdbc.update("update file_info set folder_type=0 where file_id='parent' and user_id='alice'");
        files.recoverFile("alice", "child");
        assertEquals("0", parent("child"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"parent,child", "child,parent"})
    void selectedParentsAndChildrenRestoreTogetherRegardlessOfOrder(String selected) {
        files.removeFile2RecycleBatch("alice", "child");
        files.removeFile2RecycleBatch("alice", "parent");
        jdbc.update("delete from file_info where file_id='outer' and user_id='alice'");
        files.recoverFile("alice", selected);
        assertEquals("0", parent("parent"));
        assertEquals("parent", parent("child"));
        assertEquals(2, state("parent", "alice"));
        assertEquals(2, state("child", "alice"));
    }

    @Test void recoveringOnlyChildOfStillRecycledParentFallsBackToRoot() {
        files.removeFile2RecycleBatch("alice", "child");
        files.removeFile2RecycleBatch("alice", "parent");
        files.recoverFile("alice", "child");
        assertEquals("0", parent("child"));
        assertEquals(1, state("parent", "alice"));
    }

    @Test void originalDirectoryConflictsUseUnifiedCaseInsensitiveFileNames() {
        files.removeFile2RecycleBatch("alice", "child");
        file("conflict", "alice", "parent", "REPORT.TXT", 1, 2, null);
        file("conflict2", "alice", "parent", "report (1).txt", 1, 2, null);
        files.recoverFile("alice", "child");
        assertEquals("parent", parent("child"));
        assertEquals("report (2).txt", name("child"));
    }

    @Test void simultaneousFallbacksReserveNamesForTheWholeBatch() {
        file("first", "alice", "missingA", "keep.txt", 1, 1, CUTOFF);
        file("second", "alice", "missingB", "KEEP.TXT", 1, 1, CUTOFF);
        files.recoverFile("alice", "first,second");
        assertEquals("keep (1).txt", name("first"));
        assertEquals("KEEP (2).TXT", name("second"));
        assertEquals("0", parent("first"));
        assertEquals("0", parent("second"));
    }

    @Test void malformedSelectedCycleIsBrokenInsteadOfRestoredAsACycle() {
        folder("cycleA", "alice", "cycleB", "A", 1);
        folder("cycleB", "alice", "cycleA", "B", 1);
        files.recoverFile("alice", "cycleA,cycleB");
        assertEquals("0", parent("cycleA"));
        assertEquals("cycleA", parent("cycleB"));
    }

    @Test void clearIsIdempotentAndOnlyReleasesCurrentUsersTrashQuota() {
        assertEquals(0, recycle.clear("alice"));
        files.removeFile2RecycleBatch("alice", "parent");
        assertEquals(2, recycle.clear("alice"));
        assertEquals(0, recycle.clear("alice"));
        assertEquals(3, state("parent", "alice"));
        assertEquals(3, state("child", "alice"));
        assertEquals(2, state("keep", "alice"));
        assertEquals(1, state("foreign", "bob"));
        assertEquals(40L, space("alice"));
        assertEquals(100L, space("bob"));
    }

    @Test void expirationIncludesExactBoundaryButLeavesRecentNullDateAndActiveNodes() {
        file("old", "alice", "0", "old.txt", 1, 1, new Date(CUTOFF.getTime() - 1));
        file("boundary", "alice", "0", "boundary.txt", 1, 1, CUTOFF);
        file("recent", "alice", "0", "recent.txt", 1, 1, new Date(CUTOFF.getTime() + 1));
        file("unknown", "alice", "0", "unknown.txt", 1, 1, null);
        file("active", "alice", "0", "active.txt", 1, 2, CUTOFF);
        assertEquals(2, recycle.expireUserTrash("alice", CUTOFF));
        assertEquals(3, state("old", "alice"));
        assertEquals(3, state("boundary", "alice"));
        assertEquals(1, state("recent", "alice"));
        assertEquals(1, state("unknown", "alice"));
        assertEquals(2, state("active", "alice"));
        assertEquals(1, state("foreign", "bob"));
    }

    @Test void clearHandlesMoreThanOneDatabaseBatchWithoutLosingDescendants() {
        folder("bulkdir", "alice", "0", "Bulk", 1);
        var rows = java.util.stream.IntStream.range(0, 600)
                .mapToObj(index -> new Object[]{"bulk" + index, "item " + index + ".txt"}).toList();
        jdbc.batchUpdate("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,del_flag,status,file_size) values (?,'alice','bulkdir',?,0,0,2,1)", rows);
        jdbc.update("update user_info set use_space=700 where user_id='alice'");
        assertEquals(601, recycle.clear("alice"));
        assertEquals(601, jdbc.queryForObject("select count(*) from file_info where user_id='alice' and file_id like 'bulk%' and del_flag=3", Integer.class));
        assertEquals(100L, space("alice"));
        assertEquals(1, state("foreign", "bob"));
    }

    @Test void staleActiveChildrenAreNeverDeletedByClearOrExpiry() {
        jdbc.update("update file_info set del_flag=1,recovery_time=? where file_id='parent' and user_id='alice'", CUTOFF);
        assertEquals(1, recycle.expireUserTrash("alice", CUTOFF));
        assertEquals(2, state("child", "alice"));
        assertEquals(100L, space("alice"));
    }

    @Test void zeroRetentionOrGlobalDisablePreventsAutomaticExpiration() {
        ReflectionTestUtils.setField(policy, "retentionDays", 0);
        assertFalse(policy.policy().autoCleanupEnabled());
        assertEquals(0, retention.expireAt(NOW));
        assertEquals(1, state("foreign", "bob"));
        ReflectionTestUtils.setField(policy, "retentionDays", 30);
        ReflectionTestUtils.setField(policy, "cleanupEnabled", false);
        assertEquals(0, retention.expireAt(NOW));
        assertEquals(0, recycle.expireUserTrash("bob", CUTOFF));
    }

    @Test void expirationUsesScopedUserTransactionsAndCanReclaimDisabledUsersTrash() {
        file("old", "alice", "0", "old.txt", 1, 1, CUTOFF);
        file("old", "bob", "0", "same id recent.txt", 1, 1, new Date(CUTOFF.getTime() + 1));
        jdbc.update("update user_info set status=0 where user_id='alice'");
        assertEquals(2, retention.expireAt(NOW)); // Alice 到期项 + Bob 原有到期项。
        assertEquals(3, state("old", "alice"));
        assertEquals(1, state("old", "bob"));
        assertThrows(BusinessException.class, () -> recycle.clear("alice"));
    }

    @Test void clearingSharedFileKeepsOtherUsersPhysicalContentDuringSafeGc() throws Exception {
        Path content = temporary.resolve("file/shared/content.bin");
        Files.createDirectories(content.getParent());
        Files.writeString(content, "shared original");
        jdbc.update("update file_info set file_path='shared/content.bin',file_type=10 where file_id in ('child','foreign')");
        jdbc.update("update file_info set del_flag=2 where user_id='bob' and file_id='foreign'");
        files.removeFile2RecycleBatch("alice", "child");
        assertEquals(1, recycle.clear("alice"));
        ScheduledTask gc = new ScheduledTask();
        ReflectionTestUtils.setField(gc, "fileMapper", mapper);
        ReflectionTestUtils.setField(gc, "appConfig", config);
        gc.autoDeleteFile();
        assertEquals("shared original", Files.readString(content));
        assertEquals(2, state("foreign", "bob"));
        assertNull(files.getFileInfoByFileIdAndUserId("child", "alice"));
    }

    @Test void policyAndClearHttpContractsAreAuthenticated() throws Exception {
        mvc.perform(get("/recycle/policy").session(session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retentionDays").value(30))
                .andExpect(jsonPath("$.data.autoCleanupEnabled").value(true))
                .andExpect(jsonPath("$.data.restoreStrategy").value("original_or_root"));
        mvc.perform(post("/recycle/clear").session(session())).andExpect(jsonPath("$.data.deletedCount").value(0));
        mvc.perform(get("/recycle/policy")).andExpect(jsonPath("$.code").value(901));
        mvc.perform(post("/recycle/clear")).andExpect(jsonPath("$.code").value(901));
    }

    private MockHttpSession session() { var session = new MockHttpSession(); var user = new SessionWebUserDto(); user.setUserId("alice"); user.setIsAdmin(false); session.setAttribute(Constants.SESSION_KEY, user); return session; }
    private String parent(String id) { return jdbc.queryForObject("select file_pid from file_info where file_id=? and user_id='alice'", String.class, id); }
    private String name(String id) { return jdbc.queryForObject("select file_name from file_info where file_id=? and user_id='alice'", String.class, id); }
    private int state(String id, String user) { return jdbc.queryForObject("select del_flag from file_info where file_id=? and user_id=?", Integer.class, id, user); }
    private long space(String user) { return jdbc.queryForObject("select use_space from user_info where user_id=?", Long.class, user); }
    private void folder(String id, String user, String parent, String name, int state) { jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,del_flag,status) values (?,?,?,?,1,?,2)", id, user, parent, name, state); }
    private void file(String id, String user, String parent, String name, long size, int state, Date recovery) { jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,del_flag,status,file_size,recovery_time) values (?,?,?,?,0,?,2,?,?)", id, user, parent, name, state, size, recovery); }
}
