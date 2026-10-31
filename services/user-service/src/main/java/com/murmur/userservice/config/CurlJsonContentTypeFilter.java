package com.murmur.userservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// curl -d posts a JSON body but sets Content-Type to application/x-www-form-urlencoded.
@Component
class CurlJsonContentTypeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            chain.doFilter(new JsonContentTypeRequest(request), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class JsonContentTypeRequest extends HttpServletRequestWrapper {
        private JsonContentTypeRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getContentType() {
            return MediaType.APPLICATION_JSON_VALUE;
        }

        @Override
        public String getHeader(String name) {
            if ("content-type".equalsIgnoreCase(name)) {
                return MediaType.APPLICATION_JSON_VALUE;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("content-type".equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(MediaType.APPLICATION_JSON_VALUE));
            }
            return super.getHeaders(name);
        }
    }
}
