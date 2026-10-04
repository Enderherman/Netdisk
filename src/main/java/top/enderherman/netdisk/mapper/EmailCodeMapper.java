package top.enderherman.netdisk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;

@Mapper
public interface EmailCodeMapper<T, P> extends BaseMapper<T, P> {

    void disableEmailCode(@Param("email") String email, @Param("purpose") Integer purpose);

    Integer consumeCode(@Param("email") String email, @Param("code") String code,
                        @Param("purpose") Integer purpose, @Param("validSince") Date validSince,
                        @Param("validUntil") Date validUntil);
}
