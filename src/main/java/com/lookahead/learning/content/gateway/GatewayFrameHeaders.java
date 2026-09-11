package com.lookahead.learning.content.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/** Permit the app's sandboxed teaching frames; keep every other gateway route unframeable. */
public final class GatewayFrameHeaders implements HeaderWriter {
    @Override public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        boolean teachingHtml = request.getMethod().equals("GET")
                && path.startsWith("/content/") && path.endsWith(".html");
        response.setHeader("X-Frame-Options", teachingHtml ? "SAMEORIGIN" : "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors " + (teachingHtml ? "'self'" : "'none'"));
    }
}
