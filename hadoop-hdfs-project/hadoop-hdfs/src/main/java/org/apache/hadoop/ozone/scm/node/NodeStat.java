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
 * Interface that defines Node Stats.
 */
interface NodeStat {
  /**
   * Get capacity of the node.
   * @return capacity of the node.
   */
  long getCapacity();

  /**
   * Get the used space of the node.
   * @return the used space of the node.
   */
  long getScmUsed();

  /**
   * Get the remaining space of the node.
   * @return the remaining space of the node.
   */
  long getRemaining();

  /**
   * Set the total/used/remaining space.
   * @param total - total space.
   * @param used - used space.
   * @param remain - remaining space.
   */
  @VisibleForTesting
  void set(long total, long used, long remain);

  /**
   * Adding of the stat.
   * @param stat - stat to be added.
   * @return updated node stat.
   */
  NodeStat add(NodeStat stat);

  /**
   * Subtract of the stat.
   * @param stat - stat to be subtracted.
   * @return updated nodestat.
   */
  NodeStat subtract(NodeStat stat);
}
