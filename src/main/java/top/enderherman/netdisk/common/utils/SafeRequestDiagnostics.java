package top.enderherman.netdisk.common.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** 请求异常只记录框架路由、异常类型与代码位置，不记录请求值或异常原文。 */
public final class SafeRequestDiagnostics {
    public enum Phase { REQUEST, INTERCEPTOR, PARAMETER_VALIDATION }
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private SafeRequestDiagnostics() { }

    public static void log(Logger logger, Phase phase, Throwable failure) {
        var attributes = RequestContextHolder.getRequestAttributes();
        log(logger, phase, attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null, failure);
    }

    public static void log(Logger logger, Phase phase, HttpServletRequest request, Throwable failure) {
        String method = request != null && METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> causes = new LinkedHashSet<>(), positions = new LinkedHashSet<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 4 && seen.add(current); depth++, current = current.getCause()) {
            causes.add(safeIdentifier(current.getClass().getName()));
            StackTraceElement[] trace = current.getStackTrace();
            for (StackTraceElement frame : trace) {
                if (positions.size() == 6) break;
                if (frame.getClassName().startsWith("top.enderherman.netdisk.")) positions.add(position(frame));
            }
            if (positions.isEmpty() && trace.length != 0) positions.add(position(trace[0]));
        }
        // 不把 Throwable 作为日志参数；日志后端也不能再追加 message、cause 或 suppressed 内容。
        logger.error("请求异常 phase={} method={} route={} type={} causes={} at={}", phase.name(), method,
                route(request), failure == null ? "UnknownException" : safeIdentifier(failure.getClass().getName()),
                causes.isEmpty() ? "none" : String.join(" -> ", causes), positions.isEmpty() ? "unavailable" : String.join(",", positions));
    }

    private static String route(HttpServletRequest request) {
        if (request == null) return "[unmapped]";
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = value instanceof String text ? text : value instanceof PathPattern path ? path.getPatternString() : null;
        // 只读框架匹配模板，绝不回退到 URL、URI、servletPath、查询或异常携带的请求路径。
        return pattern != null && pattern.length() <= 256 && pattern.matches("/[A-Za-z0-9_./{}*:-]*") ? pattern : "[unmapped]";
    }

    private static String position(StackTraceElement frame) {
        return safeIdentifier(frame.getClassName()) + "." + safeIdentifier(frame.getMethodName()) + ":" + frame.getLineNumber();
    }

    private static String safeIdentifier(String value) {
        return value != null && value.length() <= 256 && value.matches("[A-Za-z_$][A-Za-z0-9_.$]*|<init>|<clinit>") ? value : "unknown";
    }
}
