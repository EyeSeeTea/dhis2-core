/*
 * Copyright (c) 2004-2024, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.webapi.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.hisp.dhis.webapi.controller.security.LoginResponse;
import org.hisp.dhis.webapi.controller.security.TwoFactorSetupSessionAccess;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class TwoFactorSetupRestrictionFilter extends OncePerRequestFilter {
  private static final String LOGIN_PAGE_PATH = "/dhis-web-login";

  private final ObjectMapper objectMapper;
  private final List<RequestMatcher> allowedRequests;
  private final List<RequestMatcher> blockedRequests;

  public TwoFactorSetupRestrictionFilter(String apiContextPath, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.blockedRequests =
        List.of(
            new AntPathRequestMatcher(apiContextPath + "/**/2fa/enabled", HttpMethod.GET.name()));
    this.allowedRequests =
        List.of(
            new AntPathRequestMatcher(apiContextPath + "/**/auth/login", HttpMethod.POST.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/loginConfig", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/2fa/**"),
            new AntPathRequestMatcher(apiContextPath + "/**/account/verifyEmail"),
            new AntPathRequestMatcher(apiContextPath + "/system/info", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/system/info", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/userSettings", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/me", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/me", HttpMethod.PUT.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/me/authorization", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/me/dashboard", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/systemSettings", HttpMethod.GET.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/systemSettings/**", HttpMethod.GET.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/staticContent/logo_banner", HttpMethod.GET.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/staticContent/logo_front.png", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/schemas", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/attributes", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/apps", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/system/styles", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/locales/ui", HttpMethod.GET.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/locales/db", HttpMethod.GET.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/configuration/twoFactorMethods", HttpMethod.GET.name()),
            new AntPathRequestMatcher(
                apiContextPath + "/**/account/sendEmailVerification", HttpMethod.POST.name()),
            new AntPathRequestMatcher(apiContextPath + "/**/auth/logout"),
            new AntPathRequestMatcher("/dhis-web-login/**"),
            new AntPathRequestMatcher("/dhis-web-commons/**"),
            new AntPathRequestMatcher("/dhis-web-user-profile/**"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (HttpMethod.OPTIONS.matches(request.getMethod())
        || !TwoFactorSetupSessionAccess.isRequired(request)
        || isAllowed(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    if (isAppNavigation(request)) {
      response.sendRedirect(request.getContextPath() + LOGIN_PAGE_PATH);
      return;
    }

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(),
        LoginResponse.builder()
            .loginStatus(LoginResponse.STATUS.REQUIRES_TWO_FACTOR_ENROLMENT)
            .build());
  }

  private boolean isAllowed(HttpServletRequest request) {
    return blockedRequests.stream().noneMatch(pattern -> pattern.matches(request))
        && allowedRequests.stream().anyMatch(pattern -> pattern.matches(request));
  }

  private boolean isAppNavigation(HttpServletRequest request) {
    String requestURI = request.getRequestURI();
    String contextPath = request.getContextPath();
    String path = requestURI.substring(contextPath.length());

    // Don't redirect API requests (they get 403 JSON response)
    if (path.startsWith("/api/") && !path.startsWith("/api/apps/")) {
      return false;
    }

    // Redirect all other non-API requests (web pages, core apps, bundled apps, etc.)
    return true;
  }
}
