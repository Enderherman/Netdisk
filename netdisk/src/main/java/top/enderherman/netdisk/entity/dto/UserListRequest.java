package top.enderherman.netdisk.entity.dto;

import lombok.Data;
import top.enderherman.netdisk.entity.query.UserQuery;

/** 管理员可搜索的公开资料字段；不接受密码等内部查询条件。 */
@Data
public class UserListRequest {
    private Integer pageNo;
    private Integer pageSize;
    private String userId;
    private String nickNameFuzzy;
    private String emailFuzzy;
    private Integer status;

    public UserQuery toQuery() {
        UserQuery query = new UserQuery();
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        query.setUserId(userId);
        query.setNickNameFuzzy(nickNameFuzzy);
        query.setEmailFuzzy(emailFuzzy);
        query.setStatus(status);
        return query;
    }
}
