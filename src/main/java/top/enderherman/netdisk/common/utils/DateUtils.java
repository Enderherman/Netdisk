package top.enderherman.netdisk.common.utils;


import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * @author Enderherman
 * @date 2024/12/25
 * 日期工具类
 */
public class DateUtils {

    /**
     * 获取指定格式的 DateTimeFormatter
     * 相较于ThreadLocal更加线程安全，Java 8 提供
     *
     * @param pattern 格式化模式
     * @return DateTimeFormatter 实例
     */
    private static DateTimeFormatter getFormatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern);
    }

    /**
     * 将 Date 格式化为指定格式的字符串
     *
     * @param date    日期对象
     * @param pattern 格式化模式
     * @return 格式化后的字符串
     */
    public static String format(Date date, String pattern) {
        if (date == null) {
            throw new BusinessException("日期不可为空");
        }
        return getFormatter(pattern).format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 获取当前日期后指定天数的日期
     *
     * @param day 偏移的天数
     * @return 偏移后的日期对象
     */
    public static Date getAfterDate(Integer day) {
        LocalDate localDate = LocalDate.now().plusDays(day);
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
