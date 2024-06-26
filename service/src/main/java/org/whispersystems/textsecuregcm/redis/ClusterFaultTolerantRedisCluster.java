/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.redis;

import com.google.common.annotations.VisibleForTesting;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import io.micrometer.core.instrument.Tags;
import org.reactivestreams.Publisher;
import org.whispersystems.textsecuregcm.configuration.CircuitBreakerConfiguration;
import org.whispersystems.textsecuregcm.configuration.RedisClusterConfiguration;
import org.whispersystems.textsecuregcm.configuration.RetryConfiguration;
import org.whispersystems.textsecuregcm.util.CircuitBreakerUtil;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A fault-tolerant access manager for a Redis cluster. A single circuit breaker protects all cluster
 * calls.
 */
public class ClusterFaultTolerantRedisCluster implements FaultTolerantRedisCluster {

  private final String name;

  private final RedisClusterClient clusterClient;

  private final StatefulRedisClusterConnection<String, String> stringConnection;
  private final StatefulRedisClusterConnection<byte[], byte[]> binaryConnection;

  private final List<StatefulRedisClusterPubSubConnection<?, ?>> pubSubConnections = new ArrayList<>();

  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final Retry topologyChangedEventRetry;

  public ClusterFaultTolerantRedisCluster(final String name, final RedisClusterConfiguration clusterConfiguration,
      final ClientResources clientResources) {
    this(name,
        RedisClusterClient.create(clientResources,
            RedisUriUtil.createRedisUriWithTimeout(clusterConfiguration.getConfigurationUri(),
                clusterConfiguration.getTimeout())),
        clusterConfiguration.getTimeout(),
        clusterConfiguration.getCircuitBreakerConfiguration(),
        clusterConfiguration.getRetryConfiguration());
  }

  @VisibleForTesting
  ClusterFaultTolerantRedisCluster(final String name, final RedisClusterClient clusterClient,
      final Duration commandTimeout,
      final CircuitBreakerConfiguration circuitBreakerConfiguration, final RetryConfiguration retryConfiguration) {
    this.name = name;

    this.clusterClient = clusterClient;
    this.clusterClient.setOptions(ClusterClientOptions.builder()
        .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
        .validateClusterNodeMembership(false)
        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
            .enableAllAdaptiveRefreshTriggers()
            .build())
        // for asynchronous commands
        .timeoutOptions(TimeoutOptions.builder()
            .fixedTimeout(commandTimeout)
            .build())
        .publishOnScheduler(true)
        .build());

    this.stringConnection = clusterClient.connect();
    this.binaryConnection = clusterClient.connect(ByteArrayCodec.INSTANCE);

    this.circuitBreaker = CircuitBreaker.of(name + "-breaker", circuitBreakerConfiguration.toCircuitBreakerConfig());
    this.retry = Retry.of(name + "-retry", retryConfiguration.toRetryConfigBuilder()
        .retryOnException(exception -> exception instanceof RedisCommandTimeoutException).build());
    final RetryConfig topologyChangedEventRetryConfig = RetryConfig.custom()
        .maxAttempts(Integer.MAX_VALUE)
        .intervalFunction(
            IntervalFunction.ofExponentialRandomBackoff(Duration.ofSeconds(1), 1.5, Duration.ofSeconds(30)))
        .build();

    this.topologyChangedEventRetry = Retry.of(name + "-topologyChangedRetry", topologyChangedEventRetryConfig);

    CircuitBreakerUtil.registerMetrics(circuitBreaker, FaultTolerantRedisCluster.class, Tags.empty());
    CircuitBreakerUtil.registerMetrics(retry, FaultTolerantRedisCluster.class);
  }

  @Override
  public void shutdown() {
    stringConnection.close();
    binaryConnection.close();

    for (final StatefulRedisClusterPubSubConnection<?, ?> pubSubConnection : pubSubConnections) {
      pubSubConnection.close();
    }

    clusterClient.shutdown();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void useCluster(final Consumer<StatefulRedisClusterConnection<String, String>> consumer) {
    useConnection(stringConnection, consumer);
  }

  @Override
  public <T> T withCluster(final Function<StatefulRedisClusterConnection<String, String>, T> function) {
    return withConnection(stringConnection, function);
  }

  @Override
  public void useBinaryCluster(final Consumer<StatefulRedisClusterConnection<byte[], byte[]>> consumer) {
    useConnection(binaryConnection, consumer);
  }

  @Override
  public <T> T withBinaryCluster(final Function<StatefulRedisClusterConnection<byte[], byte[]>, T> function) {
    return withConnection(binaryConnection, function);
  }

  @Override
  public <T> Publisher<T> withBinaryClusterReactive(
      final Function<StatefulRedisClusterConnection<byte[], byte[]>, Publisher<T>> function) {
    return withConnectionReactive(binaryConnection, function);
  }

  @Override
  public <K, V> void useConnection(final StatefulRedisClusterConnection<K, V> connection,
      final Consumer<StatefulRedisClusterConnection<K, V>> consumer) {
    try {
      circuitBreaker.executeCheckedRunnable(() -> retry.executeRunnable(() -> consumer.accept(connection)));
    } catch (final Throwable t) {
      if (t instanceof RedisException) {
        throw (RedisException) t;
      } else {
        throw new RedisException(t);
      }
    }
  }

  @Override
  public <T, K, V> T withConnection(final StatefulRedisClusterConnection<K, V> connection,
      final Function<StatefulRedisClusterConnection<K, V>, T> function) {
    try {
      return circuitBreaker.executeCheckedSupplier(() -> retry.executeCallable(() -> function.apply(connection)));
    } catch (final Throwable t) {
      if (t instanceof RedisException) {
        throw (RedisException) t;
      } else {
        throw new RedisException(t);
      }
    }
  }

  @Override
  public <T, K, V> Publisher<T> withConnectionReactive(final StatefulRedisClusterConnection<K, V> connection,
      final Function<StatefulRedisClusterConnection<K, V>, Publisher<T>> function) {

    return Flux.from(function.apply(connection))
        .transformDeferred(RetryOperator.of(retry))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
  }

  public FaultTolerantPubSubConnection<String, String> createPubSubConnection() {
    final StatefulRedisClusterPubSubConnection<String, String> pubSubConnection = clusterClient.connectPubSub();
    pubSubConnections.add(pubSubConnection);

    return new ClusterFaultTolerantPubSubConnection<>(name, pubSubConnection, circuitBreaker, retry,
        topologyChangedEventRetry,
        Schedulers.newSingle(name + "-redisPubSubEvents", true));
  }
}
