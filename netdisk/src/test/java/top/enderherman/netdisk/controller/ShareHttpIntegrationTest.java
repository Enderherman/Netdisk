package top.enderherman.netdisk.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.utils.RedisUtils;
import top.enderherman.netdisk.entity.dto.SessionWebUserDto;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MVC、鉴权切面和 MyBatis，数据库使用隔离 H2，外部服务均模拟。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ShareHttpIntegrationTest {
    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    private MockHttpSession owner;
    private MockHttpSession guest;

    @BeforeEach
    void seed() {
        jdbc.update("insert into user_info(user_id,nick_name,email,status,use_space,total_space) values ('owner','Owner','owner@example.test',1,0,1048576)");
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values ('root','owner','0','shared',1,2,2)");
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,folder_type,status,del_flag) values ('secret','owner','0','private.txt',0,2,2)");
        jdbc.update("insert into file_share(share_id,file_id,user_id,valid_type,code,share_time) values ('share','root','owner',3,'abcd',current_timestamp)");
        owner = new MockHttpSession();
        SessionWebUserDto user = new SessionWebUserDto();
        user.setUserId("owner");
        user.setIsAdmin(false);
        owner.setAttribute(Constants.SESSION_KEY, user);
        guest = new MockHttpSession();
    }

    @Test
    void everyShareManagementEndpointRequiresLogin() throws Exception {
        for (String endpoint : new String[]{"loadShareList", "shareFile", "cancelShare"}) {
            mvc.perform(post("/share/" + endpoint)).andExpect(jsonPath("$.code").value(901));
        }
    }

    @Test
    void extractThenCancelImmediatelyInvalidatesSameGuestSession() throws Exception {
        extract();
        mvc.perform(post("/showShare/loadFileList").session(guest).param("shareId", "share"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.list[0].fileId").value("root"));
        mvc.perform(post("/share/cancelShare").session(owner).param("shareIds", "share"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/showShare/loadFileList").session(guest).param("shareId", "share"))
                .andExpect(jsonPath("$.code").value(902));
        mvc.perform(post("/showShare/createDownloadUrl/share/secret").session(guest))
                .andExpect(jsonPath("$.code").value(902));
    }

    @Test
    void extractedShareDoesNotAuthorizeUnsharedFile() throws Exception {
        extract();
        mvc.perform(post("/showShare/createDownloadUrl/share/secret").session(guest))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/showShare/getFile/share/secret").session(guest))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void deletedSourceAndExpiredShareAreRejected() throws Exception {
        jdbc.update("update file_info set del_flag=1 where file_id='root'");
        mvc.perform(post("/showShare/checkShareCode").session(guest).param("shareId", "share").param("code", "abcd"))
                .andExpect(jsonPath("$.code").value(902));
        jdbc.update("update file_info set del_flag=2 where file_id='root'");
        jdbc.update("update file_share set expire_time='2020-01-01 00:00:00' where share_id='share'");
        mvc.perform(post("/showShare/getShareInfo").param("shareId", "share"))
                .andExpect(jsonPath("$.code").value(902));
    }

    @Test
    void createValidShareAndRejectOversizedCode() throws Exception {
        mvc.perform(post("/share/shareFile").session(owner).param("fileId", "root").param("validType", "3").param("code", "1234"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.code").value("1234"));
        mvc.perform(post("/share/shareFile").session(owner).param("fileId", "root").param("validType", "3").param("code", "123456"))
                .andExpect(jsonPath("$.code").value(600));
    }

    private void extract() throws Exception {
        mvc.perform(post("/showShare/checkShareCode").session(guest).param("shareId", "share").param("code", "abcd"))
                .andExpect(jsonPath("$.code").value(200));
    }
}
