package com.jenkinsmonitoring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The MCP specification asks a local Streamable HTTP server to guard against DNS rebinding, where a web page
 * the user visits reaches this server through the browser. Two checks: the Origin header, when a browser
 * sends one, must be a local address; and the Host header must name a local address, which a rebinding
 * page cannot fake. Requests without an Origin header, such as those from desktop MCP clients, are allowed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");
        if ((origin != null && !isLocalOrigin(origin)) || (host != null && !isLocalHost(host))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isLocalOrigin(String origin) {
        return hostOf(origin).filter(LOCAL_HOSTS::contains).isPresent();
    }

    /** The Host header is {@code host[:port]}. */
    static boolean isLocalHost(String hostHeader) {
        return hostOf("http://" + hostHeader).filter(LOCAL_HOSTS::contains).isPresent();
    }

    private static Optional<String> hostOf(String url) {
        try {
            return Optional.ofNullable(URI.create(url).getHost()).map(host -> host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
