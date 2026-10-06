package com.supermarket.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The browser signs in with an HttpOnly cookie, which page scripts cannot read (so an injected script cannot
 * steal the session). Controllers read the token from the X-Auth-Token header; this filter fills that header
 * from the cookie when a request does not send the header itself (API clients and tests still can).
 */
@Component
public class SessionCookieFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "POS_SESSION";
    public static final String TOKEN_HEADER = "X-Auth-Token";

    public static void writeCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString());
    }

    public static void clearCookie(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String cookieToken = request.getHeader(TOKEN_HEADER) == null ? readCookie(request) : null;
        chain.doFilter(cookieToken == null ? request : new TokenHeaderRequest(request, cookieToken), response);
    }

    private static String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static final class TokenHeaderRequest extends HttpServletRequestWrapper {

        private final String token;

        private TokenHeaderRequest(HttpServletRequest request, String token) {
            super(request);
            this.token = token;
        }

        @Override
        public String getHeader(String name) {
            return TOKEN_HEADER.equalsIgnoreCase(name) ? token : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return TOKEN_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(token))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            names.add(TOKEN_HEADER);
            return Collections.enumeration(names);
        }
    }
}
