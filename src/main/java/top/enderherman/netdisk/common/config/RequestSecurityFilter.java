package top.enderherman.netdisk.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 浏览器写请求必须来自同源；代理需保留 Host 或显式配置可信前端来源。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSecurityFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final Set<String> allowedOrigins;

    public RequestSecurityFilter(ObjectMapper json, @Value("${netdisk.security.allowed-origins:}") String origins) {
        this.json = json;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(this::origin).collect(Collectors.toSet());
        this.allowedOrigins.remove(null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
            String source = request.getHeader("Origin");
            if (source == null || source.isBlank()) source = request.getHeader("Referer");
            String expected = request.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + canonicalHost(request.getServerName()) + ":" + request.getServerPort();
            boolean rejected = source != null && (origin(source) == null
                    || (!expected.equals(origin(source)) && !allowedOrigins.contains(origin(source))));
            if (source == null && "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) rejected = true;
            if (rejected) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getOutputStream(), Map.of("status", "error", "code", 403,
                        "message", "已拒绝跨站写入请求"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private String origin(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) return null;
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + canonicalHost(uri.getHost()) + ":" + port;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String canonicalHost(String host) {
        return host.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
    }
}
