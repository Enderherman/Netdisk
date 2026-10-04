package top.enderherman.netdisk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.SessionShareDto;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 MVC、AOP、MyBatis/H2 和临时磁盘内容联合验证授权。
 * 仅 Redis 和外部邮件发送器使用替身；下载码由真实接口签发后在内存缓存中回读。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FileContentHttpIntegrationTest {
    private static final String OWNER = "ctOwner";
    private static final String OTHER = "ctOther";
    private static final String ADMIN = "ctAdmin";
    private static final String SHARE_ID = "contentShare";
    private static final byte[] OWN_BYTES = "属于原用户的真实文件\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_BYTES = "另一个用户的同 ID 文件\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHARED_BYTES = "分享目录中的内容\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECRET_BYTES = "不能从分享读取的私有文件\n".getBytes(StandardCharsets.UTF_8);
    private static final Path STORAGE = createStorage();
    private static final String DATABASE = "jdbc:h2:mem:content-http-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    private final Map<String, Object> cache = new HashMap<>();

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE);
        properties.add("project.folder", () -> STORAGE.toString() + File.separator);
        properties.add("admin.email", () -> "admin@example.invalid");
    }

    @BeforeEach
    void seed() throws Exception {
        clearDatabase();
        cache.clear();
        when(redisUtils.get(anyString())).thenAnswer(invocation -> cache.get(invocation.getArgument(0, String.class)));
        when(redisUtils.setEx(anyString(), any(), anyLong())).thenAnswer(invocation -> {
            cache.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return true;
        });
        when(redisUtils.set(anyString(), any())).thenAnswer(invocation -> {
            cache.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return true;
        });

        jdbc.update("insert into user_info(user_id,nick_name,email,status,session_version,use_space,total_space) "
                        + "values (?,?,?,1,0,0,1048576)", OWNER, "Content Owner", "content-owner@example.invalid");
        jdbc.update("insert into user_info(user_id,nick_name,email,status,session_version,use_space,total_space) "
                        + "values (?,?,?,1,0,0,1048576)", OTHER, "Content Other", "content-other@example.invalid");
        jdbc.update("insert into user_info(user_id,nick_name,email,status,session_version,use_space,total_space) "
                        + "values (?,?,?,1,0,0,1048576)", ADMIN, "Content Admin", "admin@example.invalid");

        // 相同 file_id 的不同归属记录，能检测遗漏 user_id 查询条件的问题。
        file("owned", OWNER, "0", "中文 报告.txt", "202610/owner.txt", OWN_BYTES);
        file("owned", OTHER, "0", "other.txt", "202610/other.txt", OTHER_BYTES);
        folder("shareRoot", "0");
        folder("nested", "shareRoot");
        file("shared", OWNER, "nested", "shared.txt", "202610/shared.txt", SHARED_BYTES);
        file("secret", OWNER, "0", "secret.txt", "202610/secret.txt", SECRET_BYTES);
        jdbc.update("insert into file_share(share_id,file_id,user_id,valid_type,expire_time,share_time,code,show_count) "
                        + "values (?,?,?,1,?,?,?,0)", SHARE_ID, "shareRoot", OWNER,
                Timestamp.from(Instant.now().plusSeconds(3600)), Timestamp.from(Instant.now()), "A123");
    }

    @AfterEach
    void clearDatabase() throws Exception {
        // 每个模拟 HTTP 请求使用自己的事务，避免测试事务中的 MyBatis 一级缓存掩盖撤销变化。
        // 清理只能进入本类专属内存库，绝不作用于配置错误时指向的开发或生产数据库。
        assertNotNull(jdbc.getDataSource());
        try (var connection = jdbc.getDataSource().getConnection()) {
            String actualUrl = connection.getMetaData().getURL();
            assertTrue(actualUrl.startsWith("jdbc:h2:mem:"), "内容测试仅允许使用 H2 内存数据库");
            assertEquals(DATABASE.substring(0, DATABASE.indexOf(';')), actualUrl,
                    "内容测试清理必须限定于本类生成的独立数据库");
        }
        jdbc.update("delete from file_share where share_id=? or user_id in (?,?,?)", SHARE_ID, OWNER, OTHER, ADMIN);
        jdbc.update("delete from file_info where user_id in (?,?,?)", OWNER, OTHER, ADMIN);
        jdbc.update("delete from user_info where user_id in (?,?,?)", OWNER, OTHER, ADMIN);
    }

    @AfterAll
    static void removeOnlyCreatedStorage() throws IOException {
        if (!Files.exists(STORAGE)) return;
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void ownContentIsScopedByAuthenticatedUserAndReturnsActualBytes() throws Exception {
        mvc.perform(get("/file/content/owned").session(authenticated(OWNER)))
                .andExpect(status().isOk()).andExpect(content().bytes(OWN_BYTES))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/file/content/owned").session(authenticated(OTHER)))
                .andExpect(status().isOk()).andExpect(content().bytes(OTHER_BYTES));
        rejected(mvc.perform(get("/file/content/secret").session(authenticated(OTHER))));
        mvc.perform(get("/file/content/owned")).andExpect(jsonPath("$.code").value(901));
    }

    @Test
    void adminAuthorityComesFromCurrentDatabaseEmailRatherThanSessionFlag() throws Exception {
        MockHttpSession forged = authenticated(OTHER);
        ((SessionWebUserDto) forged.getAttribute(Constants.SESSION_KEY)).setIsAdmin(true);
        mvc.perform(get("/admin/content/{userId}/owned", OWNER).session(forged))
                .andExpect(jsonPath("$.code").value(404));

        MockHttpSession admin = authenticated(ADMIN); // 初始 isAdmin=false，由真实 AOP 按邮箱授权。
        mvc.perform(get("/admin/content/{userId}/owned", OWNER).session(admin))
                .andExpect(status().isOk()).andExpect(content().bytes(OWN_BYTES));
        jdbc.update("update user_info set email='former-admin@example.invalid' where user_id=?", ADMIN);
        mvc.perform(get("/admin/content/{userId}/owned", OWNER).session(admin))
                .andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/admin/content/{userId}/owned", OWNER))
                .andExpect(jsonPath("$.code").value(901));
    }

    @Test
    void changedSessionVersionOrDisabledAccountCannotUseAnOldOwnContentSession() throws Exception {
        MockHttpSession oldSession = authenticated(OWNER);
        jdbc.update("update user_info set session_version=1 where user_id=?", OWNER);
        mvc.perform(get("/file/content/owned").session(oldSession)).andExpect(jsonPath("$.code").value(901));
        jdbc.update("update user_info set session_version=0,status=0 where user_id=?", OWNER);
        mvc.perform(get("/file/content/owned").session(authenticated(OWNER))).andExpect(jsonPath("$.code").value(901));
    }

    @Test
    void foldersRecycledFilesAndUnfinishedFilesNeverReturnTheirDiskBytes() throws Exception {
        rejected(mvc.perform(get("/file/content/shareRoot").session(authenticated(OWNER))));
        for (int deletion : new int[]{0, 1, 3}) {
            jdbc.update("update file_info set del_flag=? where user_id=? and file_id='owned'", deletion, OWNER);
            rejected(mvc.perform(get("/file/content/owned").session(authenticated(OWNER))));
        }
        jdbc.update("update file_info set del_flag=2 where user_id=? and file_id='owned'", OWNER);
        for (int processing : new int[]{0, 1}) {
            jdbc.update("update file_info set status=? where user_id=? and file_id='owned'", processing, OWNER);
            rejected(mvc.perform(get("/file/content/owned").session(authenticated(OWNER))));
        }
    }

    @Test
    void correctShareCodeGrantsOnlyCurrentShareDescendantsWithoutAccountLogin() throws Exception {
        mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID))
                .andExpect(jsonPath("$.code").value(903));
        MockHttpSession extracted = extractShare();
        mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted))
                .andExpect(status().isOk()).andExpect(content().bytes(SHARED_BYTES));
        rejected(mvc.perform(get("/showShare/content/{shareId}/secret", SHARE_ID).session(extracted)));
        assertEquals(1, jdbc.queryForObject("select show_count from file_share where share_id=?", Integer.class, SHARE_ID));
    }

    @Test
    void revokedShareRejectsAnAlreadyExtractedSessionAndItsDownloadCodeOnEveryDownloadRoute() throws Exception {
        MockHttpSession extracted = extractShare();
        String code = issueSharedDownload(extracted);
        jdbc.update("delete from file_share where share_id=?", SHARE_ID);
        rejected(mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted)));
        for (String route : new String[]{"/file/download/", "/showShare/download/", "/admin/download/"}) {
            rejected(mvc.perform(get(route + code)));
        }
    }

    @Test
    void expiredShareRejectsItsPreviouslyExtractedSessionAndPreviouslyIssuedCode() throws Exception {
        MockHttpSession extracted = extractShare();
        String code = issueSharedDownload(extracted);
        jdbc.update("update file_share set expire_time=? where share_id=?", Timestamp.from(Instant.now().minusSeconds(60)), SHARE_ID);
        rejected(mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted)));
        rejected(mvc.perform(get("/showShare/download/{code}", code)));
    }

    @Test
    void movingAFileOutsideTheShareTreeRevokesOldPreviewAndDownloadAuthority() throws Exception {
        MockHttpSession extracted = extractShare();
        String code = issueSharedDownload(extracted);
        jdbc.update("update file_info set file_pid='0' where user_id=? and file_id='shared'", OWNER);
        rejected(mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted)));
        rejected(mvc.perform(get("/file/download/{code}", code)));
        // 文件本身仍存在，所有者仍可读取，失败确由分享树授权变化引起。
        mvc.perform(get("/file/content/shared").session(authenticated(OWNER)))
                .andExpect(status().isOk()).andExpect(content().bytes(SHARED_BYTES));
    }

    @Test
    void changedShareRootAndForgedExtractionIdentityAreRejected() throws Exception {
        MockHttpSession extracted = extractShare();
        SessionShareDto stored = (SessionShareDto) extracted.getAttribute(Constants.SESSION_SHARE_KEY + SHARE_ID);
        assertNotNull(stored);
        stored.setShareUserId(OTHER);
        mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted))
                .andExpect(jsonPath("$.code").value(903));
        stored.setShareUserId(OWNER);
        jdbc.update("update file_share set file_id='secret' where share_id=?", SHARE_ID);
        mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted))
                .andExpect(jsonPath("$.code").value(903));
    }

    @Test
    void ownDownloadCodeReadsBytesButStopsWorkingAsSoonAsFileIsRecycled() throws Exception {
        String code = issueOwnDownload();
        mvc.perform(get("/file/download/{code}", code)).andExpect(status().isOk()).andExpect(content().bytes(OWN_BYTES));
        jdbc.update("update file_info set del_flag=1 where user_id=? and file_id='owned'", OWNER);
        rejected(mvc.perform(get("/file/download/{code}", code)));
    }

    @Test
    void changedPhysicalPathCannotRepurposeAnAlreadyIssuedDownloadCode() throws Exception {
        String code = issueOwnDownload();
        jdbc.update("update file_info set file_path='202610/secret.txt' where user_id=? and file_id='owned'", OWNER);
        rejected(mvc.perform(get("/file/download/{code}", code)));
        mvc.perform(get("/file/content/owned").session(authenticated(OWNER)))
                .andExpect(status().isOk()).andExpect(content().bytes(SECRET_BYTES));
    }

    @Test
    void disablingOwnerRevokesExistingPrivateAndSharedDownloadCodesAndShareSessions() throws Exception {
        String privateCode = issueOwnDownload();
        MockHttpSession extracted = extractShare();
        String sharedCode = issueSharedDownload(extracted);
        jdbc.update("update user_info set status=0,session_version=session_version+1 where user_id=?", OWNER);
        rejected(mvc.perform(get("/file/download/{code}", privateCode)));
        rejected(mvc.perform(get("/showShare/download/{code}", sharedCode)));
        rejected(mvc.perform(get("/showShare/content/{shareId}/shared", SHARE_ID).session(extracted)));
    }

    @Test
    void missingCacheCodeAndRemovedDatabaseFileNeverProduceContent() throws Exception {
        rejected(mvc.perform(get("/file/download/not-issued")));
        String code = issueOwnDownload();
        jdbc.update("delete from file_info where user_id=? and file_id='owned'", OWNER);
        rejected(mvc.perform(get("/file/download/{code}", code)));
    }

    private String issueOwnDownload() throws Exception {
        var result = mvc.perform(post("/file/createDownloadUrl/owned").session(authenticated(OWNER)))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return issuedCode(result.getResponse().getContentAsString());
    }

    private String issueSharedDownload(MockHttpSession extracted) throws Exception {
        var result = mvc.perform(post("/showShare/createDownloadUrl/{shareId}/shared", SHARE_ID).session(extracted))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        String code = issuedCode(result.getResponse().getContentAsString());
        mvc.perform(get("/showShare/download/{code}", code)).andExpect(status().isOk()).andExpect(content().bytes(SHARED_BYTES));
        return code;
    }

    private String issuedCode(String body) throws Exception {
        String code = json.readTree(body).path("data").asText();
        assertEquals(50, code.length());
        assertTrue(cache.containsKey(Constants.REDIS_KEY_DOWNLOAD + code));
        return code;
    }

    private MockHttpSession extractShare() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/showShare/checkShareCode").session(session).param("shareId", SHARE_ID).param("code", "A123"))
                .andExpect(jsonPath("$.code").value(200));
        assertNotNull(session.getAttribute(Constants.SESSION_SHARE_KEY + SHARE_ID));
        return session;
    }

    private MockHttpSession authenticated(String userId) {
        MockHttpSession session = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId(userId);
        user.setSessionVersion(0L);
        user.setIsAdmin(false);
        session.setAttribute(Constants.SESSION_KEY, user);
        return session;
    }

    private void rejected(ResultActions result) throws Exception {
        // 业务错误码沿用现有 JSON 协议；不把 HTTP 200 错判成文件读取成功。
        result.andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(not(200)));
    }

    private void folder(String id, String parent) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,?,?,?,1,2,2)",
                id, OWNER, parent, id);
    }

    private void file(String id, String owner, String parent, String name, String relative, byte[] bytes) throws IOException {
        Path target = STORAGE.resolve("file").resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,file_size,file_path,folder_type,file_category,file_type,status,del_flag) "
                        + "values (?,?,?,?,?,?,0,4,7,2,2)", id, owner, parent, name, bytes.length, relative);
    }

    private static Path createStorage() {
        try { return Files.createTempDirectory("netdisk-content-http-").toAbsolutePath().normalize(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
