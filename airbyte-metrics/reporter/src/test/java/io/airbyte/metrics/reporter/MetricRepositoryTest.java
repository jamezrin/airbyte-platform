/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.metrics.reporter;

import static io.airbyte.config.persistence.OrganizationPersistence.DEFAULT_ORGANIZATION_ID;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.CONNECTION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.WORKSPACE;
import static io.airbyte.db.instance.jobs.jooq.generated.Tables.ATTEMPTS;
import static io.airbyte.db.instance.jobs.jooq.generated.Tables.JOBS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airbyte.db.instance.configs.jooq.generated.enums.ActorType;
import io.airbyte.db.instance.configs.jooq.generated.enums.GeographyType;
import io.airbyte.db.instance.configs.jooq.generated.enums.NamespaceDefinitionType;
import io.airbyte.db.instance.configs.jooq.generated.enums.StatusType;
import io.airbyte.db.instance.jobs.jooq.generated.enums.AttemptStatus;
import io.airbyte.db.instance.jobs.jooq.generated.enums.JobConfigType;
import io.airbyte.db.instance.jobs.jooq.generated.enums.JobStatus;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
abstract class MetricRepositoryTest {

  private static final String SRC = "src";
  private static final String DEST = "dst";
  private static final String CONN = "conn";
  private static final String SYNC_QUEUE = "SYNC";
  private static final String AWS_SYNC_QUEUE = "AWS_PARIS_SYNC";
  private static final String AUTO_REGION = "AUTO";
  private static final String EU_REGION = "EU";

  protected static final UUID SRC_DEF_ID = UUID.randomUUID();
  protected static final UUID DST_DEF_ID = UUID.randomUUID();
  protected static final UUID SRC_DEF_VER_ID = UUID.randomUUID();
  protected static final UUID DST_DEF_VER_ID = UUID.randomUUID();
  protected static MetricRepository db;
  protected static DSLContext ctx;

  @BeforeEach
  void setUp() {
    ctx.truncate(ACTOR).execute();
    ctx.truncate(CONNECTION).cascade().execute();
    ctx.truncate(JOBS).cascade().execute();
    ctx.truncate(ATTEMPTS).cascade().execute();
    ctx.truncate(WORKSPACE).cascade().execute();
  }

  @Nested
  class NumJobs {

    @Test
    void shouldReturnReleaseStages() {
      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.PROCESSING_TASK_QUEUE)
          .values(10L, 1L, AttemptStatus.running, SYNC_QUEUE).values(20L, 2L, AttemptStatus.running, SYNC_QUEUE)
          .values(30L, 3L, AttemptStatus.running, SYNC_QUEUE).values(40L, 4L, AttemptStatus.running, AWS_SYNC_QUEUE)
          .values(50L, 5L, AttemptStatus.running, SYNC_QUEUE)
          .execute();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      final var activeConnectionId = UUID.randomUUID();
      final var inactiveConnectionId = UUID.randomUUID();
      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.STATUS, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID,
          CONNECTION.DESTINATION_ID, CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL)
          .values(activeConnectionId, StatusType.active, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true)
          .values(inactiveConnectionId, StatusType.inactive, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true)
          .execute();

      // non-pending jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, activeConnectionId.toString(), JobStatus.pending)
          .execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(2L, activeConnectionId.toString(), JobStatus.failed)
          .values(3L, activeConnectionId.toString(), JobStatus.running)
          .values(4L, activeConnectionId.toString(), JobStatus.running)
          .values(5L, inactiveConnectionId.toString(), JobStatus.running)
          .execute();

      assertEquals(1, db.numberOfRunningJobsByTaskQueue().get(SYNC_QUEUE));
      assertEquals(1, db.numberOfRunningJobsByTaskQueue().get(AWS_SYNC_QUEUE));
      // To test we send 0 for 'null' to overwrite previous bug.
      assertEquals(0, db.numberOfRunningJobsByTaskQueue().get("null"));
      assertEquals(1, db.numberOfOrphanRunningJobs());
    }

    @Test
    void runningJobsShouldReturnZero() throws SQLException {
      // non-pending jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS).values(1L, "", JobStatus.pending).execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS).values(2L, "", JobStatus.failed).execute();

      final var result = db.numberOfRunningJobsByTaskQueue();
      assertEquals(result.get(SYNC_QUEUE), 0);
      assertEquals(result.get(AWS_SYNC_QUEUE), 0);
    }

    @Test
    void pendingJobsShouldReturnCorrectCount() throws SQLException {
      // non-pending jobs
      final var connectionUuid = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.GEOGRAPHY)
          .values(connectionUuid, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active,
              GeographyType.valueOf(EU_REGION))
          .execute();

      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, connectionUuid.toString(), JobStatus.pending)
          .values(2L, connectionUuid.toString(), JobStatus.failed)
          .values(3L, connectionUuid.toString(), JobStatus.pending)
          .values(4L, connectionUuid.toString(), JobStatus.running)
          .execute();

      final var res = db.numberOfPendingJobsByGeography();
      assertEquals(2, res.get(EU_REGION));
      assertEquals(0, res.get(AUTO_REGION));
    }

    @Test
    void pendingJobsShouldReturnZero() throws SQLException {
      final var connectionUuid = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.GEOGRAPHY)
          .values(connectionUuid, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active,
              GeographyType.valueOf(EU_REGION))
          .execute();

      // non-pending jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, connectionUuid.toString(), JobStatus.running)
          .values(2L, connectionUuid.toString(), JobStatus.failed)
          .execute();

      final var result = db.numberOfPendingJobsByGeography();
      assertEquals(result.get(AUTO_REGION), 0);
      assertEquals(result.get(EU_REGION), 0);
    }

  }

  @Nested
  class OldestPendingJob {

    @Test
    void shouldReturnOnlyPendingSeconds() throws SQLException {
      final var expAgeSecs = 1000;
      final var oldestCreateAt = OffsetDateTime.now().minus(expAgeSecs, ChronoUnit.SECONDS);
      final var connectionUuid = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.GEOGRAPHY)
          .values(connectionUuid, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active,
              GeographyType.valueOf(EU_REGION))
          .execute();

      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT)
          // oldest pending job
          .values(1L, connectionUuid.toString(), JobStatus.pending, oldestCreateAt)
          // second-oldest pending job
          .values(2L, connectionUuid.toString(), JobStatus.pending, OffsetDateTime.now())
          .execute();
      // non-pending jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(3L, connectionUuid.toString(), JobStatus.running)
          .values(4L, connectionUuid.toString(), JobStatus.failed)
          .execute();

      final Double result = db.oldestPendingJobAgeSecsByGeography().get(EU_REGION);
      // expected age is 1000 seconds, but allow for +/- 1 second to account for timing/rounding errors
      assertTrue(999 < result && result < 1001);
    }

    @Test
    void shouldReturnNothingIfNotApplicable() {
      final var connectionUuid = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.GEOGRAPHY)
          .values(connectionUuid, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active, GeographyType.EU)
          .execute();

      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, connectionUuid.toString(), JobStatus.succeeded)
          .values(2L, connectionUuid.toString(), JobStatus.running)
          .values(3L, connectionUuid.toString(), JobStatus.failed).execute();

      final var result = db.oldestPendingJobAgeSecsByGeography();
      assertEquals(result.get(EU_REGION), 0.0);
      assertEquals(result.get(AUTO_REGION), 0.0);
    }

  }

  @Nested
  class OldestRunningJob {

    @Test
    void shouldReturnOnlyRunningSeconds() {
      final var expAgeSecs = 10000;
      final var oldestCreateAt = OffsetDateTime.now().minus(expAgeSecs, ChronoUnit.SECONDS);
      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.PROCESSING_TASK_QUEUE)
          .values(10L, 1L, AttemptStatus.running, SYNC_QUEUE).values(20L, 2L, AttemptStatus.running, SYNC_QUEUE)
          .execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT)
          // oldest pending job
          .values(1L, "", JobStatus.running, oldestCreateAt)
          // second-oldest pending job
          .values(2L, "", JobStatus.running, OffsetDateTime.now())
          .execute();

      // non-pending jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(3L, "", JobStatus.pending)
          .values(4L, "", JobStatus.failed)
          .execute();

      final var result = db.oldestRunningJobAgeSecsByTaskQueue();
      // expected age is 1000 seconds, but allow for +/- 1 second to account for timing/rounding errors
      assertTrue(9999 < result.get(SYNC_QUEUE) && result.get(SYNC_QUEUE) < 10001L);
      assertEquals(result.get(AWS_SYNC_QUEUE), 0.0);
    }

    @Test
    void shouldReturnNothingIfNotApplicable() {
      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.PROCESSING_TASK_QUEUE).values(10L, 1L, SYNC_QUEUE).values(20L, 2L, SYNC_QUEUE)
          .values(30L, 3L, SYNC_QUEUE).execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, "", JobStatus.succeeded)
          .values(2L, "", JobStatus.pending)
          .values(3L, "", JobStatus.failed)
          .execute();

      final var result = db.oldestRunningJobAgeSecsByTaskQueue();
      assertEquals(result.get(SYNC_QUEUE), 0.0);
      assertEquals(result.get(AWS_SYNC_QUEUE), 0.0);
    }

  }

  @Nested
  class NumActiveConnsPerWorkspace {

    @Test
    void shouldReturnNumConnectionsBasic() {
      final var workspaceId = UUID.randomUUID();
      ctx.insertInto(WORKSPACE, WORKSPACE.ID, WORKSPACE.NAME, WORKSPACE.TOMBSTONE, WORKSPACE.ORGANIZATION_ID)
          .values(workspaceId, "test-0", false, DEFAULT_ORGANIZATION_ID)
          .execute();

      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      ctx.insertInto(ACTOR, ACTOR.ID, ACTOR.WORKSPACE_ID, ACTOR.ACTOR_DEFINITION_ID, ACTOR.NAME, ACTOR.CONFIGURATION,
          ACTOR.ACTOR_TYPE,
          ACTOR.TOMBSTONE)
          .values(srcId, workspaceId, SRC_DEF_ID, SRC, JSONB.valueOf("{}"), ActorType.source, false)
          .values(dstId, workspaceId, DST_DEF_ID, DEST, JSONB.valueOf("{}"), ActorType.destination, false)
          .execute();

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active)
          .execute();

      final var res = db.numberOfActiveConnPerWorkspace();
      assertEquals(1, res.size());
      assertEquals(2, res.get(0));
    }

    @Test
    @DisplayName("should ignore deleted connections")
    void shouldIgnoreNonRunningConnections() {
      final var workspaceId = UUID.randomUUID();
      ctx.insertInto(WORKSPACE, WORKSPACE.ID, WORKSPACE.NAME, WORKSPACE.TOMBSTONE, WORKSPACE.ORGANIZATION_ID)
          .values(workspaceId, "test-0", false, DEFAULT_ORGANIZATION_ID)
          .execute();

      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      ctx.insertInto(ACTOR, ACTOR.ID, ACTOR.WORKSPACE_ID, ACTOR.ACTOR_DEFINITION_ID, ACTOR.NAME, ACTOR.CONFIGURATION,
          ACTOR.ACTOR_TYPE,
          ACTOR.TOMBSTONE)
          .values(srcId, workspaceId, SRC_DEF_ID, SRC, JSONB.valueOf("{}"), ActorType.source, false)
          .values(dstId, workspaceId, DST_DEF_ID, DEST, JSONB.valueOf("{}"), ActorType.destination, false)
          .execute();

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.deprecated)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.inactive)
          .execute();

      final var res = db.numberOfActiveConnPerWorkspace();
      assertEquals(1, res.size());
      assertEquals(2, res.get(0));
    }

    @Test
    @DisplayName("should ignore deleted connections")
    void shouldIgnoreDeletedWorkspaces() {
      final var workspaceId = UUID.randomUUID();
      ctx.insertInto(WORKSPACE, WORKSPACE.ID, WORKSPACE.NAME, WORKSPACE.TOMBSTONE, WORKSPACE.ORGANIZATION_ID)
          .values(workspaceId, "test-0", true, DEFAULT_ORGANIZATION_ID)
          .execute();

      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      ctx.insertInto(ACTOR, ACTOR.ID, ACTOR.WORKSPACE_ID, ACTOR.ACTOR_DEFINITION_ID, ACTOR.NAME, ACTOR.CONFIGURATION,
          ACTOR.ACTOR_TYPE,
          ACTOR.TOMBSTONE)
          .values(srcId, workspaceId, SRC_DEF_ID, SRC, JSONB.valueOf("{}"), ActorType.source, false)
          .values(dstId, workspaceId, DST_DEF_ID, DEST, JSONB.valueOf("{}"), ActorType.destination, false)
          .execute();

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.MANUAL, CONNECTION.STATUS)
          .values(UUID.randomUUID(), NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"), true, StatusType.active)
          .execute();

      final var res = db.numberOfActiveConnPerWorkspace();
      assertEquals(0, res.size());
    }

    @Test
    void shouldReturnNothingIfNotApplicable() {
      final var res = db.numberOfActiveConnPerWorkspace();
      assertEquals(0, res.size());
    }

  }

  @Nested
  class OverallJobRuntimeForTerminalJobsInLastHour {

    @Test
    void shouldIgnoreNonTerminalJobs() throws SQLException {
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS)
          .values(1L, "", JobStatus.running)
          .values(2L, "", JobStatus.incomplete)
          .values(3L, "", JobStatus.pending)
          .execute();

      final var res = db.overallJobRuntimeForTerminalJobsInLastHour();
      assertEquals(0, res.size());
    }

    @Test
    void shouldIgnoreJobsOlderThan1Hour() {
      final var updateAt = OffsetDateTime.now().minus(2, ChronoUnit.HOURS);
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.UPDATED_AT).values(1L, "", JobStatus.succeeded, updateAt).execute();

      final var res = db.overallJobRuntimeForTerminalJobsInLastHour();
      assertEquals(0, res.size());
    }

    @Test
    @DisplayName("should return correct duration for terminal jobs")
    void shouldReturnTerminalJobs() {
      final var updateAt = OffsetDateTime.now();
      final var expAgeSecs = 10000;
      final var createAt = updateAt.minus(expAgeSecs, ChronoUnit.SECONDS);

      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(1L, "", JobStatus.succeeded, createAt, updateAt)
          .execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(2L, "", JobStatus.failed, createAt, updateAt)
          .execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(3L, "", JobStatus.cancelled, createAt, updateAt)
          .execute();

      final var res = db.overallJobRuntimeForTerminalJobsInLastHour();
      assertEquals(3, res.size());

      final var exp = Map.of(
          JobStatus.succeeded, expAgeSecs * 1.0,
          JobStatus.cancelled, expAgeSecs * 1.0,
          JobStatus.failed, expAgeSecs * 1.0);
      assertEquals(exp, res);
    }

    @Test
    void shouldReturnTerminalJobsComplex() {
      final var updateAtNow = OffsetDateTime.now();
      final var expAgeSecs = 10000;
      final var createAt = updateAtNow.minus(expAgeSecs, ChronoUnit.SECONDS);

      // terminal jobs in last hour
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(1L, "", JobStatus.succeeded, createAt, updateAtNow)
          .execute();
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(2L, "", JobStatus.failed, createAt, updateAtNow)
          .execute();

      // old terminal jobs
      final var updateAtOld = OffsetDateTime.now().minus(2, ChronoUnit.HOURS);
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT)
          .values(3L, "", JobStatus.cancelled, createAt, updateAtOld)
          .execute();

      // non-terminal jobs
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT)
          .values(4L, "", JobStatus.running, createAt)
          .execute();

      final var res = db.overallJobRuntimeForTerminalJobsInLastHour();
      assertEquals(2, res.size());

      final var exp = Map.of(
          JobStatus.succeeded, expAgeSecs * 1.0,
          JobStatus.failed, expAgeSecs * 1.0);
      assertEquals(exp, res);
    }

    @Test
    void shouldReturnNothingIfNotApplicable() {
      final var res = db.overallJobRuntimeForTerminalJobsInLastHour();
      assertEquals(0, res.size());
    }

  }

  @Nested
  class AbnormalJobsInLastDay {

    @Test
    void shouldCountInJobsWithMissingRun() throws SQLException {
      final var updateAt = OffsetDateTime.now().minus(300, ChronoUnit.HOURS);
      final var connectionId = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.SCHEDULE, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.CREATED_AT,
          CONNECTION.UPDATED_AT)
          .values(connectionId, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"),
              JSONB.valueOf("{\"units\": 6, \"timeUnit\": \"hours\"}"), false, StatusType.active, updateAt, updateAt)
          .execute();

      // Jobs running in prior day will not be counted
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE)
          .values(100L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS), updateAt, syncConfigType)
          .values(1L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS), updateAt, syncConfigType)
          .values(2L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(10, ChronoUnit.HOURS), updateAt, syncConfigType)
          .values(3L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(5, ChronoUnit.HOURS), updateAt, syncConfigType)
          .execute();

      final var totalConnectionResult = db.numScheduledActiveConnectionsInLastDay();
      assertEquals(1, totalConnectionResult);

      final var abnormalConnectionResult = db.numberOfJobsNotRunningOnScheduleInLastDay();
      assertEquals(1, abnormalConnectionResult);
    }

    @Test
    void shouldNotCountNormalJobsInAbnormalMetric() {
      final var updateAt = OffsetDateTime.now().minus(300, ChronoUnit.HOURS);
      final var inactiveConnectionId = UUID.randomUUID();
      final var activeConnectionId = UUID.randomUUID();
      final var srcId = UUID.randomUUID();
      final var dstId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;

      ctx.insertInto(CONNECTION, CONNECTION.ID, CONNECTION.NAMESPACE_DEFINITION, CONNECTION.SOURCE_ID, CONNECTION.DESTINATION_ID,
          CONNECTION.NAME, CONNECTION.CATALOG, CONNECTION.SCHEDULE, CONNECTION.MANUAL, CONNECTION.STATUS, CONNECTION.CREATED_AT,
          CONNECTION.UPDATED_AT)
          .values(inactiveConnectionId, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"),
              JSONB.valueOf("{\"units\": 12, \"timeUnit\": \"hours\"}"), false, StatusType.inactive, updateAt, updateAt)
          .values(activeConnectionId, NamespaceDefinitionType.source, srcId, dstId, CONN, JSONB.valueOf("{}"),
              JSONB.valueOf("{\"units\": 12, \"timeUnit\": \"hours\"}"), false, StatusType.active, updateAt, updateAt)
          .execute();

      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE)
          .values(1L, activeConnectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS), updateAt,
              syncConfigType)
          .values(2L, activeConnectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(10, ChronoUnit.HOURS), updateAt,
              syncConfigType)
          .execute();

      final var totalConnectionResult = db.numScheduledActiveConnectionsInLastDay();
      assertEquals(1, totalConnectionResult);

      final var abnormalConnectionResult = db.numberOfJobsNotRunningOnScheduleInLastDay();
      assertEquals(0, abnormalConnectionResult);
    }

  }

  @Nested
  class UnusuallyLongJobs {

    @Test
    void shouldCountJobsWithUnusuallyLongTime() throws SQLException {
      final var connectionId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;
      final var config = JSONB.valueOf("""
                                       {
                                        "sync": {
                                           "sourceDockerImage": "airbyte/source-postgres-1.1.0",
                                           "destinationDockerImage": "airbyte/destination-s3-1.4.0",
                                           "workspaceId": "81249e08-f71c-4743-98da-ed3c6c893132"
                                         }
                                       }
                                       """);

      // Current job has been running for 12 hours while the previous 5 jobs runs 2 hours. Avg will be 2
      // hours.
      // Thus latest job will be counted as an unusually long-running job.
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE, JOBS.CONFIG)
          .values(100L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS), syncConfigType, config)
          .values(1L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS), syncConfigType, config)
          .values(2L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(16, ChronoUnit.HOURS), syncConfigType, config)
          .values(3L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(16, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(14, ChronoUnit.HOURS), syncConfigType, config)
          .values(4L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(14, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS), syncConfigType, config)
          .values(5L, connectionId.toString(), JobStatus.running, OffsetDateTime.now().minus(12, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS), syncConfigType, config)
          .execute();

      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.CREATED_AT, ATTEMPTS.UPDATED_AT)
          .values(100L, 100L, AttemptStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS))
          .values(1L, 1L, AttemptStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS))
          .values(2L, 2L, AttemptStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(16, ChronoUnit.HOURS))
          .values(3L, 3L, AttemptStatus.succeeded, OffsetDateTime.now().minus(16, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(14, ChronoUnit.HOURS))
          .values(4L, 4L, AttemptStatus.succeeded, OffsetDateTime.now().minus(14, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS))
          .values(5L, 5L, AttemptStatus.running, OffsetDateTime.now().minus(12, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS))
          .execute();

      final var longRunningJobs = db.unusuallyLongRunningJobs();
      assertEquals(1, longRunningJobs.size());
      final var job = longRunningJobs.get(0);
      assertEquals("airbyte/source-postgres-1.1.0", job.sourceDockerImage());
      assertEquals("airbyte/destination-s3-1.4.0", job.destinationDockerImage());
      assertEquals(connectionId.toString(), job.connectionId());
    }

    @Test
    void handlesNullConfigRows() throws SQLException {
      final var connectionId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;

      // same as above but no value passed for `config`
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE)
          .values(100L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS), syncConfigType)
          .values(1L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS), syncConfigType)
          .values(2L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(16, ChronoUnit.HOURS), syncConfigType)
          .values(3L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(16, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(14, ChronoUnit.HOURS), syncConfigType)
          .values(4L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(14, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS), syncConfigType)
          .values(5L, connectionId.toString(), JobStatus.running, OffsetDateTime.now().minus(12, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS), syncConfigType)
          .execute();

      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.CREATED_AT, ATTEMPTS.UPDATED_AT)
          .values(100L, 100L, AttemptStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS))
          .values(1L, 1L, AttemptStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS))
          .values(2L, 2L, AttemptStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(16, ChronoUnit.HOURS))
          .values(3L, 3L, AttemptStatus.succeeded, OffsetDateTime.now().minus(16, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(14, ChronoUnit.HOURS))
          .values(4L, 4L, AttemptStatus.succeeded, OffsetDateTime.now().minus(14, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS))
          .values(5L, 5L, AttemptStatus.running, OffsetDateTime.now().minus(12, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(12, ChronoUnit.HOURS))
          .execute();

      final var longRunningJobs = db.unusuallyLongRunningJobs();
      assertEquals(1, longRunningJobs.size());
    }

    @Test
    void shouldNotCountInJobsWithinFifteenMinutes() throws SQLException {
      final var connectionId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;

      // Latest job runs 14 minutes while the previous 5 jobs runs average about 3 minutes.
      // Despite it has been more than 2x than avg it's still within 15 minutes threshold, thus this
      // shouldn't be
      // counted in.
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE)
          .values(100L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(26, ChronoUnit.MINUTES), syncConfigType)
          .values(1L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(18, ChronoUnit.MINUTES), syncConfigType)
          .values(2L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(16, ChronoUnit.MINUTES), syncConfigType)
          .values(3L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(16, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(14, ChronoUnit.MINUTES), syncConfigType)
          .values(4L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(14, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(2, ChronoUnit.MINUTES), syncConfigType)
          .values(5L, connectionId.toString(), JobStatus.running, OffsetDateTime.now().minus(14, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(2, ChronoUnit.MINUTES), syncConfigType)
          .execute();

      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.CREATED_AT, ATTEMPTS.UPDATED_AT)
          .values(100L, 100L, AttemptStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(26, ChronoUnit.MINUTES))
          .values(1L, 1L, AttemptStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(18, ChronoUnit.MINUTES))
          .values(2L, 2L, AttemptStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(16, ChronoUnit.MINUTES))
          .values(3L, 3L, AttemptStatus.succeeded, OffsetDateTime.now().minus(26, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(14, ChronoUnit.MINUTES))
          .values(4L, 4L, AttemptStatus.succeeded, OffsetDateTime.now().minus(18, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(17, ChronoUnit.MINUTES))
          .values(5L, 5L, AttemptStatus.running, OffsetDateTime.now().minus(14, ChronoUnit.MINUTES),
              OffsetDateTime.now().minus(14, ChronoUnit.MINUTES))
          .execute();

      final var numOfUnusuallyLongRunningJobs = db.unusuallyLongRunningJobs().size();
      assertEquals(0, numOfUnusuallyLongRunningJobs);
    }

    @Test
    void shouldSkipInsufficientJobRuns() throws SQLException {
      final var connectionId = UUID.randomUUID();
      final var syncConfigType = JobConfigType.sync;

      // Require at least 5 runs in last week to get meaningful average runtime.
      ctx.insertInto(JOBS, JOBS.ID, JOBS.SCOPE, JOBS.STATUS, JOBS.CREATED_AT, JOBS.UPDATED_AT, JOBS.CONFIG_TYPE)
          .values(100L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS), syncConfigType)
          .values(1L, connectionId.toString(), JobStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS), syncConfigType)
          .values(2L, connectionId.toString(), JobStatus.running, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(1, ChronoUnit.HOURS), syncConfigType)
          .execute();

      ctx.insertInto(ATTEMPTS, ATTEMPTS.ID, ATTEMPTS.JOB_ID, ATTEMPTS.STATUS, ATTEMPTS.CREATED_AT, ATTEMPTS.UPDATED_AT)
          .values(100L, 100L, AttemptStatus.succeeded, OffsetDateTime.now().minus(28, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(26, ChronoUnit.HOURS))
          .values(1L, 1L, AttemptStatus.succeeded, OffsetDateTime.now().minus(20, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(18, ChronoUnit.HOURS))
          .values(2L, 2L, AttemptStatus.running, OffsetDateTime.now().minus(18, ChronoUnit.HOURS),
              OffsetDateTime.now().minus(1, ChronoUnit.HOURS))
          .execute();

      final var numOfUnusuallyLongRunningJobs = db.unusuallyLongRunningJobs().size();
      assertEquals(0, numOfUnusuallyLongRunningJobs);
    }

  }

}
