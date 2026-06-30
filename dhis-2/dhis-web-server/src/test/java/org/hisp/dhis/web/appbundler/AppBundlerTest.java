/*
 * Copyright (c) 2004-2025, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
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
package org.hisp.dhis.web.appbundler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.hisp.dhis.appmanager.AppBundleInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the AppBundler class. */
class AppBundlerTest {

  @TempDir Path tempDir;

  private File appListFile;
  private String buildDir;
  private String downloadDir;
  private String artifactId;

  @BeforeEach
  void setUp() throws IOException {
    // Create a temporary app list file with some different examples of valid app URLs
    appListFile = tempDir.resolve("test-apps.json").toFile();
    new ObjectMapper()
        .writeValue(
            appListFile,
            Arrays.asList(
                "https://github.com/d2-ci/import-export-app",
                "https://github.com/d2-ci/data-quality-app#patch/2.42.0",
                "https://github.com/d2-ci/dashboard-app#v101.1.6",
                "https://github.com/d2-ci/aggregate-data-entry-app#v101.0.5"));

    // Set up build directory
    buildDir = tempDir.resolve("build").toString();
    downloadDir = tempDir.resolve("download").toString();
    artifactId = "dhis-web-apps";
  }

  @Test
  void testAppBundlerCreatesCorrectStructure() throws IOException {
    // Execute the app bundler so we install the apps to our temp directory
    AppBundler bundler =
        new AppBundler(downloadDir, buildDir, artifactId, appListFile.getAbsolutePath(), "master");
    bundler.execute();

    Path buildDirPath = Path.of(buildDir).resolve(artifactId);
    assertTrue(Files.exists(buildDirPath), "Build directory should exist");

    Path downloadArtifactDir = Path.of(downloadDir).resolve(artifactId);
    assertTrue(Files.exists(downloadArtifactDir), "Artifact download directory should exist");

    Path etagsDir = downloadArtifactDir.resolve("etags");
    assertTrue(Files.exists(etagsDir), "Etags directory should exist");

    Path importExportAppZip = downloadArtifactDir.resolve("import-export-app.zip");
    assertTrue(Files.exists(importExportAppZip), "Import export app ZIP should exist");

    Path settingsAppZip = downloadArtifactDir.resolve("data-quality-app.zip");
    assertTrue(Files.exists(settingsAppZip), "Settings app ZIP should exist");

    Path dashboardAppZip = downloadArtifactDir.resolve("aggregate-data-entry-app.zip");
    assertTrue(Files.exists(dashboardAppZip), "Dashboard app ZIP should exist");

    Path dashboardAppZipInBuild = buildDirPath.resolve("dashboard-app.zip");
    assertTrue(Files.exists(dashboardAppZipInBuild), "Copied dashboard app ZIP should exist");

    Path bundleInfoFile = buildDirPath.resolve("apps-bundle.json");
    assertTrue(Files.exists(bundleInfoFile), "Bundle info file should exist");
  }

  @Test
  void testAppBundlerBundlesLocalApps() throws IOException {
    Path localAppDir = tempDir.resolve("login-app-local");
    Files.createDirectories(localAppDir.resolve("build").resolve("app"));

    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, String> packageJson = Map.of("name", "@dhis2/login-app", "version", "100.4.99");

    objectMapper.writeValue(localAppDir.resolve("package.json").toFile(), packageJson);
    objectMapper.writeValue(
        localAppDir.resolve("build").resolve("app").resolve("package.json").toFile(), packageJson);

    Files.writeString(
        localAppDir.resolve("build").resolve("app").resolve("manifest.webapp"),
        """
        {
          "version": "100.4.99",
          "name": "Local Login App",
          "launch_path": "/index.html",
          "activities": {
            "dhis": {
              "href": "index.html"
            }
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        localAppDir.resolve("build").resolve("app").resolve("BUILD_INFO"),
        """
        2025-08-08T10:00:00Z
        ignored
        https://example.org/local-login-app/commit/abc123
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        localAppDir.resolve("build").resolve("app").resolve("index.html"),
        "<html><body>Local login app</body></html>",
        StandardCharsets.UTF_8);

    objectMapper.writeValue(
        appListFile, List.of("local:" + localAppDir.toAbsolutePath().normalize()));

    AppBundler bundler =
        new AppBundler(downloadDir, buildDir, artifactId, appListFile.getAbsolutePath(), "master");
    bundler.execute();

    Path downloadZip = Path.of(downloadDir).resolve(artifactId).resolve("login-app.zip");
    Path buildZip = Path.of(buildDir).resolve(artifactId).resolve("login-app.zip");
    Path bundleInfoFile = Path.of(buildDir).resolve(artifactId).resolve("apps-bundle.json");

    assertTrue(Files.exists(downloadZip), "Local app ZIP should exist in the download directory");
    assertTrue(Files.exists(buildZip), "Local app ZIP should be copied to the build directory");
    assertTrue(Files.exists(bundleInfoFile), "Bundle info file should exist");

    try (ZipFile zipFile = new ZipFile(buildZip.toFile())) {
      assertTrue(
          zipFile.getEntry("login-app/manifest.webapp") != null,
          "ZIP should contain a top-level folder with manifest.webapp");
      assertTrue(
          zipFile.getEntry("login-app/package.json") != null,
          "ZIP should contain package.json for bundle metadata enrichment");
    }

    AppBundleInfo bundleInfo = objectMapper.readValue(bundleInfoFile.toFile(), AppBundleInfo.class);
    AppBundleInfo.BundledAppInfo bundledApp = bundleInfo.getApps().get(0);

    assertEquals("login-app", bundledApp.getName());
    assertEquals("local:" + localAppDir.toAbsolutePath().normalize(), bundledApp.getUrl());
    assertEquals("local", bundledApp.getBranch());
    assertEquals("100.4.99", bundledApp.getVersion());
    assertEquals("2025-08-08T10:00:00Z", bundledApp.getBuildDate());
    assertEquals("https://example.org/local-login-app/commit/abc123", bundledApp.getCommitUrl());
  }

  @Test
  void testAppBundlerBundlesLocalAppsAndReadsVersionFromManifestWebapp() throws IOException {
    Path localAppDir = tempDir.resolve("user-profile-app-local");
    Files.createDirectories(localAppDir.resolve("build").resolve("app"));

    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, String> packageJson =
        Map.of("name", "user-profile-app", "version", "100.8.99-widp-fork-1");

    objectMapper.writeValue(localAppDir.resolve("package.json").toFile(), packageJson);

    Files.writeString(
        localAppDir.resolve("build").resolve("app").resolve("manifest.webapp"),
        """
        {
          "version": "100.8.99-widp-fork-1",
          "name": "Local User Profile App",
          "launch_path": "/index.html",
          "activities": {
            "dhis": {
              "href": "index.html"
            }
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        localAppDir.resolve("build").resolve("app").resolve("index.html"),
        "<html><body>Local user profile app</body></html>",
        StandardCharsets.UTF_8);

    objectMapper.writeValue(
        appListFile, List.of("local:" + localAppDir.toAbsolutePath().normalize()));

    AppBundler bundler =
        new AppBundler(downloadDir, buildDir, artifactId, appListFile.getAbsolutePath(), "master");
    bundler.execute();

    Path bundleInfoFile = Path.of(buildDir).resolve(artifactId).resolve("apps-bundle.json");

    AppBundleInfo bundleInfo = objectMapper.readValue(bundleInfoFile.toFile(), AppBundleInfo.class);
    AppBundleInfo.BundledAppInfo bundledApp = bundleInfo.getApps().get(0);

    assertEquals("user-profile-app", bundledApp.getName());
    assertEquals("100.8.99-widp-fork-1", bundledApp.getVersion());
  }
}
