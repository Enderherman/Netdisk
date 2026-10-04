package top.enderherman.netdisk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import top.enderherman.netdisk.common.utils.RedisUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NetdiskApplicationTests {

    @MockBean private RedisUtils<Object> redisUtils;
    @MockBean private JavaMailSender mailSender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test
    void contextLoads() {
        assertEquals(0, jdbc.queryForObject("select count(*) from user_info", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from file_info", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from file_share", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from email_code", Integer.class));
    }

    @Test
    void unauthenticatedFilesAreRejected() throws Exception {
        mvc.perform(get("/file/loadDataList")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(901));
    }

    @Test
    void captchaIsAnImage() throws Exception {
        mvc.perform(get("/checkCode")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"));
    }
}
