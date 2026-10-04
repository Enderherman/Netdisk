package top.enderherman.netdisk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.service.impl.StorageCopyService;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageCopyIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StorageCopyService copy;

    @BeforeEach void seed() {
        clear();
        jdbc.update("insert into user_info(user_id,nick_name,email,status,use_space,total_space) values ('copyFrom','From','from@example.test',1,70,1000),('copyTo','To','to@example.test',1,0,100)");
        folder("shared", "0"); folder("nested", "shared");
        file("fileA", "shared", "报告.txt", 30, 2);
        file("fileB", "nested", "b.txt", 40, 2);
        file("recycled", "nested", "deleted.txt", 100, 1);
        file("private", "0", "secret.txt", 10, 2);
    }

    @AfterEach void clear() {
        jdbc.update("delete from file_info where user_id in ('copyFrom','copyTo')");
        jdbc.update("delete from user_info where user_id in ('copyFrom','copyTo')");
    }

    @Test void copiesFullTreeAndKeepsSourcesAndSpaceConsistent() {
        save("shared,nested,fileA", "0");
        assertEquals(4, targetCount());
        assertEquals(70L, used());
        assertEquals(0, jdbc.queryForObject("select count(*) from file_info where user_id='copyTo' and file_name='deleted.txt'", Integer.class));
        assertEquals("shared", jdbc.queryForObject("select file_pid from file_info where user_id='copyFrom' and file_id='fileA'", String.class));
        String root = jdbc.queryForObject("select file_id from file_info where user_id='copyTo' and file_name='shared'", String.class);
        assertEquals(2, jdbc.queryForObject("select count(*) from file_info where user_id='copyTo' and file_pid=?", Integer.class, root));
    }

    @Test void overQuotaLeavesNoFilesAndNoCharge() {
        jdbc.update("update user_info set total_space=69 where user_id='copyTo'");
        assertThrows(BusinessException.class, () -> save("shared", "0"));
        assertEquals(0, targetCount()); assertEquals(0L, used());
    }

    @Test void exactQuotaAndZeroByteFileAreAllowed() {
        jdbc.update("update user_info set total_space=70 where user_id='copyTo'");
        file("empty", "shared", "empty.txt", 0, 2);
        save("shared", "0");
        assertEquals(5, targetCount()); assertEquals(70L, used());
    }

    @Test void databaseFailureAfterCopyInsertionRollsBackEntireOperation() {
        jdbc.execute("alter table user_info add constraint copy_failure_test check (user_id <> 'copyTo' or use_space < 30)");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> save("fileA", "0"));
            assertEquals(0, targetCount()); assertEquals(0L, used());
        } finally {
            jdbc.execute("alter table user_info drop constraint copy_failure_test");
        }
    }

    @Test void rejectsOutOfShareMissingAndWrongDestination() {
        for (String selection : new String[]{"private", "missing", "fileA,private", "fileA,"}) {
            assertThrows(BusinessException.class, () -> save(selection, "0"));
        }
        assertThrows(BusinessException.class, () -> save("fileA", "shared"));
        assertEquals(0, targetCount()); assertEquals(0L, used());
    }

    @Test void duplicateRequestsProduceUniqueNamesAndChargeBoth() {
        save("fileA,fileA", "0");
        save("fileA", "0");
        assertEquals(2, targetCount()); assertEquals(60L, used());
        assertEquals(1, jdbc.queryForObject("select count(*) from file_info where user_id='copyTo' and file_name='报告 (1).txt'", Integer.class));
    }

    @Test void failedOrIncompleteFilesAndDisabledUsersAreRejected() {
        jdbc.update("update file_info set status=0 where file_id='fileA'");
        assertThrows(BusinessException.class, () -> save("shared", "0"));
        jdbc.update("update file_info set status=2 where file_id='fileA'");
        jdbc.update("update user_info set status=0 where user_id='copyTo'");
        assertThrows(BusinessException.class, () -> save("fileA", "0"));
        assertEquals(0, targetCount());
    }

    @Test void simultaneousCopiesCannotOverspendQuota() throws Exception {
        jdbc.update("update user_info set total_space=40 where user_id='copyTo'");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> { start.await(); try { save("fileA", "0"); return true; } catch (BusinessException ex) { return false; } };
        try {
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, targetCount()); assertEquals(30L, used());
        } finally { pool.shutdownNow(); }
    }

    private void save(String selection, String destination) { copy.saveShare("shared", selection, destination, "copyFrom", "copyTo"); }
    private int targetCount() { return jdbc.queryForObject("select count(*) from file_info where user_id='copyTo'", Integer.class); }
    private long used() { return jdbc.queryForObject("select use_space from user_info where user_id='copyTo'", Long.class); }
    private void folder(String id, String parent) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,'copyFrom',?,?,1,2,2)", id, parent, id);
    }
    private void file(String id, String parent, String name, long size, int state) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,file_size,file_path,folder_type,status,del_flag) values (?,'copyFrom',?,?,?,?,0,2,?)", id, parent, name, size, "202610/" + id + ".txt", state);
    }
}
