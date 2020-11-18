/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.ksm;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;

/**
 * This class is for maintaining KeySpaceManager statistics.
 */
public class KSMMetrics {
  // KSM op metrics
  private @Metric MutableCounterLong numVolumeCreates;
  private @Metric MutableCounterLong numVolumeModifies;
  private @Metric MutableCounterLong numVolumeInfos;
  private @Metric MutableCounterLong numBucketCreates;
  private @Metric MutableCounterLong numBucketInfos;

  // Failure Metrics
  private @Metric MutableCounterLong numVolumeCreateFails;
  private @Metric MutableCounterLong numVolumeModifyFails;
  private @Metric MutableCounterLong numVolumeInfoFails;
  private @Metric MutableCounterLong numBucketCreateFails;
  private @Metric MutableCounterLong numBucketInfoFails;

  public KSMMetrics() {
  }

  public static KSMMetrics create() {
    MetricsSystem ms = DefaultMetricsSystem.instance();
    return ms.register("KSMMetrics",
        "Key Space Manager Metrics",
        new KSMMetrics());
  }

  public void incNumVolumeCreates() {
    numVolumeCreates.incr();
  }

  public void incNumVolumeModifies() {
    numVolumeModifies.incr();
  }

  public void incNumVolumeInfos() {
    numVolumeInfos.incr();
  }

  public void incNumBucketCreates() {
    numBucketCreates.incr();
  }

  public void incNumBucketInfos() {
    numBucketInfos.incr();
  }

  public void incNumVolumeCreateFails() {
    numVolumeCreates.incr();
  }

  public void incNumVolumeModifyFails() {
    numVolumeModifyFails.incr();
  }

  public void incNumVolumeInfoFails() {
    numVolumeInfoFails.incr();
  }

  public void incNumBucketCreateFails() {
    numBucketCreateFails.incr();
  }

  public void incNumBucketInfoFails() {
    numBucketInfoFails.incr();
  }

  @VisibleForTesting
  public long getNumVolumeCreates() {
    return numVolumeCreates.value();
  }

  @VisibleForTesting
  public long getNumVolumeModifies() {
    return numVolumeModifies.value();
  }

  @VisibleForTesting
  public long getNumVolumeInfos() {
    return numVolumeInfos.value();
  }

  @VisibleForTesting
  public long getNumBucketCreates() {
    return numBucketCreates.value();
  }

  @VisibleForTesting
  public long getNumBucketInfos() {
    return numBucketInfos.value();
  }

  @VisibleForTesting
  public long getNumVolumeCreateFails() {
    return numVolumeCreateFails.value();
  }

  @VisibleForTesting
  public long getNumVolumeModifyFails() {
    return numVolumeModifyFails.value();
  }

  @VisibleForTesting
  public long getNumVolumeInfoFails() {
    return numVolumeInfoFails.value();
  }

  @VisibleForTesting
  public long getNumBucketCreateFails() {
    return numBucketCreateFails.value();
  }

  @VisibleForTesting
  public long getNumBucketInfoFails() {
    return numBucketInfoFails.value();
  }

}
