/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.runtimemetrics.java8;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.instrumentation.runtimemetrics.java8.internal.JmxRuntimeMetricsUtil;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registers measurements that generate metrics about JVM memory pools.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * MemoryPools.registerObservers(GlobalOpenTelemetry.get());
 * }</pre>
 *
 * <p>Example metrics being exported: Component
 *
 * <pre>
 *   process.runtime.jvm.memory.init{type="heap",pool="G1 Eden Space"} 1000000
 *   process.runtime.jvm.memory.usage{type="heap",pool="G1 Eden Space"} 2500000
 *   process.runtime.jvm.memory.committed{type="heap",pool="G1 Eden Space"} 3000000
 *   process.runtime.jvm.memory.limit{type="heap",pool="G1 Eden Space"} 4000000
 *   process.runtime.jvm.memory.usage_after_last_gc{type="heap",pool="G1 Eden Space"} 1500000
 *   process.runtime.jvm.memory.init{type="non_heap",pool="Metaspace"} 200
 *   process.runtime.jvm.memory.usage{type="non_heap",pool="Metaspace"} 400
 *   process.runtime.jvm.memory.committed{type="non_heap",pool="Metaspace"} 500
 * </pre>
 *
 * <p>In case you enable the preview of stable JVM semantic conventions (e.g. by setting the {@code
 * otel.semconv-stability.opt-in} system property to {@code jvm}), the metrics being exported will
 * follow <a
 * href="https://github.com/open-telemetry/semantic-conventions/blob/main/docs/runtime/jvm-metrics.md">the
 * most recent JVM semantic conventions</a>. This is how the example above looks when stable JVM
 * semconv is enabled:
 *
 * <pre>
 *   jvm.memory.used{type="heap",pool="G1 Eden Space"} 2500000
 *   jvm.memory.committed{type="heap",pool="G1 Eden Space"} 3000000
 *   jvm.memory.limit{type="heap",pool="G1 Eden Space"} 4000000
 *   jvm.memory.used_after_last_gc{type="heap",pool="G1 Eden Space"} 1500000
 *   jvm.memory.used{type="non_heap",pool="Metaspace"} 400
 *   jvm.memory.committed{type="non_heap",pool="Metaspace"} 500
 * </pre>
 */
public final class MemoryPools {

  private static final AttributeKey<String> TYPE_KEY = stringKey("type");
  private static final AttributeKey<String> POOL_KEY = stringKey("pool");

  // TODO: use the opentelemetry-semconv classes once we have metrics attributes there
  private static final AttributeKey<String> JVM_MEMORY_POOL_NAME =
      stringKey("jvm.memory.pool.name");
  private static final AttributeKey<String> JVM_MEMORY_TYPE = stringKey("jvm.memory.type");

  private static final String HEAP = "heap";
  private static final String NON_HEAP = "non_heap";

  /** Register observers for java runtime memory metrics. */
  public static List<AutoCloseable> registerObservers(OpenTelemetry openTelemetry) {
    return registerObservers(openTelemetry, ManagementFactory.getMemoryPoolMXBeans());
  }

  // Visible for testing
  static List<AutoCloseable> registerObservers(
      OpenTelemetry openTelemetry, List<MemoryPoolMXBean> poolBeans) {
    Meter meter = JmxRuntimeMetricsUtil.getMeter(openTelemetry);
    List<AutoCloseable> observables = new ArrayList<>();

    if (SemconvStability.emitOldJvmSemconv()) {
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.memory.usage")
              .setDescription("Measure of memory used")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      POOL_KEY,
                      TYPE_KEY,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getUsed)));
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.memory.init")
              .setDescription("Measure of initial memory requested")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      POOL_KEY,
                      TYPE_KEY,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getInit)));
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.memory.committed")
              .setDescription("Measure of memory committed")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      POOL_KEY,
                      TYPE_KEY,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getCommitted)));
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.memory.limit")
              .setDescription("Measure of max obtainable memory")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      POOL_KEY,
                      TYPE_KEY,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getMax)));
      observables.add(
          meter
              .upDownCounterBuilder("process.runtime.jvm.memory.usage_after_last_gc")
              .setDescription(
                  "Measure of memory used after the most recent garbage collection event on this pool")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      POOL_KEY,
                      TYPE_KEY,
                      poolBeans,
                      MemoryPoolMXBean::getCollectionUsage,
                      MemoryUsage::getUsed)));
    }

    if (SemconvStability.emitStableJvmSemconv()) {
      observables.add(
          meter
              .upDownCounterBuilder("jvm.memory.used")
              .setDescription("Measure of memory used.")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      JVM_MEMORY_POOL_NAME,
                      JVM_MEMORY_TYPE,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getUsed)));
      observables.add(
          meter
              .upDownCounterBuilder("jvm.memory.committed")
              .setDescription("Measure of memory committed.")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      JVM_MEMORY_POOL_NAME,
                      JVM_MEMORY_TYPE,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getCommitted)));
      observables.add(
          meter
              .upDownCounterBuilder("jvm.memory.limit")
              .setDescription("Measure of max obtainable memory.")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      JVM_MEMORY_POOL_NAME,
                      JVM_MEMORY_TYPE,
                      poolBeans,
                      MemoryPoolMXBean::getUsage,
                      MemoryUsage::getMax)));
      observables.add(
          meter
              .upDownCounterBuilder("jvm.memory.used_after_last_gc")
              .setDescription(
                  "Measure of memory used, as measured after the most recent garbage collection event on this pool.")
              .setUnit("By")
              .buildWithCallback(
                  callback(
                      JVM_MEMORY_POOL_NAME,
                      JVM_MEMORY_TYPE,
                      poolBeans,
                      MemoryPoolMXBean::getCollectionUsage,
                      MemoryUsage::getUsed)));
    }

    return observables;
  }

  // Visible for testing
  static Consumer<ObservableLongMeasurement> callback(
      AttributeKey<String> poolNameKey,
      AttributeKey<String> memoryTypeKey,
      List<MemoryPoolMXBean> poolBeans,
      Function<MemoryPoolMXBean, MemoryUsage> memoryUsageExtractor,
      Function<MemoryUsage, Long> valueExtractor) {
    List<Attributes> attributeSets = new ArrayList<>(poolBeans.size());
    for (MemoryPoolMXBean pool : poolBeans) {
      attributeSets.add(
          Attributes.builder()
              .put(poolNameKey, pool.getName())
              .put(memoryTypeKey, memoryType(pool.getType()))
              .build());
    }

    return measurement -> {
      for (int i = 0; i < poolBeans.size(); i++) {
        Attributes attributes = attributeSets.get(i);
        MemoryUsage memoryUsage = memoryUsageExtractor.apply(poolBeans.get(i));
        if (memoryUsage == null) {
          // JVM may return null in special cases for MemoryPoolMXBean.getUsage() and
          // MemoryPoolMXBean.getCollectionUsage()
          continue;
        }
        long value = valueExtractor.apply(memoryUsage);
        if (value != -1) {
          measurement.record(value, attributes);
        }
      }
    };
  }

  private static String memoryType(MemoryType memoryType) {
    switch (memoryType) {
      case HEAP:
        return HEAP;
      case NON_HEAP:
        return NON_HEAP;
    }
    return "unknown";
  }

  private MemoryPools() {}
}
