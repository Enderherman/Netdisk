package top.enderherman.netdisk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.component.RedisComponent;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.dto.FileListRequest;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.service.FileService;
import top.enderherman.netdisk.service.impl.FileOrganizationService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:file_organization_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FileOrganizationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired FileService files;
    @Autowired FileOrganizationService organization;
    @Autowired MockMvc mvc;
    @MockBean RedisComponent redisComponent;
    @MockBean JavaMailSender mailSender;

    @BeforeEach void seed() {
        clear();
        jdbc.update("insert into user_info(user_id,status,use_space,total_space) values ('alice',1,100,1000),('bob',1,100,1000)");
        folder("source", "alice", "0", "Source", 2);
        folder("nested", "alice", "source", "Nested", 2);
        folder("target", "alice", "0", "Target", 2);
        folder("recycled", "alice", "0", "Deleted", 1);
        folder("orphan", "alice", "recycled", "Stale child", 2);
        file("first", "alice", "source", "report.txt", 10);
        file("second", "alice", "nested", "report.txt", 20);
        file("plain", "alice", "0", "root.txt", 2);
        folder("foreign", "bob", "0", "Private", 2);
        file("secret", "bob", "foreign", "secret.txt", 100);
    }

    @AfterEach void clear() {
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
    }

    @Test void createFolderChecksLiveOwnedParentAndSetsMetadata() {
        FileInfo created = files.newFolder("nested", "alice", "中文资料");
        assertEquals("nested", created.getFilePid());
        assertEquals(1, created.getFolderType());
        assertEquals(2, created.getStatus());
        assertEquals(2, created.getDelFlag());
        assertNotNull(created.getCreateTime());
        assertEquals("中文资料", name(created.getFileId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "recycled", "orphan", "plain", "missing", "../bad"})
    void cannotCreateUnderForeignDeletedNonFolderMissingOrInvalidParent(String parent) {
        long before = count();
        assertThrows(BusinessException.class, () -> files.newFolder(parent, "alice", "child"));
        assertEquals(before, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../escape", "a/b", "a\\b", "bad\nname", "trailing.", " leading", "trailing ", "CON", "nul.txt", "bad:name"})
    void unsafeNamesCannotBeCreatedOrRenamed(String name) {
        assertThrows(BusinessException.class, () -> files.newFolder("0", "alice", name));
        assertThrows(BusinessException.class, () -> files.rename("first", "alice", name));
        assertEquals("report.txt", name("first"));
    }

    @Test void renameUsesCompleteNameAndPreservesContentMetadata() {
        FileInfo renamed = files.rename("first", "alice", "final.pdf");
        assertEquals("final.pdf", renamed.getFileName());
        assertEquals(7, renamed.getFileType());
        assertEquals("202610/first.txt", renamed.getFilePath());
        assertEquals("final.pdf", files.rename("first", "alice", "final.pdf").getFileName());
        assertEquals("FINAL.PDF", files.rename("first", "alice", "FINAL.PDF").getFileName());
    }

    @Test void namesAreUniqueAcrossFilesFoldersAndCase() {
        assertThrows(BusinessException.class, () -> files.newFolder("source", "alice", "REPORT.TXT"));
        assertThrows(BusinessException.class, () -> files.rename("plain", "alice", "target"));
        assertEquals("root.txt", name("plain"));
    }

    @Test void renameAndMoveCannotTouchForeignDeletedOrProcessingFiles() {
        assertThrows(BusinessException.class, () -> files.rename("secret", "alice", "taken.txt"));
        assertThrows(BusinessException.class, () -> files.rename("recycled", "alice", "restored"));
        jdbc.update("update file_info set status=0 where file_id='first' and user_id='alice'");
        assertThrows(BusinessException.class, () -> files.rename("first", "alice", "new.txt"));
        assertThrows(BusinessException.class, () -> files.changeFileFolder("first", "target", "alice"));
        assertEquals("source", parent("first"));
    }

    @Test void moveRejectsSelfDescendantAndNonFolderTargets() {
        assertThrows(BusinessException.class, () -> files.changeFileFolder("source", "nested", "alice"));
        assertThrows(BusinessException.class, () -> files.changeFileFolder("source,plain", "source", "alice"));
        assertThrows(BusinessException.class, () -> files.changeFileFolder("first", "plain", "alice"));
        assertThrows(BusinessException.class, () -> files.changeFileFolder("first", "foreign", "alice"));
        assertEquals("0", parent("source"));
    }

    @Test void malformedExistingCyclesAreRejectedByBothMutationsAndBrowsing() {
        jdbc.update("update file_info set file_pid='nested' where file_id='source' and user_id='alice'");
        assertThrows(BusinessException.class, () -> files.newFolder("source", "alice", "New"));
        assertThrows(BusinessException.class, () -> files.rename("first", "alice", "New.txt"));
        FileListRequest query = new FileListRequest();
        query.setFilePid("source");
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
    }

    @Test void movingParentAndDescendantKeepsTheirHierarchyAndSameParentIsNoop() {
        files.changeFileFolder("source,first,source", "target", "alice");
        assertEquals("target", parent("source"));
        assertEquals("source", parent("first"));
        files.changeFileFolder("source", "target", "alice");
        assertEquals("Source", name("source"));
    }

    @Test void duplicateNamesAreAllocatedUniquelyForTheWholeMoveBatch() {
        file("existing", "alice", "target", "REPORT.TXT", 1);
        files.changeFileFolder("first,second", "target", "alice");
        assertEquals("report (1).txt", name("first"));
        assertEquals("report (2).txt", name("second"));
        assertEquals("target", parent("first"));
        assertEquals("target", parent("second"));
    }

    @Test void invalidMemberDoesNotPartiallyMoveBatch() {
        assertThrows(BusinessException.class, () -> files.changeFileFolder("first,secret", "target", "alice"));
        assertEquals("source", parent("first"));
    }

    @Test void databaseFailureRollsBackEarlierMovesInTheBatch() {
        jdbc.execute("alter table file_info add constraint test_move_atomic check (file_id <> 'second' or file_pid <> 'target')");
        try {
            assertThrows(RuntimeException.class, () -> files.changeFileFolder("first,second", "target", "alice"));
            assertEquals("source", parent("first"));
            assertEquals("nested", parent("second"));
            assertEquals("report.txt", name("first"));
        } finally {
            jdbc.execute("alter table file_info drop constraint test_move_atomic");
        }
    }

    @Test void destinationBrowserExcludesEverySelectedFolderAndTheirDescendants() {
        assertEquals(List.of("target"), organization.folders("alice", "0", "source").stream().map(FileInfo::getFileId).toList());
        assertTrue(organization.folders("alice", "0", "source,target").isEmpty());
        assertTrue(organization.folders("alice", "source", "source").isEmpty());
        assertThrows(BusinessException.class, () -> organization.folders("alice", "0", "foreign"));
    }

    @Test void literalSearchEscapesPercentUnderscoreAndEscapeCharacter() {
        file("literal", "alice", "nested", "100%_!.txt", 1);
        file("wildcard", "alice", "0", "100abc.txt", 1);
        FileListRequest query = new FileListRequest();
        query.setFileNameFuzzy("%_!");
        var result = organization.list("alice", query, null);
        assertEquals(1, result.getTotalCount());
        assertEquals("literal", result.getList().get(0).getFileId());
    }

    @Test void categorySearchIncludesNestedFilesButExplicitDirectoryScopesIt() {
        FileListRequest query = new FileListRequest();
        assertEquals(3, organization.list("alice", query, "doc").getTotalCount());
        query.setFilePid("source");
        assertEquals(1, organization.list("alice", query, "doc").getTotalCount());
        query.setFilePid("foreign");
        assertThrows(BusinessException.class, () -> organization.list("alice", query, "doc"));
    }

    @Test void safeSortIsStableAndFoldersComeFirst() {
        file("large", "alice", "0", "Large.txt", 10);
        FileListRequest query = new FileListRequest();
        query.setSortField("fileSize");
        query.setSortDirection("desc");
        List<FileInfo> results = organization.list("alice", query, null).getList();
        assertEquals(1, results.get(0).getFolderType());
        assertEquals(List.of("large", "plain"), results.stream().filter(f -> f.getFolderType() == 0).map(FileInfo::getFileId).toList());
        query.setSortField("file_name;drop table file_info");
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
        query.setSortField("fileName");
        query.setSortDirection("desc;select 1");
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
    }

    @Test void paginationClampsOversizedPagesAndRejectsInvalidBoundaries() {
        for (int n = 0; n < 105; n++) file("p" + n, "alice", "0", "File " + n + ".txt", n);
        FileListRequest query = new FileListRequest();
        query.setFolderType(0);
        query.setPageSize(Integer.MAX_VALUE);
        assertEquals(100, organization.list("alice", query, null).getPageSize());
        assertEquals(100, organization.list("alice", query, null).getList().size());
        query.setPageNo(Integer.MAX_VALUE);
        assertEquals(2, organization.list("alice", query, null).getPageNo());
        assertEquals(6, organization.list("alice", query, null).getList().size());
        query.setPageNo(0);
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
        query.setPageNo(1);
        query.setPageSize(-1);
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
    }

    @Test void invalidFilterValuesCannotExpandOrCorruptTheQuery() {
        FileListRequest query = new FileListRequest();
        assertThrows(BusinessException.class, () -> organization.list("alice", query, "unknown"));
        query.setFolderType(9);
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
        query.setFolderType(null);
        query.setStatus(99);
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
        query.setStatus(null);
        query.setFileType(0);
        assertThrows(BusinessException.class, () -> organization.list("alice", query, null));
    }

    @Test void httpListCannotBindOwnerDeletionSqlOrPhysicalPathFields() throws Exception {
        mvc.perform(get("/file/loadDataList").session(session()).param("userId", "bob")
                        .param("delFlag", "1").param("orderBy", "file_name; drop table file_info")
                        .param("filePath", "private").param("queryNickName", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.list[0].filePath").doesNotExist());
        assertEquals(10, count());
    }

    @Test void bothFolderEndpointSpellingsAndCompleteNameRenameWork() throws Exception {
        mvc.perform(post("/file/newFolder").session(session()).param("filePid", "0").param("fileName", "New"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/file/newFoloder").session(session()).param("filePid", "0").param("fileName", "Legacy"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/file/rename").session(session()).param("fileId", "first").param("fileName", "FullName.txt"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.fileName").value("FullName.txt"));
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId("alice");
        user.setIsAdmin(false);
        session.setAttribute(Constants.SESSION_KEY, user);
        return session;
    }
    private String parent(String id) { return jdbc.queryForObject("select file_pid from file_info where file_id=? and user_id='alice'", String.class, id); }
    private String name(String id) { return jdbc.queryForObject("select file_name from file_info where file_id=? and user_id='alice'", String.class, id); }
    private long count() { return jdbc.queryForObject("select count(*) from file_info", Long.class); }
    private void folder(String id, String user, String parent, String name, int state) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag,last_update_time) values (?,?,?,?,1,2,?,current_timestamp)", id, user, parent, name, state);
    }
    private void file(String id, String user, String parent, String name, long size) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag,file_size,file_category,file_type,file_path,last_update_time) values (?,?,?,?,0,2,2,?,4,7,?,current_timestamp)", id, user, parent, name, size, "202610/" + id + ".txt");
    }
}
