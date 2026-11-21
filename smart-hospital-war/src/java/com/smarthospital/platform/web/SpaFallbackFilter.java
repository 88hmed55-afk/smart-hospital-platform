package com.smarthospital.platform.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Serves the React single-page application for client-side routes.
 *
 * <p>Requests under {@code /app/*} and the standalone {@code /login} route are
 * handled by React Router. A direct navigation or a refresh must therefore
 * return {@code index.html} with a {@code 200} status instead of a container
 * {@code 404}. API calls live under {@code /api/*} and are deliberately
 * excluded.
 */
@WebFilter(filterName = "spaFallbackFilter", urlPatterns = {"/app/*", "/login"})
public class SpaFallbackFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if ("GET".equalsIgnoreCase(httpRequest.getMethod())) {
            httpRequest.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
