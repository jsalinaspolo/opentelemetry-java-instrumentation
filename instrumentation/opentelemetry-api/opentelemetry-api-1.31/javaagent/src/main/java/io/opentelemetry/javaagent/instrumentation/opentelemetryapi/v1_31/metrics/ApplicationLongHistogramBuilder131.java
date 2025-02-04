/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opentelemetryapi.v1_31.metrics;

import application.io.opentelemetry.api.common.AttributeKey;
import application.io.opentelemetry.extension.incubator.metrics.ExtendedLongHistogramBuilder;
import io.opentelemetry.javaagent.instrumentation.opentelemetryapi.trace.Bridging;
import java.util.List;

final class ApplicationLongHistogramBuilder131 extends ApplicationLongHistogramBuilder132
    implements ExtendedLongHistogramBuilder {

  private final io.opentelemetry.api.metrics.LongHistogramBuilder agentBuilder;

  ApplicationLongHistogramBuilder131(
      io.opentelemetry.api.metrics.LongHistogramBuilder agentBuilder) {
    super(agentBuilder);
    this.agentBuilder = agentBuilder;
  }

  @Override
  public ExtendedLongHistogramBuilder setExplicitBucketBoundariesAdvice(
      List<Long> bucketBoundaries) {
    agentBuilder.setExplicitBucketBoundariesAdvice(bucketBoundaries);
    return this;
  }

  @Override
  public ExtendedLongHistogramBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    ((io.opentelemetry.extension.incubator.metrics.ExtendedLongHistogramBuilder) agentBuilder)
        .setAttributesAdvice(Bridging.toAgent(attributes));
    return this;
  }
}
