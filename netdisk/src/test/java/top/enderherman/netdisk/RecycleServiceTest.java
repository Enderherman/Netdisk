package top.enderherman.netdisk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.mapper.FileMapper;
import top.enderherman.netdisk.service.FileService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recycle_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RecycleServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FileService fileService;
    @Autowired private FileMapper<FileInfo, FileQuery> fileMapper;
    @MockBean private RedisComponent redisComponent;
    @MockBean private JavaMailSender mailSender;

    @BeforeEach void seed() {
        clear();
        jdbc.update("insert into user_info(user_id,use_space,total_space) values ('alice',100,1000),('bob',100,1000)");
        folder("root", "alice", "0", "资料", 2);
        folder("child", "alice", "root", "内层", 2);
        folder("deep", "alice", "child", "最内层", 2);
        file("leaf", "alice", "deep", "记录.txt", 60, 2);
        file("keep", "alice", "0", "保留.txt", 40, 2);
        folder("root", "bob", "0", "其他用户资料", 2);
        file("foreign", "bob", "root", "私人.txt", 100, 2);
    }

    @AfterEach void clear() {
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
    }

    @Test void recycleMarksEveryDescendantAndKeepsOtherUsersUnchanged() {
        fileService.removeFile2RecycleBatch("alice", "root");
        assertEquals(1, state("root", "alice"));
        for (String id : new String[]{"child", "deep", "leaf"}) assertEquals(0, state(id, "alice"));
        assertEquals(2, state("root", "bob"));
        assertEquals(2, state("foreign", "bob"));
        assertEquals(100L, space("alice")); // 回收站仍占空间。
    }

    @Test void selectingParentAndDescendantCreatesOnlyOneRecycleRoot() {
        fileService.removeFile2RecycleBatch("alice", "root,leaf,root");
        assertEquals(1, state("root", "alice"));
        assertEquals(0, state("leaf", "alice"));
    }

    @Test void recoverRestoresDeepHierarchyAndClearsRecoveryTime() {
        fileService.removeFile2RecycleBatch("alice", "root");
        folder("conflict", "alice", "0", "资料", 2);
        fileService.recoverFile("alice", "root");
        for (String id : new String[]{"root", "child", "deep", "leaf"}) {
            assertEquals(2, state(id, "alice"));
            assertNull(jdbc.queryForObject("select recovery_time from file_info where user_id='alice' and file_id=?", Object.class, id));
        }
        assertEquals("0", parent("root"));
        assertEquals("root", parent("child"));
        assertEquals("deep", parent("leaf"));
        assertEquals("资料 (1)", jdbc.queryForObject("select file_name from file_info where user_id='alice' and file_id='root'", String.class));
    }

    @Test void recoveringParentDoesNotRestoreAnIndependentlyRecycledChild() {
        fileService.removeFile2RecycleBatch("alice", "leaf");
        fileService.removeFile2RecycleBatch("alice", "root");
        fileService.recoverFile("alice", "root");
        assertEquals(2, state("deep", "alice"));
        assertEquals(1, state("leaf", "alice"));
    }

    @Test void permanentDeleteIncludesIndependentlyRecycledDescendantsAndReclaimsOnlyOwnerSpace() {
        fileService.removeFile2RecycleBatch("alice", "leaf");
        fileService.removeFile2RecycleBatch("alice", "root");
        fileService.deleteFile("alice", "root", false);
        for (String id : new String[]{"root", "child", "deep", "leaf"}) assertEquals(3, state(id, "alice"));
        assertEquals(40L, space("alice"));
        assertEquals(100L, space("bob"));
        assertEquals(2, state("foreign", "bob"));
        verify(redisComponent).resetUserSpaceUse("alice");
    }

    @Test void explicitlySelectedIndependentRecycleChildAlsoRecovers() {
        fileService.removeFile2RecycleBatch("alice", "leaf");
        fileService.removeFile2RecycleBatch("alice", "root");
        fileService.recoverFile("alice", "root,leaf");
        assertEquals(2, state("root", "alice"));
        assertEquals(2, state("leaf", "alice"));
        assertEquals("0", parent("leaf"));
    }

    @Test void administratorCanPermanentlyDeleteActiveTree() {
        fileService.deleteFile("alice", "root", true);
        assertEquals(3, state("leaf", "alice"));
        assertEquals(40L, space("alice"));
        assertEquals(2, state("root", "bob"));
    }

    @Test void ordinaryUserCannotPermanentlyDeleteActiveFiles() {
        assertThrows(BusinessException.class, () -> fileService.deleteFile("alice", "root", false));
        assertEquals(2, state("root", "alice"));
        assertEquals(100L, space("alice"));
    }

    @Test void mixedForeignSelectionFailsWithoutPartialChanges() {
        assertThrows(BusinessException.class, () -> fileService.removeFile2RecycleBatch("alice", "root,foreign"));
        assertEquals(2, state("root", "alice"));
        assertEquals(2, state("foreign", "bob"));
    }

    @Test void emptyIdsAreRejectedAndLegacyCyclesCannotHang() {
        assertThrows(BusinessException.class, () -> fileService.removeFile2RecycleBatch("alice", ","));
        jdbc.update("update file_info set file_pid='deep' where file_id='root' and user_id='alice'");
        assertThrows(BusinessException.class, () -> fileService.removeFile2RecycleBatch("alice", "root"));
        assertEquals(2, state("root", "alice"));
    }

    @Test void restoringMultipleSameNamesReservesEachNewRootName() {
        file("other", "alice", "child", "保留.txt", 1, 1);
        file("another", "alice", "deep", "保留.txt", 1, 1);
        fileService.recoverFile("alice", "other,another");
        assertEquals(3, jdbc.queryForObject("select count(distinct file_name) from file_info where user_id='alice' and file_pid='0' and folder_type=0", Integer.class));
    }

    @Test void cacheFailureDoesNotRollBackSuccessfulDatabaseDeletion() {
        doThrow(new IllegalStateException("test Redis unavailable")).when(redisComponent).resetUserSpaceUse("alice");
        assertDoesNotThrow(() -> fileService.deleteFile("alice", "root", true));
        assertEquals(3, state("leaf", "alice"));
        assertEquals(40L, space("alice"));
    }

    @Test void physicalReferenceMapperIncludesOtherUsersAndRecycleItems() {
        jdbc.update("update file_info set file_path='shared/data.txt' where file_id in ('leaf','foreign')");
        jdbc.update("update file_info set del_flag=1 where file_id='foreign'");
        assertEquals(2, fileMapper.selectStorageReferencesForUpdate("shared/data.txt").size());
    }

    private int state(String id, String user) {
        return jdbc.queryForObject("select del_flag from file_info where file_id=? and user_id=?", Integer.class, id, user);
    }
    private String parent(String id) {
        return jdbc.queryForObject("select file_pid from file_info where file_id=? and user_id='alice'", String.class, id);
    }
    private long space(String user) {
        return jdbc.queryForObject("select use_space from user_info where user_id=?", Long.class, user);
    }
    private void folder(String id, String user, String parent, String name, int state) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,?,?,?,1,2,?)", id, user, parent, name, state);
    }
    private void file(String id, String user, String parent, String name, long size, int state) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag,file_size) values (?,?,?,?,0,2,?,?)", id, user, parent, name, state, size);
    }
}
