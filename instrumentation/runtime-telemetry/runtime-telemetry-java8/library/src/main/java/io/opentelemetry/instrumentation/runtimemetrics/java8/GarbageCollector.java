/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.runtimemetrics.java8;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.instrumentation.runtimemetrics.java8.internal.JmxRuntimeMetricsUtil;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Registers instruments that generate metrics about JVM garbage collection.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * GarbageCollector.registerObservers(GlobalOpenTelemetry.get());
 * }</pre>
 *
 * <p>Example metrics being exported:
 *
 * <pre>
 *   process.runtime.jvm.gc.duration{gc="G1 Young Generation",action="end of minor GC"} 0.022
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
 *   jvm.gc.duration{jvm.gc.name="G1 Young Generation",jvm.gc.action="end of minor GC"} 0.022
 * </pre>
 */
public final class GarbageCollector {

  private static final Logger logger = Logger.getLogger(GarbageCollector.class.getName());

  private static final double MILLIS_PER_S = TimeUnit.SECONDS.toMillis(1);

  private static final AttributeKey<String> GC_KEY = stringKey("gc");
  private static final AttributeKey<String> ACTION_KEY = stringKey("action");

  // TODO: use the opentelemetry-semconv classes once we have metrics attributes there
  private static final AttributeKey<String> JVM_GC_NAME = stringKey("jvm.gc.name");
  private static final AttributeKey<String> JVM_GC_ACTION = stringKey("jvm.gc.action");
  static final List<Double> GC_DURATION_BUCKETS = unmodifiableList(asList(0.01, 0.1, 1., 10.));

  private static final NotificationFilter GC_FILTER =
      notification ->
          notification
              .getType()
              .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION);

  /** Register observers for java runtime memory metrics. */
  public static List<AutoCloseable> registerObservers(OpenTelemetry openTelemetry) {
    if (!isNotificationClassPresent()) {
      logger.fine(
          "The com.sun.management.GarbageCollectionNotificationInfo class is not available;"
              + " GC metrics will not be reported.");
      return Collections.emptyList();
    }

    return registerObservers(
        openTelemetry,
        ManagementFactory.getGarbageCollectorMXBeans(),
        GarbageCollector::extractNotificationInfo);
  }

  // Visible for testing
  static List<AutoCloseable> registerObservers(
      OpenTelemetry openTelemetry,
      List<GarbageCollectorMXBean> gcBeans,
      Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
    Meter meter = JmxRuntimeMetricsUtil.getMeter(openTelemetry);

    DoubleHistogram oldGcDuration = null;
    DoubleHistogram stableGcDuration = null;

    if (SemconvStability.emitOldJvmSemconv()) {
      oldGcDuration =
          meter
              .histogramBuilder("process.runtime.jvm.gc.duration")
              .setDescription("Duration of JVM garbage collection actions")
              .setUnit("s")
              .setExplicitBucketBoundariesAdvice(emptyList())
              .build();
    }
    if (SemconvStability.emitStableJvmSemconv()) {
      stableGcDuration =
          meter
              .histogramBuilder("jvm.gc.duration")
              .setDescription("Duration of JVM garbage collection actions.")
              .setUnit("s")
              .setExplicitBucketBoundariesAdvice(GC_DURATION_BUCKETS)
              .build();
    }

    List<AutoCloseable> result = new ArrayList<>();
    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (!(gcBean instanceof NotificationEmitter)) {
        continue;
      }
      NotificationEmitter notificationEmitter = (NotificationEmitter) gcBean;
      GcNotificationListener listener =
          new GcNotificationListener(oldGcDuration, stableGcDuration, notificationInfoExtractor);
      notificationEmitter.addNotificationListener(listener, GC_FILTER, null);
      result.add(() -> notificationEmitter.removeNotificationListener(listener));
    }
    return result;
  }

  private static final class GcNotificationListener implements NotificationListener {

    @Nullable private final DoubleHistogram oldGcDuration;
    @Nullable private final DoubleHistogram stableGcDuration;
    private final Function<Notification, GarbageCollectionNotificationInfo>
        notificationInfoExtractor;

    private GcNotificationListener(
        @Nullable DoubleHistogram oldGcDuration,
        @Nullable DoubleHistogram stableGcDuration,
        Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
      this.oldGcDuration = oldGcDuration;
      this.stableGcDuration = stableGcDuration;
      this.notificationInfoExtractor = notificationInfoExtractor;
    }

    @Override
    public void handleNotification(Notification notification, Object unused) {
      GarbageCollectionNotificationInfo notificationInfo =
          notificationInfoExtractor.apply(notification);

      String gcName = notificationInfo.getGcName();
      String gcAction = notificationInfo.getGcAction();
      double duration = notificationInfo.getGcInfo().getDuration() / MILLIS_PER_S;

      if (oldGcDuration != null) {
        oldGcDuration.record(duration, Attributes.of(GC_KEY, gcName, ACTION_KEY, gcAction));
      }
      if (stableGcDuration != null) {
        stableGcDuration.record(
            duration, Attributes.of(JVM_GC_NAME, gcName, JVM_GC_ACTION, gcAction));
      }
    }
  }

  /**
   * Extract {@link GarbageCollectionNotificationInfo} from the {@link Notification}.
   *
   * <p>Note: this exists as a separate function so that the behavior can be overridden with mocks
   * in tests. It's very challenging to create a mock {@link CompositeData} that can be parsed by
   * {@link GarbageCollectionNotificationInfo#from(CompositeData)}.
   */
  private static GarbageCollectionNotificationInfo extractNotificationInfo(
      Notification notification) {
    return GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
  }

  private static boolean isNotificationClassPresent() {
    try {
      Class.forName(
          "com.sun.management.GarbageCollectionNotificationInfo",
          false,
          GarbageCollectorMXBean.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private GarbageCollector() {}
}
