/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.select;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.inject.Inject;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.nary.BinaryFn;
import org.apache.druid.query.CacheStrategy;
import org.apache.druid.query.IntervalChunkingQueryRunnerDecorator;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.Result;
import org.apache.druid.query.ResultGranularTimestampComparator;
import org.apache.druid.query.ResultMergeQueryRunner;
import org.apache.druid.query.aggregation.MetricManipulationFn;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.timeline.LogicalSegment;
import org.apache.druid.timeline.SegmentId;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 */
public class SelectQueryQueryToolChest extends QueryToolChest<Result<SelectResultValue>, SelectQuery>
{
  private static final byte SELECT_QUERY = 0x16;
  private static final TypeReference<Object> OBJECT_TYPE_REFERENCE =
      new TypeReference<Object>()
      {
      };
  private static final TypeReference<Result<SelectResultValue>> TYPE_REFERENCE =
      new TypeReference<Result<SelectResultValue>>()
      {
      };

  private final ObjectMapper jsonMapper;
  @Deprecated
  private final IntervalChunkingQueryRunnerDecorator intervalChunkingQueryRunnerDecorator;
  private final SelectQueryMetricsFactory queryMetricsFactory;

  public SelectQueryQueryToolChest(
      ObjectMapper jsonMapper,
      IntervalChunkingQueryRunnerDecorator intervalChunkingQueryRunnerDecorator,
      Supplier<SelectQueryConfig> configSupplier
  )
  {
    this(jsonMapper, intervalChunkingQueryRunnerDecorator, configSupplier, DefaultSelectQueryMetricsFactory.instance());
  }

  @Inject
  public SelectQueryQueryToolChest(
      ObjectMapper jsonMapper,
      IntervalChunkingQueryRunnerDecorator intervalChunkingQueryRunnerDecorator,
      Supplier<SelectQueryConfig> configSupplier,
      SelectQueryMetricsFactory queryMetricsFactory
  )
  {
    this.jsonMapper = jsonMapper;
    this.intervalChunkingQueryRunnerDecorator = intervalChunkingQueryRunnerDecorator;
    this.queryMetricsFactory = queryMetricsFactory;
  }

  @Override
  public QueryRunner<Result<SelectResultValue>> mergeResults(
      QueryRunner<Result<SelectResultValue>> queryRunner
  )
  {
    return new ResultMergeQueryRunner<Result<SelectResultValue>>(queryRunner)
    {
      @Override
      protected Ordering<Result<SelectResultValue>> makeOrdering(Query<Result<SelectResultValue>> query)
      {
        return ResultGranularTimestampComparator.create(
            ((SelectQuery) query).getGranularity(), query.isDescending()
        );
      }

      @Override
      protected BinaryFn<Result<SelectResultValue>, Result<SelectResultValue>, Result<SelectResultValue>> createMergeFn(
          Query<Result<SelectResultValue>> input
      )
      {
        SelectQuery query = (SelectQuery) input;
        return new SelectBinaryFn(
            query.getGranularity(),
            query.getPagingSpec(),
            query.isDescending()
        );
      }
    };
  }

  @Override
  public SelectQueryMetrics makeMetrics(SelectQuery query)
  {
    SelectQueryMetrics queryMetrics = queryMetricsFactory.makeMetrics(query);
    queryMetrics.query(query);
    return queryMetrics;
  }

  @Override
  public Function<Result<SelectResultValue>, Result<SelectResultValue>> makePreComputeManipulatorFn(
      final SelectQuery query,
      final MetricManipulationFn fn
  )
  {
    return Functions.identity();
  }

  @Override
  public TypeReference<Result<SelectResultValue>> getResultTypeReference()
  {
    return TYPE_REFERENCE;
  }

  @Override
  public CacheStrategy<Result<SelectResultValue>, Object, SelectQuery> getCacheStrategy(final SelectQuery query)
  {

    return new CacheStrategy<Result<SelectResultValue>, Object, SelectQuery>()
    {
      private final List<DimensionSpec> dimensionSpecs =
          query.getDimensions() != null ? query.getDimensions() : Collections.emptyList();
      private final List<String> dimOutputNames = dimensionSpecs.size() > 0 ?
          Lists.transform(dimensionSpecs, DimensionSpec::getOutputName) : Collections.emptyList();

      @Override
      public boolean isCacheable(SelectQuery query, boolean willMergeRunners)
      {
        return true;
      }

      @Override
      public byte[] computeCacheKey(SelectQuery query)
      {
        final DimFilter dimFilter = query.getDimensionsFilter();
        final byte[] filterBytes = dimFilter == null ? new byte[]{} : dimFilter.getCacheKey();
        final byte[] granularityBytes = query.getGranularity().getCacheKey();

        final List<DimensionSpec> dimensionSpecs =
            query.getDimensions() != null ? query.getDimensions() : Collections.emptyList();
        final byte[][] dimensionsBytes = new byte[dimensionSpecs.size()][];
        int dimensionsBytesSize = 0;
        int index = 0;
        for (DimensionSpec dimension : dimensionSpecs) {
          dimensionsBytes[index] = dimension.getCacheKey();
          dimensionsBytesSize += dimensionsBytes[index].length;
          ++index;
        }

        final Set<String> metrics = new TreeSet<>();
        if (query.getMetrics() != null) {
          metrics.addAll(query.getMetrics());
        }

        final byte[][] metricBytes = new byte[metrics.size()][];
        int metricBytesSize = 0;
        index = 0;
        for (String metric : metrics) {
          metricBytes[index] = StringUtils.toUtf8(metric);
          metricBytesSize += metricBytes[index].length;
          ++index;
        }

        final byte[] virtualColumnsCacheKey = query.getVirtualColumns().getCacheKey();
        final byte isDescendingByte = query.isDescending() ? (byte) 1 : 0;

        final ByteBuffer queryCacheKey = ByteBuffer
            .allocate(
                2
                + granularityBytes.length
                + filterBytes.length
                + query.getPagingSpec().getCacheKey().length
                + dimensionsBytesSize
                + metricBytesSize
                + virtualColumnsCacheKey.length
            )
            .put(SELECT_QUERY)
            .put(granularityBytes)
            .put(filterBytes)
            .put(query.getPagingSpec().getCacheKey())
            .put(isDescendingByte);

        for (byte[] dimensionsByte : dimensionsBytes) {
          queryCacheKey.put(dimensionsByte);
        }

        for (byte[] metricByte : metricBytes) {
          queryCacheKey.put(metricByte);
        }

        queryCacheKey.put(virtualColumnsCacheKey);

        return queryCacheKey.array();
      }

      @Override
      public byte[] computeResultLevelCacheKey(SelectQuery query)
      {
        return computeCacheKey(query);
      }

      @Override
      public TypeReference<Object> getCacheObjectClazz()
      {
        return OBJECT_TYPE_REFERENCE;
      }

      @Override
      public Function<Result<SelectResultValue>, Object> prepareForCache(boolean isResultLevelCache)
      {
        return new Function<Result<SelectResultValue>, Object>()
        {
          @Override
          public Object apply(final Result<SelectResultValue> input)
          {
            if (!dimOutputNames.isEmpty()) {
              return Arrays.asList(
                  input.getTimestamp().getMillis(),
                  input.getValue().getPagingIdentifiers(),
                  input.getValue().getDimensions(),
                  input.getValue().getMetrics(),
                  input.getValue().getEvents(),
                  dimOutputNames
              );
            }
            return Arrays.asList(
                input.getTimestamp().getMillis(),
                input.getValue().getPagingIdentifiers(),
                input.getValue().getDimensions(),
                input.getValue().getMetrics(),
                input.getValue().getEvents()
            );
          }
        };
      }

      @Override
      public Function<Object, Result<SelectResultValue>> pullFromCache(boolean isResultLevelCache)
      {
        return new Function<Object, Result<SelectResultValue>>()
        {
          private final Granularity granularity = query.getGranularity();

          @Override
          public Result<SelectResultValue> apply(Object input)
          {
            List<Object> results = (List<Object>) input;
            Iterator<Object> resultIter = results.iterator();

            DateTime timestamp = granularity.toDateTime(((Number) resultIter.next()).longValue());

            Map<String, Integer> pageIdentifier = jsonMapper.convertValue(
                resultIter.next(),
                new TypeReference<Map<String, Integer>>() {}
            );
            Set<String> dimensionSet = jsonMapper.convertValue(resultIter.next(), new TypeReference<Set<String>>() {});
            Set<String> metricSet = jsonMapper.convertValue(resultIter.next(), new TypeReference<Set<String>>() {});
            List<EventHolder> eventHolders = jsonMapper.convertValue(
                resultIter.next(),
                new TypeReference<List<EventHolder>>() {}
            );
            // check the condition that outputName of cached result should be updated
            if (resultIter.hasNext()) {
              List<String> cachedOutputNames = (List<String>) resultIter.next();
              Preconditions.checkArgument(
                  cachedOutputNames.size() == dimOutputNames.size(),
                  "Cache hit but different number of dimensions??"
              );
              for (int idx = 0; idx < dimOutputNames.size(); idx++) {
                if (!cachedOutputNames.get(idx).equals(dimOutputNames.get(idx))) {
                  // rename outputName in the EventHolder
                  for (EventHolder eventHolder : eventHolders) {
                    Object obj = eventHolder.getEvent().remove(cachedOutputNames.get(idx));
                    if (obj != null) {
                      eventHolder.getEvent().put(dimOutputNames.get(idx), obj);
                    }
                  }
                }
              }
            }

            return new Result<>(
                timestamp,
                new SelectResultValue(pageIdentifier, dimensionSet, metricSet, eventHolders)
            );
          }
        };
      }
    };
  }

  @Override
  public QueryRunner<Result<SelectResultValue>> preMergeQueryDecoration(final QueryRunner<Result<SelectResultValue>> runner)
  {
    return intervalChunkingQueryRunnerDecorator.decorate(
        new QueryRunner<Result<SelectResultValue>>()
        {
          @Override
          public Sequence<Result<SelectResultValue>> run(
              QueryPlus<Result<SelectResultValue>> queryPlus,
              Map<String, Object> responseContext
          )
          {
            SelectQuery selectQuery = (SelectQuery) queryPlus.getQuery();
            if (selectQuery.getDimensionsFilter() != null) {
              selectQuery = selectQuery.withDimFilter(selectQuery.getDimensionsFilter().optimize());
              queryPlus = queryPlus.withQuery(selectQuery);
            }
            return runner.run(queryPlus, responseContext);
          }
        }, this);
  }

  @Override
  public <T extends LogicalSegment> List<T> filterSegments(SelectQuery query, List<T> segments)
  {
    // at the point where this code is called, only one datasource should exist.
    final String dataSource = Iterables.getOnlyElement(query.getDataSource().getNames());

    PagingSpec pagingSpec = query.getPagingSpec();
    Map<String, Integer> paging = pagingSpec.getPagingIdentifiers();
    if (paging == null || paging.isEmpty()) {
      return segments;
    }

    final Granularity granularity = query.getGranularity();

    TreeMap<Long, Long> granularThresholds = new TreeMap<>();

    // A paged select query using a UnionDataSource will return pagingIdentifiers from segments in more than one
    // dataSource which confuses subsequent queries and causes a failure. To avoid this, filter only the paging keys
    // that are applicable to this dataSource so that each dataSource in a union query gets the appropriate keys.
    paging
        .keySet()
        .stream()
        .filter(identifier -> SegmentId.tryParse(dataSource, identifier) != null)
        .map(SegmentId.makeIntervalExtractor(dataSource))
        .sorted(query.isDescending() ? Comparators.intervalsByEndThenStart()
                                     : Comparators.intervalsByStartThenEnd())
        .forEach(interval -> {
          if (query.isDescending()) {
            long granularEnd = granularity.bucketStart(interval.getEnd()).getMillis();
            Long currentEnd = granularThresholds.get(granularEnd);
            if (currentEnd == null || interval.getEndMillis() > currentEnd) {
              granularThresholds.put(granularEnd, interval.getEndMillis());
            }
          } else {
            long granularStart = granularity.bucketStart(interval.getStart()).getMillis();
            Long currentStart = granularThresholds.get(granularStart);
            if (currentStart == null || interval.getStartMillis() < currentStart) {
              granularThresholds.put(granularStart, interval.getStartMillis());
            }
          }
        });

    List<T> queryIntervals = Lists.newArrayList(segments);

    Iterator<T> it = queryIntervals.iterator();
    if (query.isDescending()) {
      while (it.hasNext()) {
        Interval interval = it.next().getInterval();
        Map.Entry<Long, Long> ceiling = granularThresholds.ceilingEntry(granularity.bucketStart(interval.getEnd()).getMillis());
        if (ceiling == null || interval.getStartMillis() >= ceiling.getValue()) {
          it.remove();
        }
      }
    } else {
      while (it.hasNext()) {
        Interval interval = it.next().getInterval();
        Map.Entry<Long, Long> floor = granularThresholds.floorEntry(granularity.bucketStart(interval.getStart()).getMillis());
        if (floor == null || interval.getEndMillis() <= floor.getValue()) {
          it.remove();
        }
      }
    }
    return queryIntervals;
  }
}
