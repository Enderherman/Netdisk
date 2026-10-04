package top.enderherman.netdisk.service;

import top.enderherman.netdisk.entity.dto.SessionWebUserDto;
import top.enderherman.netdisk.entity.pojo.User;
import top.enderherman.netdisk.entity.query.UserQuery;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;

import java.util.List;

public interface UserService {

    /**
     * 根据条件查询列表
     */
    List<User> findListByParam(UserQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(UserQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<User> findListByPage(UserQuery param);

    /**
     * 注册
     */
    void register(String email, String nickName, String password, String emailCode);

    SessionWebUserDto login(String email, String password);

    void resetPwd(String email, String password, String emailCode);

    void changePassword(String userId, String currentPassword, String password);

    void updateNickname(String userId, String nickName);

    void updateUserByUserId(User user, String userId);

    SessionWebUserDto qqLogin(String code);

    /**
     * 更改用户状态
     */
    void updateUserStatus(String userId, Integer status);

    /**
     * 更改用户空间
     */
    void changeUserSpace(String userId, Integer changeSpace);

    /**
     * 根据UserId查询对象
     */
    User getUserInfoByUserId(String userId);
}
