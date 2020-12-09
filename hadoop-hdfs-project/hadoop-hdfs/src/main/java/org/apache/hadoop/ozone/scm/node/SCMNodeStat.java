/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.scm.node;

import com.google.common.annotations.VisibleForTesting;

/**
 * This class represents the SCM node stat.
 */
public final class SCMNodeStat implements NodeStat {
  private long capacity;
  private long scmUsed;
  private long remaining;

  public SCMNodeStat() {
  }

  public SCMNodeStat(final SCMNodeStat other) {
    set(other.capacity, other.scmUsed, other.remaining);
  }

  /**
   * @return the total configured capacity of the node.
   */
  @Override
  public long getCapacity() {
    return capacity;
  }

  /**
   * @return the total SCM used space on the node.
   */
  @Override
  public long getScmUsed() {
    return scmUsed;
  }

  /**
   * @return the total remaining space available on the node.
   */
  @Override
  public long getRemaining() {
    return remaining;
  }

  @VisibleForTesting
  @Override
  public void set(final long total, final long used, final long remain) {
    this.capacity = total;
    this.scmUsed = used;
    this.remaining = remain;
  }

  @Override
  public SCMNodeStat add(final NodeStat stat) {
    this.capacity += stat.getCapacity();
    this.scmUsed += stat.getScmUsed();
    this.remaining += stat.getRemaining();
    return this;
  }

  @Override
  public SCMNodeStat subtract(final NodeStat stat) {
    this.capacity -= stat.getCapacity();
    this.scmUsed -= stat.getScmUsed();
    this.remaining -= stat.getRemaining();
    return this;
  }

  @Override
  public boolean equals(final Object to) {
    return this == to
        || (to instanceof SCMNodeStat
        && capacity == ((SCMNodeStat) to).getCapacity()
        && scmUsed == ((SCMNodeStat) to).getScmUsed()
        && remaining == ((SCMNodeStat) to).getRemaining());
  }

  @Override
  public int hashCode() {
    assert false : "hashCode not designed";
    return 42; // any arbitrary constant will do
  }
}
