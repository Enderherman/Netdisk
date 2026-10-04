package top.enderherman.netdisk.common.utils;

import top.enderherman.netdisk.common.exceptions.BusinessException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 网盘展示名与下载名的共同规则，名称不能成为磁盘路径。 */
public final class FileNames {
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\\\/:*?\"<>|]");
    private static final Pattern RESERVED = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    private FileNames() { }

    public static String requireValid(String name) {
        if (name != null) name = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (name == null || name.isBlank() || name.length() > 200 || !name.equals(name.strip())
                || name.endsWith(".") || UNSAFE.matcher(name).find() || RESERVED.matcher(name).matches()
                || name.equals(".") || name.equals("..")) {
            throw new BusinessException("名称须为 1 至 200 个字符，不能含路径符号、控制字符、首尾空白或系统保留名称");
        }
        return name;
    }

    /** 以大小写和组合重音归一化避免常见跨平台同名。 */
    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** occupiedKeys 使用 key(name)，调用者将返回值加入集合以保留该名称。 */
    public static String unique(String name, boolean folder, Set<String> occupiedKeys) {
        name = requireValid(name);
        if (!occupiedKeys.contains(key(name))) return name;
        int dot = name.lastIndexOf('.');
        String suffix = !folder && dot > 0 ? name.substring(dot) : "";
        String stem = suffix.isEmpty() ? name : name.substring(0, dot);
        for (int n = 1; ; n++) {
            String marker = " (" + n + ")";
            if (marker.length() + suffix.length() >= 200) {
                throw new BusinessException("文件扩展名过长，无法自动生成唯一名称，请先重命名");
            }
            String prefix = stem.substring(0, Math.min(stem.length(), 200 - marker.length() - suffix.length()));
            // 不在 Unicode 代理对中间截断。
            if (!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length() - 1))) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            String candidate = prefix + marker + suffix;
            if (!occupiedKeys.contains(key(candidate))) return candidate;
        }
    }
}
