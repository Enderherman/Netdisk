package top.enderherman.netdisk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.enderherman.netdisk.common.config.RequestSecurityFilter;

import static org.junit.jupiter.api.Assertions.*;

class RequestSecurityFilterTest {
    @Test
    void sameOriginAndNoOriginClientsAreAllowed() throws Exception {
        assertAllowed(request(null, null), "");
        assertAllowed(request("http://localhost", null), "");
        assertAllowed(request(null, "http://localhost/page"), "");
    }

    @Test
    void retainedViteHostAndExplicitTrustedOriginAreAllowed() throws Exception {
        MockHttpServletRequest vite = request("http://localhost:5173", null);
        vite.setServerPort(5173);
        assertAllowed(vite, "");
        assertAllowed(request("https://cloud.example.test", null), "https://cloud.example.test");
    }

    @Test
    void sameOriginIpv6LoopbackIsAllowed() throws Exception {
        MockHttpServletRequest ipv6 = request("http://[::1]:5173", null);
        ipv6.setServerName("::1");
        ipv6.setServerPort(5173);
        assertAllowed(ipv6, "");
    }

    @Test
    void hostileMalformedAndNullOriginsAreRejected() throws Exception {
        for (String origin : new String[]{"https://evil.test", "null", "http://localhost:81", "http://user@localhost", "invalid"}) {
            assertRejected(request(origin, "http://localhost/safe"));
        }
    }

    @Test
    void hostileRefererAndCrossSiteFetchMetadataAreRejected() throws Exception {
        assertRejected(request(null, "https://evil.test/page"));
        MockHttpServletRequest browser = request(null, null);
        browser.addHeader("Sec-Fetch-Site", "cross-site");
        assertRejected(browser);
    }

    @Test
    void forgedForwardedHostDoesNotOverrideRequestOrigin() throws Exception {
        MockHttpServletRequest request = request("https://evil.test", null);
        request.addHeader("X-Forwarded-Host", "evil.test");
        request.addHeader("X-Forwarded-Proto", "https");
        assertRejected(request);
    }

    @Test
    void readRequestsAreUnaffected() throws Exception {
        MockHttpServletRequest request = request("https://external.test", null);
        request.setMethod("GET");
        assertAllowed(request, "");
    }

    private MockHttpServletRequest request(String origin, String referer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/updateProfile");
        request.setScheme("http"); request.setServerName("localhost"); request.setServerPort(80);
        if (origin != null) request.addHeader("Origin", origin);
        if (referer != null) request.addHeader("Referer", referer);
        return request;
    }
    private void assertAllowed(MockHttpServletRequest request, String allowed) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestSecurityFilter(new ObjectMapper(), allowed).doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    }
    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestSecurityFilter(new ObjectMapper(), "").doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }
}
