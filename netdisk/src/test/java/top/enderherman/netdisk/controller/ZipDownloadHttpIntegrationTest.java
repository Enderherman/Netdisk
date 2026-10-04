package top.enderherman.netdisk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.DownloadFileDto;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.dto.ZipDownloadDto;
import top.enderherman.netdisk.service.AccountRateLimiter;
import top.enderherman.netdisk.service.UserService;
import top.enderherman.netdisk.service.ZipDownloadService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:zip-download-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ZipDownloadHttpIntegrationTest {
    @MockBean private RedisUtils<Object> redis;
    @MockBean private JavaMailSender mail;
    @MockBean private AccountRateLimiter rateLimiter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppConfig config;
    @Autowired private ZipDownloadService zips;
    @Autowired private UserService users;
    @TempDir Path storage;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private MockHttpSession owner;
    private static final byte[] FIRST = "云盘目录测试\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void seed() throws Exception {
        config.setProjectFolder(storage.toString()); cache.clear();
        jdbc.update("delete from file_share"); jdbc.update("delete from file_info"); jdbc.update("delete from user_info");
        jdbc.update("insert into user_info(user_id,nick_name,email,password,status,use_space,total_space) values ('zipOwner','Owner','zip-owner@example.test',?,1,0,10000000),('zipGuest','Guest','zip-guest@example.test',?,1,0,10000000)",
                StringUtils.encodingByMd5("Password123"), StringUtils.encodingByMd5("Password123"));
        folder("folder", "0", "资料"); folder("empty", "folder", "空目录");
        file("fileA", "folder", "说明.txt", FIRST, "zipOwner");
        file("fileB", "0", "说明.txt", new byte[]{1, 2, 3}, "zipOwner");
        file("foreign", "0", "他人的文件.txt", new byte[]{4}, "zipGuest");
        when(redis.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0, String.class)));
        when(redis.setEx(anyString(), any(), anyLong())).thenAnswer(call -> {
            // 按生产 Redis 配置真实序列化往返，验证嵌套清单和 sessionVersion 不丢失。
            var serializer = org.springframework.data.redis.serializer.RedisSerializer.json();
            Object decoded = serializer.deserialize(serializer.serialize(call.getArgument(1)));
            cache.put(call.getArgument(0, String.class), decoded); return true;
        });
        owner = session(0);
    }

    @Test
    void archiveContainsChineseTreeEmptyDirectoryAndDeduplicatedSelection() throws Exception {
        String code = createZip("folder,fileA,empty");
        byte[] archive = downloadZip(code);
        Map<String, byte[]> entries = unzip(archive);
        assertEquals(Set.of("资料/", "资料/空目录/", "资料/说明.txt"), entries.keySet());
        assertArrayEquals(FIRST, entries.get("资料/说明.txt"));
        assertEquals(0, entries.get("资料/空目录/").length);
        Path saved = storage.resolve("verified.zip"); Files.write(saved, archive);
        try (ZipFile zip = new ZipFile(saved.toFile(), StandardCharsets.UTF_8)) { assertEquals(3, zip.size()); }
        assertEquals(0L, jdbc.queryForObject("select use_space from user_info where user_id='zipOwner'", Long.class));
    }

    @Test
    void batchNamesAreUniqueAndZeroByteFilesRemainPresent() throws Exception {
        file("zero", "0", "零字节.txt", new byte[0], "zipOwner");
        Map<String, byte[]> entries = unzip(downloadZip(createZip("fileA,fileB,zero,empty")));
        assertEquals(Set.of("说明.txt", "说明 (1).txt", "零字节.txt", "空目录/"), entries.keySet());
        assertArrayEquals(FIRST, entries.get("说明.txt"));
        assertArrayEquals(new byte[]{1, 2, 3}, entries.get("说明 (1).txt"));
        assertEquals(0, entries.get("零字节.txt").length);
    }

    @Test
    void foreignDeletedAndUnfinishedTreesCannotIssueTokens() throws Exception {
        for (String ids : new String[]{"foreign", "fileA,foreign", "missing", "fileA,", "0"}) {
            mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", ids).param("userId", "zipGuest"))
                    .andExpect(jsonPath("$.code").value(600));
        }
        jdbc.update("update file_info set status=0 where file_id='fileA' and user_id='zipOwner'");
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "folder")).andExpect(jsonPath("$.code").value(600));
        jdbc.update("update file_info set status=2 where file_id='fileA' and user_id='zipOwner'");
        jdbc.update("update file_info set del_flag=1 where file_id='folder' and user_id='zipOwner'");
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "fileA")).andExpect(jsonPath("$.code").value(600));
        assertTrue(cache.isEmpty());
    }

    @Test
    void archiveTraversalAndStorageTraversalAreRejectedBeforeSigning() throws Exception {
        for (String name : new String[]{"../escape", "..\\escape", "/absolute", "C:evil", ".", ".."}) {
            jdbc.update("update file_info set file_name=? where file_id='fileA' and user_id='zipOwner'", name);
            mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "fileA")).andExpect(jsonPath("$.code").value(600));
        }
        jdbc.update("update file_info set file_name='safe.txt',file_path='../../outside.txt' where file_id='fileA' and user_id='zipOwner'");
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "fileA")).andExpect(jsonPath("$.code").value(404));
        assertTrue(cache.isEmpty());
    }

    @Test
    void changedTreeOrMissingPhysicalFileInvalidatesSignedSnapshot() throws Exception {
        String original = createZip("folder");
        jdbc.update("update file_info set file_name='renamed.txt' where file_id='fileA' and user_id='zipOwner'");
        mvc.perform(get("/file/downloadZip/" + original)).andExpect(jsonPath("$.code").value(600));
        String updated = createZip("folder");
        Files.delete(storage.resolve("file/202610/fileA.bin"));
        mvc.perform(get("/file/downloadZip/" + updated)).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void missingExpiredAndMalformedCodesAreRejected() throws Exception {
        String expired = createZip("folder");
        ((ZipDownloadDto) cache.get(ZipDownloadService.REDIS_PREFIX + expired)).setExpiresAt(System.currentTimeMillis() - 1);
        mvc.perform(get("/file/downloadZip/" + expired)).andExpect(jsonPath("$.code").value(600));
        String missing = createZip("fileA"); cache.remove(ZipDownloadService.REDIS_PREFIX + missing);
        mvc.perform(get("/file/downloadZip/" + missing)).andExpect(jsonPath("$.code").value(600));
        mvc.perform(get("/file/downloadZip/invalid")).andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void redisFailureCannotPretendZipPrivateOrShareCodeWasIssued() throws Exception {
        MockHttpSession share = extractedShare();
        doReturn(false).when(redis).setEx(anyString(), any(), anyLong());
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "fileA"))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/file/createDownloadUrl/fileA").session(owner))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/showShare/createDownloadUrl/shared/fileA").session(share))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data").doesNotExist());
        assertTrue(cache.isEmpty());
    }

    @Test
    void disableThenEnableNeverRevivesOldPrivateOrZipCodes() throws Exception {
        String single = createPrivate(); String zip = createZip("folder");
        users.updateUserStatus("zipOwner", 0); users.updateUserStatus("zipOwner", 1);
        mvc.perform(get("/file/download/" + single)).andExpect(jsonPath("$.code").value(901));
        mvc.perform(get("/file/downloadZip/" + zip)).andExpect(jsonPath("$.code").value(901));
        owner = session(2);
        mvc.perform(get("/file/download/" + createPrivate())).andExpect(content().bytes(FIRST));
        assertTrue(unzip(downloadZip(createZip("folder"))).containsKey("资料/说明.txt"));
    }

    @Test
    void passwordChangesRevokePrivateCodesWithoutRevokingPublicShare() throws Exception {
        String single = createPrivate(); String zip = createZip("folder");
        MockHttpSession share = extractedShare();
        String shared = code(mvc.perform(post("/showShare/createDownloadUrl/shared/fileA").session(share))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString());
        users.changePassword("zipOwner", "Password123", "Changed123");
        mvc.perform(get("/file/download/" + single)).andExpect(jsonPath("$.code").value(901));
        mvc.perform(get("/file/downloadZip/" + zip)).andExpect(jsonPath("$.code").value(901));
        mvc.perform(get("/showShare/download/" + shared)).andExpect(content().bytes(FIRST));
    }

    @Test
    void privateLegacyTokenWithoutVersionMustBeReissued() throws Exception {
        String code = createPrivate();
        DownloadFileDto token = (DownloadFileDto) cache.get(Constants.REDIS_KEY_DOWNLOAD + code);
        assertEquals(0L, token.getSessionVersion()); token.setSessionVersion(null);
        mvc.perform(get("/file/download/" + code)).andExpect(jsonPath("$.code").value(901));
    }

    @Test
    void largeFileStreamsInBoundedWritesAndRoundTrips() throws Exception {
        byte[] expected = new byte[2 * 1024 * 1024]; new Random(7).nextBytes(expected);
        file("large", "0", "大文件.bin", expected, "zipOwner");
        RecordingResponse response = new RecordingResponse(null);
        zips.download(createZip("large"), response);
        assertTrue(response.writes > 1);
        assertTrue(response.maxWrite <= 64 * 1024);
        assertArrayEquals(expected, unzip(response.getContentAsByteArray()).get("大文件.bin"));
    }

    @Test
    void midStreamRevocationInterruptsInsteadOfFinishingAValidPartialArchive() throws Exception {
        byte[] data = new byte[2 * 1024 * 1024]; new Random(11).nextBytes(data);
        file("large", "0", "large.bin", data, "zipOwner");
        String code = createZip("large");
        RecordingResponse response = new RecordingResponse(() -> jdbc.update("update user_info set session_version=session_version+1 where user_id='zipOwner'"));
        assertThrows(IOException.class, () -> zips.download(code, response));
        assertTrue(response.isCommitted());
        assertTrue(response.revoked);
        Path incomplete = storage.resolve("incomplete.zip"); Files.write(incomplete, response.getContentAsByteArray());
        assertThrows(java.util.zip.ZipException.class, () -> { try (ZipFile ignored = new ZipFile(incomplete.toFile())) { } });
    }

    @Test
    void checksumFailureBeforeCommitProducesExplicitFailure() throws Exception {
        jdbc.update("update file_info set file_md5=? where file_id='fileA' and user_id='zipOwner'", "0".repeat(32));
        String code = createZip("fileA");
        MockHttpServletResponse response = new MockHttpServletResponse(); response.setBufferSize(65536);
        BusinessException error = assertThrows(BusinessException.class, () -> zips.download(code, response));
        assertEquals(500, error.getCode());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void selectionCountEntryCountAndPathLengthAreBounded() throws Exception {
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "fileA,".repeat(1000) + "fileA"))
                .andExpect(jsonPath("$.code").value(600));
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < 10000; n++) rows.add(new Object[]{"n" + n, "dir" + n});
        jdbc.batchUpdate("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,'zipOwner','empty',?,1,2,2)", rows);
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "empty"))
                .andExpect(jsonPath("$.code").value(600));
        jdbc.update("delete from file_info where file_pid='empty' and user_id='zipOwner'");
        String parent = "folder";
        for (int n = 0; n < 8; n++) { folder("depth" + n, parent, "路".repeat(190)); parent = "depth" + n; }
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", "folder"))
                .andExpect(jsonPath("$.code").value(600));
        assertTrue(cache.isEmpty());
    }

    @Test
    void signingRequiresPostLoginAndTrustedBrowserOrigin() throws Exception {
        mvc.perform(get("/file/createZipDownloadUrl").session(owner)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/file/createZipDownloadUrl").param("fileIds", "fileA")).andExpect(jsonPath("$.code").value(901));
        mvc.perform(post("/file/createZipDownloadUrl").session(owner).header("Origin", "https://evil.test").param("fileIds", "fileA"))
                .andExpect(status().isForbidden());
    }

    private String createZip(String ids) throws Exception {
        return code(mvc.perform(post("/file/createZipDownloadUrl").session(owner).param("fileIds", ids))
                .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString());
    }
    private String createPrivate() throws Exception {
        return code(mvc.perform(post("/file/createDownloadUrl/fileA").session(owner)).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString());
    }
    private String code(String body) throws Exception { return json.readTree(body).path("data").asText(); }
    private byte[] downloadZip(String code) throws Exception {
        return mvc.perform(get("/file/downloadZip/" + code)).andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
    }
    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertNull(result.put(entry.getName(), input.readAllBytes()), "归档中不能出现重名条目");
            }
        }
        return result;
    }
    private void folder(String id, String parent, String name) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values (?,'zipOwner',?,?,1,2,2)", id, parent, name);
    }
    private void file(String id, String parent, String name, byte[] data, String user) throws Exception {
        Path path = storage.resolve("file/202610/" + id + ".bin"); Files.createDirectories(path.getParent()); Files.write(path, data);
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,file_size,file_path,file_md5,folder_type,status,del_flag) values (?,?,?,?,?,?,?,0,2,2)",
                id, user, parent, name, data.length, "202610/" + id + ".bin", DigestUtils.md5Hex(data));
    }
    private MockHttpSession session(long version) {
        MockHttpSession session = new MockHttpSession(); SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId("zipOwner"); user.setIsAdmin(false); user.setSessionVersion(version);
        session.setAttribute(Constants.SESSION_KEY, user); return session;
    }
    private MockHttpSession extractedShare() throws Exception {
        jdbc.update("insert into file_share(share_id,file_id,user_id,valid_type,code,share_time) values ('shared','fileA','zipOwner',3,'abcd',current_timestamp)");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/showShare/checkShareCode").session(session).param("shareId", "shared").param("code", "abcd"))
                .andExpect(jsonPath("$.code").value(200));
        return session;
    }

    private static class RecordingResponse extends MockHttpServletResponse {
        int maxWrite; int writes; long bytes; boolean revoked;
        private final ServletOutputStream output;
        RecordingResponse(Runnable revoke) throws IOException {
            ServletOutputStream delegate = super.getOutputStream();
            output = new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) { }
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] value, int offset, int length) throws IOException {
                    maxWrite = Math.max(maxWrite, length); writes++; bytes += length;
                    delegate.write(value, offset, length);
                    if (revoke != null && !revoked && bytes > 65536) {
                        setCommitted(true); revoked = true; revoke.run();
                    }
                }
            };
        }
        @Override public ServletOutputStream getOutputStream() { return output; }
    }
}
