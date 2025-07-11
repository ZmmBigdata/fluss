/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.metrics.prometheus;

import com.alibaba.fluss.annotation.VisibleForTesting;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.metrics.CharacterFilter;
import com.alibaba.fluss.metrics.Counter;
import com.alibaba.fluss.metrics.Gauge;
import com.alibaba.fluss.metrics.Histogram;
import com.alibaba.fluss.metrics.HistogramStatistics;
import com.alibaba.fluss.metrics.Meter;
import com.alibaba.fluss.metrics.Metric;
import com.alibaba.fluss.metrics.groups.MetricGroup;
import com.alibaba.fluss.metrics.reporter.MetricReporter;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.alibaba.fluss.utils.Preconditions.checkState;

/* This file is based on source code of Apache Flink Project (https://flink.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/** {@link MetricReporter} that exports {@link Metric Metrics} via Prometheus. */
public class PrometheusReporter implements MetricReporter {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusReporter.class);

    private static final Pattern UNALLOWED_CHAR_PATTERN = Pattern.compile("[^a-zA-Z0-9:_]");
    private static final CharacterFilter CHARACTER_FILTER = PrometheusReporter::replaceInvalidChars;

    @VisibleForTesting static final char SCOPE_SEPARATOR = '_';
    @VisibleForTesting static final String SCOPE_PREFIX = "fluss" + SCOPE_SEPARATOR;

    private final Map<String, AbstractMap.SimpleImmutableEntry<Collector, Integer>>
            collectorsWithCountByMetricName = new HashMap<>();

    @VisibleForTesting final CollectorRegistry registry = new CollectorRegistry(true);

    private HTTPServer httpServer;
    private int port;

    int getPort() {
        checkState(httpServer != null, "Server has not been initialized.");
        return port;
    }

    PrometheusReporter(Iterator<Integer> ports) {
        while (ports.hasNext()) {
            port = ports.next();
            try {
                httpServer = new HTTPServer(new InetSocketAddress(port), this.registry);
                LOG.info("Started PrometheusReporter HTTP server on port {}.", port);
                break;
            } catch (IOException ioe) { // assume port conflict
                LOG.debug("Could not start PrometheusReporter HTTP server on port {}.", port, ioe);
            }
        }

        if (httpServer == null) {
            throw new RuntimeException(
                    "Could not start PrometheusReporter HTTP server on any configured port. Ports: "
                            + ports);
        }
    }

    static String replaceInvalidChars(final String input) {
        // https://prometheus.io/docs/instrumenting/writing_exporters/
        // Only [a-zA-Z0-9:_] are valid in metric names, any other characters should be sanitized to
        // an underscore.
        return UNALLOWED_CHAR_PATTERN.matcher(input).replaceAll("_");
    }

    @Override
    public void open(Configuration config) {
        // do nothing now;
    }

    public void close() {
        if (httpServer != null) {
            httpServer.stop();
        }
        registry.clear();
    }

    @Override
    public void notifyOfAddedMetric(Metric metric, String metricName, MetricGroup group) {
        List<String> dimensionKeys = new LinkedList<>();
        List<String> dimensionValues = new LinkedList<>();
        for (final Map.Entry<String, String> dimension : group.getAllVariables().entrySet()) {
            final String key = dimension.getKey();
            dimensionKeys.add(CHARACTER_FILTER.filterCharacters(key));
            dimensionValues.add(CHARACTER_FILTER.filterCharacters(dimension.getValue()));
        }

        final String scopedMetricName = getScopedName(metricName, group);
        final String helpString = metricName + " (scope: " + getLogicalScope(group) + ")";

        final Collector collector;
        Integer count = 0;

        synchronized (this) {
            if (collectorsWithCountByMetricName.containsKey(scopedMetricName)) {
                final AbstractMap.SimpleImmutableEntry<Collector, Integer> collectorWithCount =
                        collectorsWithCountByMetricName.get(scopedMetricName);
                collector = collectorWithCount.getKey();
                count = collectorWithCount.getValue();
            } else {
                collector =
                        createCollector(
                                metric,
                                dimensionKeys,
                                dimensionValues,
                                scopedMetricName,
                                helpString);
                try {
                    collector.register(registry);
                } catch (Exception e) {
                    LOG.warn("There was a problem registering metric {}.", metricName, e);
                }
            }
            addMetric(metric, dimensionValues, collector);
            collectorsWithCountByMetricName.put(
                    scopedMetricName, new AbstractMap.SimpleImmutableEntry<>(collector, count + 1));
        }
    }

    private static String getScopedName(String metricName, MetricGroup group) {
        return SCOPE_PREFIX
                + getLogicalScope(group)
                + SCOPE_SEPARATOR
                + CHARACTER_FILTER.filterCharacters(metricName);
    }

    private Collector createCollector(
            Metric metric,
            List<String> dimensionKeys,
            List<String> dimensionValues,
            String scopedMetricName,
            String helpString) {
        Collector collector;
        switch (metric.getMetricType()) {
            case GAUGE:
            case COUNTER:
            case METER:
                collector =
                        io.prometheus.client.Gauge.build()
                                .name(scopedMetricName)
                                .help(helpString)
                                .labelNames(toArray(dimensionKeys))
                                .create();
                break;
            case HISTOGRAM:
                collector =
                        new HistogramSummaryProxy(
                                (Histogram) metric,
                                scopedMetricName,
                                helpString,
                                dimensionKeys,
                                dimensionValues);
                break;
            default:
                LOG.warn(
                        "Cannot create collector for unknown metric type: {}. This indicates that the metric type is not supported by this reporter.",
                        metric.getClass().getName());
                collector = null;
        }
        return collector;
    }

    private void addMetric(Metric metric, List<String> dimensionValues, Collector collector) {
        switch (metric.getMetricType()) {
            case GAUGE:
                ((io.prometheus.client.Gauge) collector)
                        .setChild(gaugeFrom((Gauge<?>) metric), toArray(dimensionValues));
                break;
            case COUNTER:
                ((io.prometheus.client.Gauge) collector)
                        .setChild(gaugeFrom((Counter) metric), toArray(dimensionValues));
                break;
            case METER:
                ((io.prometheus.client.Gauge) collector)
                        .setChild(gaugeFrom((Meter) metric), toArray(dimensionValues));
                break;
            case HISTOGRAM:
                ((HistogramSummaryProxy) collector).addChild((Histogram) metric, dimensionValues);
                break;
            default:
                LOG.warn(
                        "Cannot add unknown metric type: {}. This indicates that the metric type is not supported by this reporter.",
                        metric.getClass().getName());
        }
    }

    private void removeMetric(Metric metric, List<String> dimensionValues, Collector collector) {
        switch (metric.getMetricType()) {
            case GAUGE:
            case COUNTER:
            case METER:
                ((io.prometheus.client.Gauge) collector).remove(toArray(dimensionValues));
                break;
            case HISTOGRAM:
                ((HistogramSummaryProxy) collector).remove(dimensionValues);
                break;
            default:
                LOG.warn(
                        "Cannot remove unknown metric type: {}. This indicates that the metric type is not supported by this reporter.",
                        metric.getClass().getName());
        }
    }

    @Override
    public void notifyOfRemovedMetric(Metric metric, String metricName, MetricGroup group) {

        List<String> dimensionValues = new LinkedList<>();
        for (final Map.Entry<String, String> dimension : group.getAllVariables().entrySet()) {
            dimensionValues.add(CHARACTER_FILTER.filterCharacters(dimension.getValue()));
        }

        final String scopedMetricName = getScopedName(metricName, group);
        synchronized (this) {
            final AbstractMap.SimpleImmutableEntry<Collector, Integer> collectorWithCount =
                    collectorsWithCountByMetricName.get(scopedMetricName);
            final Integer count = collectorWithCount.getValue();
            final Collector collector = collectorWithCount.getKey();

            removeMetric(metric, dimensionValues, collector);

            if (count == 1) {
                try {
                    registry.unregister(collector);
                } catch (Exception e) {
                    LOG.warn("There was a problem unregistering metric {}.", scopedMetricName, e);
                }
                collectorsWithCountByMetricName.remove(scopedMetricName);
            } else {
                collectorsWithCountByMetricName.put(
                        scopedMetricName,
                        new AbstractMap.SimpleImmutableEntry<>(collector, count - 1));
            }
        }
    }

    private static String getLogicalScope(MetricGroup group) {
        return group.getLogicalScope(CHARACTER_FILTER, SCOPE_SEPARATOR);
    }

    @VisibleForTesting
    io.prometheus.client.Gauge.Child gaugeFrom(Gauge<?> gauge) {
        return new io.prometheus.client.Gauge.Child() {
            @Override
            public double get() {
                final Object value = gauge.getValue();
                if (value == null) {
                    LOG.debug("Gauge {} is null-valued, defaulting to 0.", gauge);
                    return 0;
                }
                if (value instanceof Double) {
                    return (double) value;
                }
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                if (value instanceof Boolean) {
                    return ((Boolean) value) ? 1 : 0;
                }
                LOG.debug(
                        "Invalid type for Gauge {}: {}, only number types and booleans are supported by this reporter.",
                        gauge,
                        value.getClass().getName());
                return 0;
            }
        };
    }

    private static io.prometheus.client.Gauge.Child gaugeFrom(Counter counter) {
        return new io.prometheus.client.Gauge.Child() {
            @Override
            public double get() {
                return (double) counter.getCount();
            }
        };
    }

    private static io.prometheus.client.Gauge.Child gaugeFrom(Meter meter) {
        return new io.prometheus.client.Gauge.Child() {
            @Override
            public double get() {
                return meter.getRate();
            }
        };
    }

    @VisibleForTesting
    static class HistogramSummaryProxy extends Collector {
        static final List<Double> QUANTILES = Arrays.asList(.5, .75, .95, .98, .99, .999);

        private final String metricName;
        private final String helpString;
        private final List<String> labelNamesWithQuantile;

        private final Map<List<String>, Histogram> histogramsByLabelValues = new HashMap<>();

        HistogramSummaryProxy(
                final Histogram histogram,
                final String metricName,
                final String helpString,
                final List<String> labelNames,
                final List<String> labelValues) {
            this.metricName = metricName;
            this.helpString = helpString;
            this.labelNamesWithQuantile = addToList(labelNames, "quantile");
            histogramsByLabelValues.put(labelValues, histogram);
        }

        @Override
        public List<MetricFamilySamples> collect() {
            // We cannot use SummaryMetricFamily because it is impossible to get a sum of all values
            // (at least for Dropwizard histograms,
            // whose snapshot's values array only holds a sample of recent values).

            List<MetricFamilySamples.Sample> samples = new LinkedList<>();
            for (Map.Entry<List<String>, Histogram> labelValuesToHistogram :
                    histogramsByLabelValues.entrySet()) {
                addSamples(
                        labelValuesToHistogram.getKey(),
                        labelValuesToHistogram.getValue(),
                        samples);
            }
            return Collections.singletonList(
                    new MetricFamilySamples(metricName, Type.SUMMARY, helpString, samples));
        }

        void addChild(final Histogram histogram, final List<String> labelValues) {
            histogramsByLabelValues.put(labelValues, histogram);
        }

        void remove(final List<String> labelValues) {
            histogramsByLabelValues.remove(labelValues);
        }

        private void addSamples(
                final List<String> labelValues,
                final Histogram histogram,
                final List<MetricFamilySamples.Sample> samples) {
            samples.add(
                    new MetricFamilySamples.Sample(
                            metricName + "_count",
                            labelNamesWithQuantile.subList(0, labelNamesWithQuantile.size() - 1),
                            labelValues,
                            histogram.getCount()));
            final HistogramStatistics statistics = histogram.getStatistics();
            for (final Double quantile : QUANTILES) {
                samples.add(
                        new MetricFamilySamples.Sample(
                                metricName,
                                labelNamesWithQuantile,
                                addToList(labelValues, quantile.toString()),
                                statistics.getQuantile(quantile)));
            }
        }
    }

    private static List<String> addToList(List<String> list, String element) {
        final List<String> result = new ArrayList<>(list);
        result.add(element);
        return result;
    }

    private static String[] toArray(List<String> list) {
        return list.toArray(new String[0]);
    }
}
