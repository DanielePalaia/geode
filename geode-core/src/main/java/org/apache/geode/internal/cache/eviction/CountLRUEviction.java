/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.eviction;

import java.util.Properties;

import org.apache.geode.StatisticDescriptor;
import org.apache.geode.StatisticsType;
import org.apache.geode.StatisticsTypeFactory;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.Declarable;
import org.apache.geode.cache.EvictionAction;
import org.apache.geode.cache.EvictionAttributes;
import org.apache.geode.cache.Region;
import org.apache.geode.internal.cache.InternalRegion;
import org.apache.geode.internal.cache.Token;
import org.apache.geode.internal.cache.persistence.DiskRegionView;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.statistics.StatisticsTypeFactoryImpl;

/**
 * A {@code CapacityController} that will remove the least recently used (LRU) entry from a region
 * once the region reaches a certain capacity. The entry is locally destroyed when evicted by the
 * capacity controller.
 *
 * <p>
 * This is not supported as the capacity controller of a region with mirroring enabled.
 *
 * <p>
 * CountLRUEviction must be set in the RegionAttributes before the region is created. A Region with
 * CountLRUEviction set will throw an IllegalStateException if an attempt is made to replace the
 * Region's capacity controller. While the capacity controller cannot be replaced, it does support
 * changing the limit with the setMaximumEntries method.
 *
 * <p>
 * If you are using a {@code cache.xml} file to create a JCache region declaratively, you can
 * include the following to associate a {@code CountLRUEviction} with a region:
 *
 * <pre>
 *  &lt;region-attributes&gt;
 *    &lt;capacity-controller&gt;
 *      &lt;classname&gt;org.apache.geode.cache.LRUCapacityController&lt;/classname&gt;
 *      &lt;parameter name="maximum-entries"&gt;1000&lt;/parameter&gt;
 *    &lt;/capacity-controller&gt;
 *  &lt;/region-attributes&gt;
 * </pre>
 *
 * @since GemFire 2.0.2
 */
public class CountLRUEviction extends AbstractEvictionController implements Declarable {

  private static final long serialVersionUID = -4383074909189355938L;

  /**
   * The default maximum number of entries allowed by an LRU capacity controller is 900.
   */
  public static final int DEFAULT_MAXIMUM_ENTRIES = EvictionAttributes.DEFAULT_ENTRIES_MAXIMUM;

  /**
   * The key for setting the maximum-entries property declaratively.
   *
   * @see #init
   */
  public static final String MAXIMUM_ENTRIES = "maximum-entries";

  protected static final StatisticsType statType;

  static {
    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();

    final String entriesAllowedDesc = "Number of entries allowed in this region.";
    final String regionEntryCountDesc = "Number of entries in this region.";
    final String lruEvictionsDesc = "Number of total entry evictions triggered by LRU.";
    final String lruDestroysDesc =
        "Number of entries destroyed in the region through both destroy cache operations and eviction. Reset to zero each time it exceeds lruDestroysLimit.";
    final String lruDestroysLimitDesc =
        "Maximum number of entry destroys triggered by LRU before scan occurs.";
    final String lruEvaluationsDesc = "Number of entries evaluated during LRU operations.";
    final String lruGreedyReturnsDesc = "Number of non-LRU entries evicted during LRU operations";

    statType = f.createType("LRUStatistics",
        "Statistics about entry based Least Recently Used region entry disposal",
        new StatisticDescriptor[] {
            f.createLongGauge("entriesAllowed", entriesAllowedDesc, "entries"),
            f.createLongGauge("entryCount", regionEntryCountDesc, "entries"),
            f.createLongCounter("lruEvictions", lruEvictionsDesc, "entries"),
            f.createLongCounter("lruDestroys", lruDestroysDesc, "entries"),
            f.createLongGauge("lruDestroysLimit", lruDestroysLimitDesc, "entries"),
            f.createLongCounter("lruEvaluations", lruEvaluationsDesc, "entries"),
            f.createLongCounter("lruGreedyReturns", lruGreedyReturnsDesc, "entries"),});

  }

  /** The maximum number entries allowed by this controller */
  private volatile int maximumEntries;

  /**
   * Creates an LRU capacity controller that allows the {@link #DEFAULT_MAXIMUM_ENTRIES default}
   * maximum number of entries and the
   * {@link org.apache.geode.cache.EvictionAction#DEFAULT_EVICTION_ACTION default} eviction action.
   *
   * @see #CountLRUEviction(int,Region)
   */
  public CountLRUEviction(Region region) {
    this(DEFAULT_MAXIMUM_ENTRIES, EvictionAction.DEFAULT_EVICTION_ACTION, region);
  }

  /**
   * Creates an LRU capacity controller that allows the given number of maximum entries and uses the
   * default eviction action.
   *
   * @param maximumEntries The maximum number of entries allowed in the region whose capacity this
   *        controller controls. Once there are {@code capacity} entries in a region, this
   *        controller will remove the least recently used entry.<br>
   *        <p>
   *        For a region with {@link DataPolicy#PARTITION}, the maximum number of entries allowed in
   *        the region, collectively for its primary buckets and redundant copies for this VM. After
   *        there are {@code capacity} entries in the region's primary buckets and redundant copies
   *        for this VM, this controller will remove the least recently used entry from the bucket
   *        in which the subsequent {@code put} takes place.
   */
  public CountLRUEviction(int maximumEntries, Region region) {
    this(maximumEntries, EvictionAction.DEFAULT_EVICTION_ACTION, region);
  }

  /**
   * Creates an LRU capacity controller that allows the given number of maximum entries.
   *
   * @param maximumEntries The maximum number of entries allowed in the region whose capacity this
   *        controller controls. Once there are {@code capacity} entries in a region, this
   *        controller will remove the least recently used entry.<br>
   *        <p>
   *        For a region with {@link DataPolicy#PARTITION}, the maximum number of entries allowed in
   *        the region, collectively for its primary buckets and redundant copies for this VM. After
   *        there are {@code capacity} entries in the region's primary buckets and redundant copies
   *        for this VM, this controller will remove the least recently used entry from the bucket
   *        in which the subsequent {@code put} takes place.
   * @param evictionAction The action to perform upon the least recently used entry. See
   *        {@link #EVICTION_ACTION}.
   */
  public CountLRUEviction(int maximumEntries, EvictionAction evictionAction, Region region) {
    super(evictionAction, region);
    setMaximumEntries(maximumEntries);
  }

  /**
   * Sets the limit on the number of entries allowed. This change takes place on next region
   * operation that could increase the region size.
   */
  public void setMaximumEntries(int maximumEntries) {
    if (maximumEntries <= 0)
      throw new IllegalArgumentException(
          LocalizedStrings.LRUCapacityController_MAXIMUM_ENTRIES_MUST_BE_POSITIVE
              .toLocalizedString());
    this.maximumEntries = maximumEntries;
    if (bucketRegion != null) {
      bucketRegion.setLimit(this.maximumEntries);
    } else if (this.stats != null) {
      this.stats.setLimit(this.maximumEntries);
    }
  }

  @Override
  public void setLimit(int max) {
    setMaximumEntries(max);
  }

  ////////////////////// Instance Methods /////////////////////

  /**
   * Because an {@code LRUCapacityController} is {@link Declarable}, it can be initialized with
   * properties. The {@link #MAXIMUM_ENTRIES "maximum-entries"} (case-sensitive) property can be
   * used to specify the capacity allowed by this controller. Other properties in props are ignored.
   * The {@link #EVICTION_ACTION "eviction-action"} property specifies the action to be taken when
   * the region has reached its capacity.
   *
   * @throws NumberFormatException The {@code maximum-entries} property cannot be parsed as an
   *         integer
   * @throws IllegalArgumentException The value of the {@code eviction-action} property is not
   *         recoginzed.
   */
  @Override
  public void init(Properties props) throws NumberFormatException {
    String prop = null;
    if ((prop = props.getProperty(MAXIMUM_ENTRIES)) != null) {
      this.maximumEntries = Integer.parseInt(prop);
    }

    if ((prop = props.getProperty(EVICTION_ACTION)) != null) {
      setEvictionAction(EvictionAction.parseAction(prop));
    }
  }

  @Override
  public long getLimit() {
    return this.maximumEntries;
  }

  /**
   * Indicate what kind of {@code AbstractEvictionController} this helper implements
   */
  @Override
  public org.apache.geode.cache.EvictionAlgorithm getEvictionAlgorithm() {
    return org.apache.geode.cache.EvictionAlgorithm.LRU_ENTRY;
  }

  /**
   * All entries for the LRUCapacityController are considered to be of size 1.
   */
  @Override
  public int entrySize(Object key, Object value) throws IllegalArgumentException {

    if (Token.isRemoved(value) /* && (value != Token.TOMBSTONE) */) { // un-comment to make
                                                                      // tombstones visible
      // bug #42228 - lruEntryDestroy removes an entry from the LRU, but if
      // it is subsequently resurrected we want the new entry to generate a delta
      return 0;
    }
    if ((value == null /* overflow to disk */ || value == Token.INVALID
        || value == Token.LOCAL_INVALID) && getEvictionAction().isOverflowToDisk()) {
      // Don't count this guys toward LRU
      return 0;

    } else {
      return 1;
    }
  }

  @Override
  public StatisticsType getStatisticsType() {
    return statType;
  }

  @Override
  public String getStatisticsName() {
    return "LRUStatistics";
  }

  @Override
  public int getLimitStatId() {
    return statType.nameToId("entriesAllowed");
  }

  @Override
  public int getCountStatId() {
    return statType.nameToId("entryCount");
  }

  @Override
  public int getEvictionsStatId() {
    return statType.nameToId("lruEvictions");
  }

  @Override
  public int getDestroysStatId() {
    return statType.nameToId("lruDestroys");
  }

  @Override
  public int getDestroysLimitStatId() {
    return statType.nameToId("lruDestroysLimit");
  }

  @Override
  public int getEvaluationsStatId() {
    return statType.nameToId("lruEvaluations");
  }

  @Override
  public int getGreedyReturnsStatId() {
    return statType.nameToId("lruGreedyReturns");
  }

  @Override
  public boolean mustEvict(EvictionStatistics stats, InternalRegion region, int delta) {
    return stats.getCounter() + delta > stats.getLimit();
  }

  @Override
  public boolean lruLimitExceeded(EvictionStatistics stats, DiskRegionView diskRegionView) {
    return stats.getCounter() > stats.getLimit();
  }


  @Override
  public boolean equals(Object cc) {
    if (!super.equals(cc))
      return false;
    if (!(cc instanceof CountLRUEviction))
      return false;
    CountLRUEviction other = (CountLRUEviction) cc;
    if (this.maximumEntries != other.maximumEntries)
      return false;
    return true;
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#hashCode()
   *
   * Note that we just need to make sure that equal objects return equal hashcodes; nothing really
   * elaborate is done here.
   */
  @Override
  public int hashCode() {
    int result = super.hashCode();
    result += this.maximumEntries;
    return result;
  }

  /**
   * Returns a brief description of this capacity controller.
   *
   * @since GemFire 4.0
   */
  @Override
  public String toString() {
    return LocalizedStrings.LRUCapacityController_LRUCAPACITYCONTROLLER_WITH_A_CAPACITY_OF_0_ENTRIES_AND_EVICTION_ACTION_1
        .toLocalizedString(new Object[] {Long.valueOf(this.getLimit()), this.getEvictionAction()});
  }
}
