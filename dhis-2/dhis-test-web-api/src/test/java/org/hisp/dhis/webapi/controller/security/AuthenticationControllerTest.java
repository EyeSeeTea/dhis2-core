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
package org.hisp.dhis.webapi.controller.security;

import static org.hisp.dhis.common.CodeGenerator.generateSecureRandomBytes;
import static org.hisp.dhis.security.twofa.TwoFactorAuthService.TWO_FACTOR_AUTH_REQUIRED_RESTRICTION_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.Cookie;
import org.hisp.dhis.security.twofa.TwoFactorAuthService;
import org.hisp.dhis.security.twofa.TwoFactorAuthService.Email2FACode;
import org.hisp.dhis.security.twofa.TwoFactorType;
import org.hisp.dhis.setting.SettingKey;
import org.hisp.dhis.setting.SystemSettingManager;
import org.hisp.dhis.user.User;
import org.hisp.dhis.user.UserDetails;
import org.hisp.dhis.user.UserRole;
import org.hisp.dhis.web.HttpStatus;
import org.hisp.dhis.webapi.DhisAuthenticationApiTest;
import org.hisp.dhis.webapi.json.domain.JsonLoginResponse;
import org.hisp.dhis.webapi.json.domain.JsonWebMessage;
import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.session.SessionRegistry;

/**
 * @author Morten Svanæs <msvanaes@dhis2.org>
 */
// @Slf4j
class AuthenticationControllerTest extends DhisAuthenticationApiTest {

  @Autowired SystemSettingManager systemSettingManager;
  @Autowired private SessionRegistry sessionRegistry;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void tearDown() {
    systemSettingManager.saveSystemSetting(SettingKey.LOCK_MULTIPLE_FAILED_LOGINS, false);
    systemSettingManager.saveSystemSetting(SettingKey.CREDENTIALS_EXPIRES, 0);
    // [SMS2FA] TODO: check the following, only present in newer
    // SystemSettingsService
    // settingsService.clearCurrentSettings();
    userService.invalidateAllSessions();
    clearSecurityContext();
  }

  @Test
  void testSuccessfulLogin() {
    JsonLoginResponse response =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("SUCCESS", response.getLoginStatus());
    assertEquals("/dhis-web-dashboard/", response.getRedirectUrl());
  }

  @Test
  void testLoginWithDeprecatedUsername() {
    User adminUser = userService.getUserByUsername("admin");
    adminUser.setUsername("Üsername");
    userService.updateUser(adminUser);
    JsonLoginResponse response =
        POST("/auth/login", "{'username':'Üsername','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("SUCCESS", response.getLoginStatus());
    assertEquals("/dhis-web-dashboard/", response.getRedirectUrl());
  }

  @Test
  void testWrongUsernameOrPassword() {
    User userA = createUserWithAuth("userb", "ALL");
    injectSecurityContextUser(userA);

    clearSecurityContext();
    JsonWebMessage response =
        POST("/auth/login", "{'username':'userb','password':'district9'}")
            .content(HttpStatus.UNAUTHORIZED)
            .as(JsonWebMessage.class);

    assertEquals("Bad credentials", response.getMessage());
    assertEquals("Unauthorized", response.getHttpStatus());
    assertEquals(401, response.getHttpStatusCode());
    assertEquals("ERROR", response.getStatus());
  }

  @Test
  void testLoginWithTOTP2FA() {
    User admin = userService.getUserByUsername("admin");
    String secret = Base32.encode(generateSecureRandomBytes(20));
    admin.setSecret(secret);
    admin.setTwoFactorType(TwoFactorType.TOTP_ENABLED);
    userService.updateUser(admin);

    JsonLoginResponse wrong2FaCodeResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("INCORRECT_TWO_FACTOR_CODE_TOTP", wrong2FaCodeResponse.getLoginStatus());
    Assertions.assertNull(wrong2FaCodeResponse.getRedirectUrl());

    Totp totp = new Totp(secret);
    String code = totp.now();
    loginWith2FACode(code);
  }

  @Test
  void testLoginWithTOTP2FAWithDeprecatedSecretLength() {
    User admin = userService.getUserByUsername("admin");
    String secret = Base32.encode(generateSecureRandomBytes(10));
    admin.setSecret(secret);
    admin.setTwoFactorType(TwoFactorType.TOTP_ENABLED);
    userService.updateUser(admin);

    JsonLoginResponse wrong2FaCodeResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("INCORRECT_TWO_FACTOR_CODE_TOTP", wrong2FaCodeResponse.getLoginStatus());
    Assertions.assertNull(wrong2FaCodeResponse.getRedirectUrl());

    Totp totp = new Totp(secret);
    String code = totp.now();
    loginWith2FACode(code);
  }

  @Test
  void testLoginWithTOTP2FAWithIncorrectSecretLength() {
    User admin = userService.getUserByUsername("admin");
    String secret = Base32.encode(generateSecureRandomBytes(15));
    admin.setSecret(secret);
    admin.setTwoFactorType(TwoFactorType.TOTP_ENABLED);
    userService.updateUser(admin);

    JsonLoginResponse wrong2FaCodeResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("INCORRECT_TWO_FACTOR_CODE_TOTP", wrong2FaCodeResponse.getLoginStatus());
    Assertions.assertNull(wrong2FaCodeResponse.getRedirectUrl());

    Totp totp = new Totp(secret);
    String code = totp.now();

    JsonLoginResponse ok2FaCodeResponse =
        POST(
                "/auth/login",
                "{'username':'admin','password':'district','twoFactorCode':'%s'}".formatted(code))
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);
    assertEquals("INCORRECT_TWO_FACTOR_CODE_TOTP", ok2FaCodeResponse.getLoginStatus());
  }

  @Test
  void testLoginEmail2FA() {
    User admin = userService.getUserByUsername("admin");
    String emailAddress = "valid.x@email.com";
    admin.setEmail(emailAddress);
    admin.setVerifiedEmail(emailAddress);
    Email2FACode email2FACode = TwoFactorAuthService.generateEmail2FACode();
    String secret = email2FACode.encodedCode();
    admin.setSecret(secret);
    admin.setTwoFactorType(TwoFactorType.EMAIL_ENABLED);
    userService.updateUser(admin);

    loginWith2FACode(email2FACode.code());
  }

  @Test
  void testLoginWith2FAEnrolmentOngoing() throws Exception {
    User userA = createUserWithAuth("usera", "ALL");
    injectSecurityContextUser(userA);

    // This will initiate TOTP 2FA enrolment.
    mvc.perform(
            get("/2fa/qrCode")
                .header("Authorization", "Basic dXNlcmE6ZGlzdHJpY3Q=")
                .contentType("application/octet-stream")
                .accept("application/octet-stream"))
        .andExpect(status().isAccepted());

    JsonLoginResponse loginResponse =
        POST("/auth/login", "{'username':'usera','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    // This means that the user can still log in as normal while the 2FA enrolment
    // is ongoing.
    assertEquals("SUCCESS", loginResponse.getLoginStatus());
    assertEquals("/dhis-web-dashboard/", loginResponse.getRedirectUrl());
  }

  @Test
  void testLoginWithLockedUser() {
    systemSettingManager.saveSystemSetting(SettingKey.LOCK_MULTIPLE_FAILED_LOGINS, true);

    User admin = userService.getUserByUsername("admin");
    userService.updateUser(admin);
    userService.registerFailedLogin(admin.getUsername());
    userService.registerFailedLogin(admin.getUsername());
    userService.registerFailedLogin(admin.getUsername());
    userService.registerFailedLogin(admin.getUsername());
    userService.registerFailedLogin(admin.getUsername());

    JsonLoginResponse loginResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("ACCOUNT_LOCKED", loginResponse.getLoginStatus());
    assertNull(loginResponse.getRedirectUrl());
  }

  @Test
  void testLoginWithDisabledUser() {
    User admin = userService.getUserByUsername("admin");
    admin.setDisabled(true);
    userService.updateUser(admin);

    JsonLoginResponse loginResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("ACCOUNT_DISABLED", loginResponse.getLoginStatus());
    assertNull(loginResponse.getRedirectUrl());
  }

  @Test
  void testLoginWithCredentialsExpiredUser() {
    systemSettingManager.saveSystemSetting(SettingKey.CREDENTIALS_EXPIRES, 1);

    User admin = userService.getUserByUsername("admin");

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(admin.getPasswordLastUpdated());
    calendar.add(Calendar.MONTH, -2);

    admin.setPasswordLastUpdated(calendar.getTime());
    userService.updateUser(admin);

    JsonLoginResponse loginResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("PASSWORD_EXPIRED", loginResponse.getLoginStatus());
    assertNull(loginResponse.getRedirectUrl());
  }

  @Test
  void testLoginWithAccountExpiredUser() {
    User admin = userService.getUserByUsername("admin");

    Calendar calendar = Calendar.getInstance();
    calendar.setTime(admin.getPasswordLastUpdated());
    calendar.add(Calendar.MONTH, -2);

    admin.setAccountExpiry(calendar.getTime());
    userService.updateUser(admin);

    JsonLoginResponse loginResponse =
        POST("/auth/login", "{'username':'admin','password':'district'}")
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);

    assertEquals("ACCOUNT_EXPIRED", loginResponse.getLoginStatus());
    assertNull(loginResponse.getRedirectUrl());
  }

  @Test
  void testSessionGetsCreated() {
    userService.invalidateAllSessions();

    HttpResponse response = POST("/auth/login", "{'username':'admin','password':'district'}");
    assertNotNull(response);

    assertEquals(1, sessionRegistry.getAllPrincipals().size());
    UserDetails actual = (UserDetails) sessionRegistry.getAllPrincipals().get(0);

    assertNotNull(actual);
    assertEquals("admin", actual.getUsername());
  }

  @Test
  void testLoginWith2FARestrictionAllowsOnlySetupEndpoints() throws Exception {
    User user = createUserWithAuth("requires2fa", "ALL");
    UserRole role = user.getUserRoles().iterator().next();
    role.setRestrictions(Set.of(TWO_FACTOR_AUTH_REQUIRED_RESTRICTION_NAME));
    userService.updateUserRole(role);

    MockHttpServletResponse loginResponse =
        mvc.perform(
                post("/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"requires2fa\",\"password\":\"district\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    Map<String, Object> loginBody =
        objectMapper.readValue(loginResponse.getContentAsString(), Map.class);
    assertEquals("REQUIRES_TWO_FACTOR_ENROLMENT", loginBody.get("loginStatus"));

    Cookie sessionCookie = loginResponse.getCookie("JSESSIONID");
    assertNotNull(sessionCookie);

    mvc.perform(get("/2fa/enabled").cookie(sessionCookie)).andExpect(status().isOk());

    MockHttpServletResponse blockedResponse =
        mvc.perform(get("/users").cookie(sessionCookie))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse();

    Map<String, Object> blockedBody =
        objectMapper.readValue(blockedResponse.getContentAsString(), Map.class);
    assertEquals("REQUIRES_TWO_FACTOR_ENROLMENT", blockedBody.get("loginStatus"));

    mvc.perform(post("/2fa/enrollTOTP2FA").cookie(sessionCookie)).andExpect(status().isOk());

    User userWithSecret = userService.getUserByUsername("requires2fa");
    String code = new Totp(userWithSecret.getSecret()).now();

    mvc.perform(
            post("/2fa/enable")
                .cookie(sessionCookie)
                .contentType("application/json")
                .content("{\"code\":\"%s\"}".formatted(code)))
        .andExpect(status().isOk());

    mvc.perform(get("/users").cookie(sessionCookie)).andExpect(status().isOk());
  }

  @Test
  void testLoginWith2FARestrictionRedirectsAppNavigationToLoginPage() throws Exception {
    User user = createUserWithAuth("requires2faredirect", "ALL");
    UserRole role = user.getUserRoles().iterator().next();
    role.setRestrictions(Set.of(TWO_FACTOR_AUTH_REQUIRED_RESTRICTION_NAME));
    userService.updateUserRole(role);

    MockHttpServletResponse loginResponse =
        mvc.perform(
                post("/auth/login")
                    .contentType("application/json")
                    .content("{\"username\":\"requires2faredirect\",\"password\":\"district\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    Cookie sessionCookie = loginResponse.getCookie("JSESSIONID");
    assertNotNull(sessionCookie);

    MockHttpServletResponse redirectResponse =
        mvc.perform(get("/apps/Homepage-App/").cookie(sessionCookie))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse();

    assertEquals("/dhis-web-login", redirectResponse.getRedirectedUrl());
  }

  @Test
  void testBasicAuthBlockedFor2FARestrictionUser() throws Exception {
    User user = createUserWithAuth("basicauth2fa", "ALL");
    UserRole role = user.getUserRoles().iterator().next();
    role.setRestrictions(Set.of(TWO_FACTOR_AUTH_REQUIRED_RESTRICTION_NAME));
    userService.updateUserRole(role);

    String basicAuth =
        "Basic " + java.util.Base64.getEncoder().encodeToString("basicauth2fa:district".getBytes());

    // API requests with Basic Auth should be blocked (403)
    MockHttpServletResponse blockedResponse =
        mvc.perform(get("/api/users").header("Authorization", basicAuth))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse();

    Map<String, Object> blockedBody =
        objectMapper.readValue(blockedResponse.getContentAsString(), Map.class);
    assertEquals("REQUIRES_TWO_FACTOR_ENROLMENT", blockedBody.get("loginStatus"));

    // Allowed endpoints should still work with Basic Auth
    mvc.perform(get("/api/me").header("Authorization", basicAuth)).andExpect(status().isOk());
  }

  private void loginWith2FACode(String code) {
    JsonLoginResponse ok2FaCodeResponse =
        POST(
                "/auth/login",
                "{'username':'admin','password':'district','twoFactorCode':'%s'}".formatted(code))
            .content(HttpStatus.OK)
            .as(JsonLoginResponse.class);
    assertEquals("SUCCESS", ok2FaCodeResponse.getLoginStatus());
    assertEquals("/dhis-web-dashboard/", ok2FaCodeResponse.getRedirectUrl());
  }
}
