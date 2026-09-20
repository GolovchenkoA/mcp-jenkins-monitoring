package com.jenkinsmonitoring.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OriginValidationFilterTest {

    private final OriginValidationFilter filter = new OriginValidationFilter();

    private MockHttpServletResponse send(String origin, String host) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (host != null) {
            request.addHeader("Host", host);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() == null ? response : okResponse(response);
    }

    /** A request that reached the end of the chain was let through. */
    private static MockHttpServletResponse okResponse(MockHttpServletResponse response) {
        response.setStatus(200);
        return response;
    }

    @Test
    void localOriginsAreAllowedAndOthersAreNot() {
        assertThat(OriginValidationFilter.isLocalOrigin("http://localhost:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalOrigin("http://127.0.0.1:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalOrigin("http://[::1]:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalOrigin("HTTP://LOCALHOST:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalOrigin("https://evil.example.com")).isFalse();
        assertThat(OriginValidationFilter.isLocalOrigin("http://localhost.evil.com")).isFalse();
        assertThat(OriginValidationFilter.isLocalOrigin("null")).isFalse();
    }

    @Test
    void theHostHeaderMustNameALocalAddress() {
        assertThat(OriginValidationFilter.isLocalHost("localhost:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalHost("127.0.0.1:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalHost("[::1]:2026")).isTrue();
        assertThat(OriginValidationFilter.isLocalHost("evil.example.com")).isFalse();
        assertThat(OriginValidationFilter.isLocalHost("evil.example.com:2026")).isFalse();
    }

    @Test
    void aRequestWithoutAnOriginIsLetThrough() throws Exception {
        assertThat(send(null, "localhost:2026").getStatus()).isEqualTo(200);
        assertThat(send(null, "127.0.0.1:2026").getStatus()).isEqualTo(200);
    }

    @Test
    void aLocalBrowserOriginIsLetThrough() throws Exception {
        assertThat(send("http://127.0.0.1:2026", "127.0.0.1:2026").getStatus()).isEqualTo(200);
        assertThat(send("http://[::1]:2026", "[::1]:2026").getStatus()).isEqualTo(200);
    }

    @Test
    void aForeignOriginIsRefused() throws Exception {
        assertThat(send("https://evil.example.com", "localhost:2026").getStatus()).isEqualTo(403);
    }

    @Test
    void aRebindingHostIsRefusedEvenWithoutAnOrigin() throws Exception {
        assertThat(send(null, "evil.example.com:2026").getStatus()).isEqualTo(403);
    }
}
