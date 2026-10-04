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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class RecentFilesHttpIntegrationTest {
    @MockBean RedisUtils<Object> redisUtils;
    @MockBean JavaMailSender mailSender;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    MockHttpSession session;

    @BeforeEach void seed() {
        jdbc.update("insert into user_info(user_id,nick_name,email,status,use_space,total_space) values ('recent','Recent','recent@example.test',1,0,1000)");
        SessionWebUserDto user = new SessionWebUserDto(); user.setUserId("recent"); user.setSessionVersion(0L); user.setIsAdmin(false);
        session = new MockHttpSession(); session.setAttribute(Constants.SESSION_KEY, user);
        insert("old", "recent", "0", "2026-01-01 00:00:00", 0, 2, 2);
        insert("new", "recent", "folder", "2026-10-04 00:00:00", 0, 2, 2);
        insert("foreign", "other", "0", "2026-10-05 00:00:00", 0, 2, 2);
        insert("folder", "recent", "0", "2026-10-05 00:00:00", 1, 2, 2);
        insert("recycle", "recent", "0", "2026-10-05 00:00:00", 0, 1, 2);
        insert("partial", "recent", "0", "2026-10-05 00:00:00", 0, 2, 0);
    }

    @Test void listsOnlyOwnReadyFilesAcrossFoldersInLatestOrder() throws Exception {
        mvc.perform(get("/file/recent").session(session).param("userId", "other").param("orderBy", "invalid"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.list[0].fileId").value("new"))
                .andExpect(jsonPath("$.data.list[1].fileId").value("old"))
                .andExpect(jsonPath("$.data.list[0].filePath").doesNotExist());
    }

    @Test void supportsRealPaginationAndRejectsInvalidBounds() throws Exception {
        mvc.perform(get("/file/recent").session(session).param("pageNo", "2").param("pageSize", "1"))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].fileId").value("old"));
        mvc.perform(get("/file/recent").session(session).param("pageNo", "0"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(get("/file/recent").session(session).param("pageSize", "101"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test void requiresAuthentication() throws Exception {
        mvc.perform(get("/file/recent")).andExpect(jsonPath("$.code").value(901));
    }

    private void insert(String id, String owner, String parent, String modified, int folder, int deleted, int state) {
        jdbc.update("insert into file_info(file_id,user_id,file_pid,file_name,last_update_time,folder_type,del_flag,status) values (?,?,?,?,?,?,?,?)",
                id, owner, parent, id + ".txt", modified, folder, deleted, state);
    }
}
