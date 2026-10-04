package top.enderherman.netdisk.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.config.AppConfig;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.service.UserService;
import top.enderherman.netdisk.service.impl.FileUploadService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.commons.codec.digest.DigestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-security-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "project.folder=./target/admin-security-storage/"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminSecurityHttpIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private FileUploadService uploads;
    @Autowired private AppConfig appConfig;
    @TempDir Path storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    private MockHttpSession admin;
    private MockHttpSession member;

    @BeforeEach
    void seed() {
        appConfig.setProjectFolder(storage.toString());
        jdbc.update("delete from file_info");
        jdbc.update("delete from user_info");
        jdbc.update("insert into user_info(user_id,nick_name,email,password,status,use_space,total_space) values ('admin','Admin','admin@example.invalid','sensitive-hash',1,0,10485760)");
        jdbc.update("insert into user_info(user_id,nick_name,email,password,status,use_space,total_space) values ('member','Member','member@example.test','sensitive-hash',1,1048576,3145728)");
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,file_size,folder_type,status,del_flag) values ('content','member','0','document.txt',1048576,0,2,2)");
        admin = session("admin");
        member = session("member");
    }

    @Test
    void userSearchIsBoundedAndNeverReturnsOrFiltersBySecrets() throws Exception {
        mvc.perform(post("/admin/loadUserList").session(admin).param("userId", "member").param("passwordFuzzy", "does-not-match"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].sessionVersion").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].qqOpenId").doesNotExist());
        for (String size : new String[]{"0", "-1", "101", "2147483647"}) {
            mvc.perform(post("/admin/loadUserList").session(admin).param("pageSize", size)).andExpect(jsonPath("$.code").value(600));
        }
        mvc.perform(post("/admin/loadUserList").session(admin).param("pageNo", "0")).andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void settingsValidateTemplateQuotaAndCacheWriteResult() throws Exception {
        mvc.perform(post("/admin/saveSysSettings").session(admin).param("registerEmailTitle", "Welcome")
                        .param("registerEmailContent", "No placeholder").param("userInitUseSpace", "50"))
                .andExpect(jsonPath("$.code").value(600));
        for (String size : new String[]{"-1", "0", "1048577"}) {
            mvc.perform(post("/admin/saveSysSettings").session(admin).param("registerEmailTitle", "Welcome")
                            .param("registerEmailContent", "Code: %s").param("userInitUseSpace", size))
                    .andExpect(jsonPath("$.code").value(600));
        }
        mvc.perform(post("/admin/saveSysSettings").session(admin).param("registerEmailTitle", "Welcome\r\nBcc:other")
                        .param("registerEmailContent", "Code: %s").param("userInitUseSpace", "50"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/admin/saveSysSettings").session(admin).param("registerEmailTitle", "Welcome")
                        .param("registerEmailContent", "Code: %s").param("userInitUseSpace", "50"))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.message").value("系统设置保存失败，请检查缓存服务"));
        when(redisUtils.set(eq(Constants.REDIS_KEY_SYS_SETTING), any())).thenReturn(true);
        mvc.perform(post("/admin/saveSysSettings").session(admin).param("registerEmailTitle", "Welcome")
                        .param("registerEmailContent", "100% ready. Code: %s").param("userInitUseSpace", "50"))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void quotaCanGrowOrShrinkWithoutDroppingBelowUsedSpace() throws Exception {
        mvc.perform(post("/admin/updateUserSpace").session(admin).param("userId", "member").param("changeSpace", "2"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(5 * Constants.MB, quota());
        mvc.perform(post("/admin/updateUserSpace").session(admin).param("userId", "member").param("changeSpace", "-3"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(2 * Constants.MB, quota());
        for (String change : new String[]{"-2", "0", "-2147483648"}) {
            mvc.perform(post("/admin/updateUserSpace").session(admin).param("userId", "member").param("changeSpace", change))
                    .andExpect(jsonPath("$.code").value(600));
        }
        assertEquals(2 * Constants.MB, quota());
        mvc.perform(post("/admin/updateUserSpace").session(admin).param("userId", "missing").param("changeSpace", "1"))
                .andExpect(jsonPath("$.code").value(600));
        verify(redisUtils, times(2)).delete(Constants.REDIS_KEY_USER_SPACE_USE + "member");
    }

    @Test
    void shrinkIncludesRealUnfinishedUploadsAndExcludesCompletedReceipts() {
        byte[] fullFile = new byte[2 * 1024 * 1024];
        String checksum = DigestUtils.md5Hex(fullFile);
        SessionWebUserDto user = (SessionWebUserDto) member.getAttribute(Constants.SESSION_KEY);
        var upload = uploads.upload(user, null,
                new MockMultipartFile("file", "pending.bin", "application/octet-stream", Arrays.copyOf(fullFile, fullFile.length - 1)),
                "pending.bin", "0", checksum, 0, 2);
        assertThrows(BusinessException.class, () -> users.changeUserSpace("member", -1));
        assertEquals(3 * Constants.MB, quota());
        uploads.upload(user, upload.getFileId(), new MockMultipartFile("file", "pending.bin", "application/octet-stream", new byte[]{0}),
                "pending.bin", "0", checksum, 1, 2);
        // 释放初始化的 1MB 文件，保留已完成任务 receipt，缩容不能重复计费 receipt。
        jdbc.update("delete from file_info where file_id='content' and user_id='member'");
        users.changeUserSpace("member", -1);
        assertEquals(2 * Constants.MB, quota());
    }

    @Test
    void concurrentShrinksAreSerializedAgainstCurrentQuota() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> shrink = () -> {
                try { users.changeUserSpace("member", -2); return true; }
                catch (BusinessException expected) { return false; }
            };
            var results = executor.invokeAll(List.of(shrink, shrink));
            int success = (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0);
            assertEquals(1, success);
        } finally { executor.shutdownNow(); }
        assertEquals(Constants.MB, quota());
    }

    @Test
    void adminDeletionUsesCorrectOwnerAndInvalidBatchRollsBackEverything() throws Exception {
        mvc.perform(post("/admin/delFile").session(admin).param("fileIdAndUserIds", "content_member,missing_zzmissing"))
                .andExpect(jsonPath("$.code").value(600));
        assertEquals(2, fileState());
        mvc.perform(post("/admin/delFile").session(admin).param("fileIdAndUserIds", "content_member"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(3, fileState());
    }

    @Test
    void malformedDeletionAndNonAdminCannotMutateData() throws Exception {
        mvc.perform(post("/admin/delFile").session(admin).param("fileIdAndUserIds", "content_member,bad"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/admin/delFile").session(member).param("fileIdAndUserIds", "content_member"))
                .andExpect(jsonPath("$.code").value(404));
        assertEquals(2, fileState());
    }

    @Test
    void getRequestsCannotReachAnyMutation() throws Exception {
        for (String path : new String[]{"/admin/saveSysSettings", "/admin/updateUserStatus", "/admin/updateUserSpace", "/admin/delFile",
                "/file/uploadFile", "/file/newFolder", "/file/newFoloder", "/file/rename", "/file/changeFileFolder", "/file/delFile",
                "/file/createDownloadUrl/content", "/share/shareFile", "/share/cancelShare", "/showShare/checkShareCode", "/showShare/saveShare",
                "/showShare/createDownloadUrl/share/content", "/recycle/recoverFile", "/recycle/delFile", "/updateUserAvatar", "/updateProfile"}) {
            mvc.perform(get(path).session(admin)).andExpect(status().isMethodNotAllowed());
        }
        assertEquals(2, fileState());
        assertEquals(3 * Constants.MB, quota());
    }

    @Test
    void hostileOriginCannotChangeProfileButSameOriginAndCliCan() throws Exception {
        mvc.perform(post("/updateProfile").session(member).header("Origin", "https://evil.test").param("nickName", "Attacker"))
                .andExpect(status().isForbidden());
        assertEquals("Member", nickname());
        mvc.perform(post("/updateProfile").session(member).header("Origin", "http://localhost").param("nickName", "New Member")
                        .param("email", "changed@example.test").param("isAdmin", "true"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.isAdmin").value(false));
        assertEquals("member@example.test", jdbc.queryForObject("select email from user_info where user_id='member'", String.class));
        mvc.perform(post("/updateProfile").session(member).param("nickName", "CLI Member")).andExpect(jsonPath("$.code").value(200));
        assertEquals("CLI Member", nickname());
    }

    @Test
    void nicknameRejectsDuplicatesAndControlCharacters() throws Exception {
        mvc.perform(post("/updateProfile").session(member).param("nickName", "Admin")).andExpect(jsonPath("$.code").value(601));
        mvc.perform(post("/updateProfile").session(member).param("nickName", "Bad\nName")).andExpect(jsonPath("$.code").value(600));
        assertEquals("Member", nickname());
    }

    @Test
    void avatarUploadAndFallbackAlwaysReturnSafeImages() throws Exception {
        byte[] fallback = mvc.perform(get("/getAvatar/noavatar")).andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(fallback)));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), "png", png);
        mvc.perform(multipart("/updateUserAvatar").file(new MockMultipartFile("avatar", "image.png", "image/png", png.toByteArray())).session(member))
                .andExpect(jsonPath("$.code").value(200));
        byte[] actual = mvc.perform(get("/getAvatar/member")).andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(20, ImageIO.read(new ByteArrayInputStream(actual)).getWidth());
        mvc.perform(multipart("/updateUserAvatar").file(new MockMultipartFile("avatar", "fake.jpg", "image/jpeg", "<html/>".getBytes())).session(member))
                .andExpect(jsonPath("$.code").value(600));
    }

    private MockHttpSession session(String userId) {
        MockHttpSession session = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto(); user.setUserId(userId); user.setIsAdmin(false);
        session.setAttribute(Constants.SESSION_KEY, user);
        return session;
    }
    private long quota() { return jdbc.queryForObject("select total_space from user_info where user_id='member'", Long.class); }
    private int fileState() { return jdbc.queryForObject("select del_flag from file_info where file_id='content' and user_id='member'", Integer.class); }
    private String nickname() { return jdbc.queryForObject("select nick_name from user_info where user_id='member'", String.class); }
}
