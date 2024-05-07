/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.airbyte.commons.envvar.EnvVar;
import io.airbyte.config.storage.S3StorageConfig;
import io.airbyte.config.storage.StorageBucketConfig;
import io.airbyte.featureflag.TestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Tag("logger-client")
class S3LogsTest {

  private static final String REGION_STRING = "us-west-2";
  private static final Region REGION = Region.of(REGION_STRING);
  private static final String BUCKET_NAME = "airbyte-kube-integration-logging-test";

  private static final LogConfigs LOG_CONFIGS;

  static {
    final var bucketLog = EnvVar.STORAGE_BUCKET_LOG.fetch();
    Objects.requireNonNull(bucketLog);
    final var region = EnvVar.AWS_DEFAULT_REGION.fetch();
    Objects.requireNonNull(region);

    LOG_CONFIGS = new LogConfigs(new S3StorageConfig(
        new StorageBucketConfig(bucketLog, "state", "workload", "payload"),
        EnvVar.AWS_ACCESS_KEY_ID.fetch(),
        EnvVar.AWS_SECRET_ACCESS_KEY.fetch(),
        region));
  }

  private S3Client s3Client;

  @BeforeEach
  void setup() {
    s3Client = S3Client.builder().region(REGION).build();
    generatePaginateTestFiles();
  }

  /**
   * The test files here were generated by {@link #generatePaginateTestFiles()}.
   *
   * Generate enough files to force pagination and confirm all data is read.
   */
  @Test
  void testRetrieveAllLogs() throws IOException {
    final var data = S3Logs.getFile(s3Client, LOG_CONFIGS, "paginate", 6);

    final var retrieved = new ArrayList<String>();
    Files.lines(data.toPath()).forEach(retrieved::add);

    final var expected = List.of("Line 0", "Line 1", "Line 2", "Line 3", "Line 4", "Line 5", "Line 6", "Line 7", "Line 8");

    assertEquals(expected, retrieved);
  }

  /**
   * The test files for this test have been pre-generated and uploaded into the bucket folder. The
   * folder contains the following files with these contents:
   * <li>first-file.txt - Line 1, Line 2, Line 3</li>
   * <li>second-file.txt - Line 4, Line 5, Line 6</li>
   * <li>third-file.txt - Line 7, Line 8, Line 9</li>
   */
  @Test
  void testTail() throws IOException {
    final var data = new S3Logs(() -> s3Client).tailCloudLog(LOG_CONFIGS, "tail", 6, new TestClient());
    final var expected = List.of("Line 4", "Line 5", "Line 6", "Line 7", "Line 8", "Line 9");
    assertEquals(data, expected);
  }

  private void generatePaginateTestFiles() {
    for (int i = 0; i < 9; i++) {
      final var fileName = i + "-file";
      final var line = "Line " + i + "\n";
      final PutObjectRequest objectRequest = PutObjectRequest.builder()
          .bucket(BUCKET_NAME)
          .key("paginate/" + fileName)
          .build();

      s3Client.putObject(objectRequest, RequestBody.fromBytes(line.getBytes(StandardCharsets.UTF_8)));
    }
  }

}
