package top.enderherman.netdisk.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.entity.enums.ResponseCodeEnum;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CopyUtils {

    /**
     * 将 List<S> 类型的对象列表转换为 List<T> 类型的对象列表
     *
     * @param sList  源对象列表
     * @param tClass 目标对象类型的 Class
     * @param <T>    目标对象类型
     * @param <S>    源对象类型
     * @return 转换后的 List<T> 对象
     */
    public static <T, S> List<T> copyList(List<S> sList, Class<T> tClass) {
        List<T> list = new ArrayList<>();
        if (sList == null || sList.isEmpty()) {
            return list; // 返回空列表，避免 NullPointerException
        }
        for (S s : sList) {
            T t = createInstance(tClass);
            if (t != null) {
                BeanUtils.copyProperties(s, t);
                list.add(t);
            }
        }
        return list;
    }

    /**
     * 将 S 类型的对象转换为 T 类型的对象
     *
     * @param s      源对象
     * @param tClass 目标对象类型的 Class
     * @param <T>    目标对象类型
     * @param <S>    源对象类型
     * @return 转换后的 T 对象
     */
    public static <T, S> T copy(S s, Class<T> tClass) {
        if (s == null) {
            return null; // 避免空指针异常
        }
        T t = createInstance(tClass);
        if (t != null) {
            BeanUtils.copyProperties(s, t);
        }
        return t;
    }

    /**
     * 安全创建目标对象实例
     *
     * @param tClass 目标对象类型的 Class
     * @param <T>    目标对象类型
     * @return 创建的实例对象，如果失败则返回 null
     */
    private static <T> T createInstance(Class<T> tClass) {
        try {
            return tClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("创建对象失败",e);
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }
}
