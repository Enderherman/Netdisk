package top.enderherman.netdisk.common.utils;

import org.apache.commons.codec.digest.DigestUtils;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;


public class StringUtils {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /**
     * 生成随机数
     *
     * @param length 长度
     * @return 随机数
     */
    public static String getRandomNumber(Integer length) {
        return randomCharacters(length, DIGITS);
    }

    /**
     * 生成随机码
     *
     * @param length 长度
     * @return 随机码
     */
    public static String getRandomString(Integer length) {
        return randomCharacters(length, ALPHANUMERIC);
    }

    private static String randomCharacters(Integer length, char[] alphabet) {
        if (length == null || length < 0) throw new IllegalArgumentException("随机码长度不能为负数或空值");
        char[] value = new char[length];
        for (int i = 0; i < value.length; i++) value[i] = alphabet[RANDOM.nextInt(alphabet.length)];
        return new String(value);
    }

    public static void checkParam(Object param) {
        try {
            Field[] fields = param.getClass().getDeclaredFields();
            boolean notEmpty = false;
            for (Field field : fields) {
                String methodName = "get" + StringUtils.upperCaseFirstLetter(field.getName());
                Method method = param.getClass().getMethod(methodName);
                Object object = method.invoke(param);
                if (object != null && object instanceof java.lang.String && !StringUtils.isEmpty(object.toString())
                        || object != null && !(object instanceof java.lang.String)) {
                    notEmpty = true;
                    break;
                }
            }
            if (!notEmpty) {
                throw new BusinessException("多参数更新，删除，必须有非空条件");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException("校验参数是否为空失败");
        }
    }



    /**
     * 校验空
     *
     * @param str 字符串
     * @return 是否为空
     */
    public static boolean isEmpty(String str) {
        return null == str || "".equals(str) || "null".equals(str) || "\u0000".equals(str) || "".equals(str.trim());
    }

    /**
     * md5编码
     *
     * @param originString 密码
     * @return 编码后的密码
     */
    public static String encodingByMd5(String originString) {
        return isEmpty(originString) ? null : DigestUtils.md5Hex(originString);
    }

    public static String upperCaseFirstLetter(String field) {
        if (isEmpty(field)) {
            return field;
        }
        //如果第二个字母是大写，第一个字母不大写
        if (field.length() > 1 && Character.isUpperCase(field.charAt(1))) {
            return field;
        }
        return field.substring(0, 1).toUpperCase() + field.substring(1);
    }

    public static boolean verifyPath(String path) {
        if (StringUtils.isEmpty(path)) {
            return true;
        }
        if (path.contains("../") || path.contains("..\\")) {
            return false;
        }
        return true;
    }

    /**
     * 重命名
     * @return 加5个随机字符重命名
     * @param originFileName 原文件名
     */
    public static String rename(String originFileName){
        String fileNameReal = getFileNameWithoutSuffix(originFileName);
        String suffix= getFileSuffix(originFileName);
        return fileNameReal+"_"+getRandomString(Constants.LENGTH_5)+suffix;
    }

    public static String getFileNameWithoutSuffix(String fileName){
        int index = fileName.lastIndexOf(".");
        if (index == -1){
            return fileName;
        }
        String prefix = fileName.substring(0,index);
        return prefix;
    }

    public static String getFileSuffix(String fileName){
        int index = fileName.lastIndexOf(".");
        if (index == -1){
            return "";
        }
        String suffix = fileName.substring(index);
        return suffix;
    }

    public static boolean pathIsOk(String path){
        if (StringUtils.isEmpty(path)){
            return true;
        }
        if (path.contains("../") || path.contains("..\\")){
            return false;
        }
        return true;
    }

    public static boolean isBlank(CharSequence cs) {
        int strLen;
        if (cs != null && (strLen = cs.length()) != 0) {
            for(int i = 0; i < strLen; ++i) {
                if (!Character.isWhitespace(cs.charAt(i))) {
                    return false;
                }
            }

            return true;
        } else {
            return true;
        }
    }
}
