/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.event.EventBus;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.configuration.CircuitBreakerConfiguration;
import org.whispersystems.textsecuregcm.configuration.RetryConfiguration;
import reactor.core.publisher.Flux;

class FaultTolerantRedisClusterTest {

  private RedisAdvancedClusterCommands<String, String> clusterCommands;
  private FaultTolerantRedisCluster faultTolerantCluster;

  @SuppressWarnings("unchecked")
  @BeforeEach
  public void setUp() {
    final RedisClusterClient clusterClient = mock(RedisClusterClient.class);
    final StatefulRedisClusterConnection<String, String> clusterConnection = mock(StatefulRedisClusterConnection.class);
    final StatefulRedisClusterPubSubConnection<String, String> pubSubConnection = mock(
        StatefulRedisClusterPubSubConnection.class);
    final ClientResources clientResources = mock(ClientResources.class);
    final EventBus eventBus = mock(EventBus.class);

    clusterCommands = mock(RedisAdvancedClusterCommands.class);

    when(clusterClient.connect()).thenReturn(clusterConnection);
    when(clusterClient.connectPubSub()).thenReturn(pubSubConnection);
    when(clusterClient.getResources()).thenReturn(clientResources);
    when(clusterConnection.sync()).thenReturn(clusterCommands);
    when(clientResources.eventBus()).thenReturn(eventBus);
    when(eventBus.get()).thenReturn(mock(Flux.class));

    final CircuitBreakerConfiguration breakerConfiguration = new CircuitBreakerConfiguration();
    breakerConfiguration.setFailureRateThreshold(100);
    breakerConfiguration.setSlidingWindowSize(1);
    breakerConfiguration.setSlidingWindowMinimumNumberOfCalls(1);
    breakerConfiguration.setWaitDurationInOpenState(Duration.ofSeconds(Integer.MAX_VALUE));

    final RetryConfiguration retryConfiguration = new RetryConfiguration();
    retryConfiguration.setMaxAttempts(3);
    retryConfiguration.setWaitDuration(0);

    faultTolerantCluster = new ClusterFaultTolerantRedisCluster("test", clusterClient, Duration.ofSeconds(2),
        breakerConfiguration, retryConfiguration);
  }

  @Test
  void testBreaker() {
    when(clusterCommands.get(anyString()))
        .thenReturn("value")
        .thenThrow(new RuntimeException("Badness has ensued."));

    assertEquals("value", faultTolerantCluster.withCluster(connection -> connection.sync().get("key")));

    assertThrows(RedisException.class,
        () -> faultTolerantCluster.withCluster(connection -> connection.sync().get("OH NO")));

    final RedisException redisException = assertThrows(RedisException.class,
        () -> faultTolerantCluster.withCluster(connection -> connection.sync().get("OH NO")));

    assertInstanceOf(CallNotPermittedException.class, redisException.getCause());
  }

  @Test
  void testRetry() {
    when(clusterCommands.get(anyString()))
        .thenThrow(new RedisCommandTimeoutException())
        .thenThrow(new RedisCommandTimeoutException())
        .thenReturn("value");

    assertEquals("value", faultTolerantCluster.withCluster(connection -> connection.sync().get("key")));

    when(clusterCommands.get(anyString()))
        .thenThrow(new RedisCommandTimeoutException())
        .thenThrow(new RedisCommandTimeoutException())
        .thenThrow(new RedisCommandTimeoutException())
        .thenReturn("value");

    assertThrows(RedisCommandTimeoutException.class,
        () -> faultTolerantCluster.withCluster(connection -> connection.sync().get("key")));

  }

  @Nested
  class WithRealCluster {

    private static final Duration TIMEOUT = Duration.ofMillis(50);

    private static final RetryConfiguration retryConfiguration = new RetryConfiguration();

    static {
      retryConfiguration.setMaxAttempts(1);
      retryConfiguration.setWaitDuration(50);
    }

    @RegisterExtension
    static final RedisClusterExtension REDIS_CLUSTER_EXTENSION = RedisClusterExtension.builder()
        .retryConfiguration(retryConfiguration)
        .timeout(TIMEOUT)
        .build();

    @Test
    void testTimeout() {
      final FaultTolerantRedisCluster cluster = REDIS_CLUSTER_EXTENSION.getRedisCluster();

      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
        final ExecutionException asyncException = assertThrows(ExecutionException.class,
            () -> cluster.withCluster(connection -> connection.async().blpop(TIMEOUT.toMillis() * 2, "key")).get());
        assertInstanceOf(RedisCommandTimeoutException.class, asyncException.getCause());

        assertThrows(RedisCommandTimeoutException.class,
            () -> cluster.withCluster(connection -> connection.sync().blpop(TIMEOUT.toMillis() * 2, "key")));
      });

    }
  }

}
