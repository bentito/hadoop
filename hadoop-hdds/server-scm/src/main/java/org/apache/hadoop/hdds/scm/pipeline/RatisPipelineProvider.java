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

package org.apache.hadoop.hdds.scm.pipeline;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.ContainerPlacementPolicy;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.SCMContainerPlacementRandom;
import org.apache.hadoop.hdds.scm.node.NodeManager;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements Api for creating ratis pipelines.
 */
public class RatisPipelineProvider implements PipelineProvider {

  private final NodeManager nodeManager;
  private final PipelineStateManager stateManager;

  RatisPipelineProvider(NodeManager nodeManager,
      PipelineStateManager stateManager) {
    this.nodeManager = nodeManager;
    this.stateManager = stateManager;
  }

  /**
   * Create pluggable container placement policy implementation instance.
   *
   * @param nodeManager - SCM node manager.
   * @param conf - configuration.
   * @return SCM container placement policy implementation instance.
   */
  @SuppressWarnings("unchecked")
  // TODO: should we rename ContainerPlacementPolicy to PipelinePlacementPolicy?
  private static ContainerPlacementPolicy createContainerPlacementPolicy(
      final NodeManager nodeManager, final Configuration conf) {
    Class<? extends ContainerPlacementPolicy> implClass =
        (Class<? extends ContainerPlacementPolicy>) conf.getClass(
            ScmConfigKeys.OZONE_SCM_CONTAINER_PLACEMENT_IMPL_KEY,
            SCMContainerPlacementRandom.class);

    try {
      Constructor<? extends ContainerPlacementPolicy> ctor =
          implClass.getDeclaredConstructor(NodeManager.class,
              Configuration.class);
      return ctor.newInstance(nodeManager, conf);
    } catch (RuntimeException e) {
      throw e;
    } catch (InvocationTargetException e) {
      throw new RuntimeException(implClass.getName()
          + " could not be constructed.", e.getCause());
    } catch (Exception e) {
//      LOG.error("Unhandled exception occurred, Placement policy will not " +
//          "be functional.");
      throw new IllegalArgumentException("Unable to load " +
          "ContainerPlacementPolicy", e);
    }
  }


  @Override
  public Pipeline create(ReplicationFactor factor) throws IOException {
    // Get set of datanodes already used for ratis pipeline
    Set<DatanodeDetails> dnsUsed = new HashSet<>();
    stateManager.getPipelines(ReplicationType.RATIS)
        .forEach(p -> dnsUsed.addAll(p.getNodes()));

    // Get list of healthy nodes
    List<DatanodeDetails> dns =
        nodeManager.getNodes(NodeState.HEALTHY)
            .parallelStream()
            .filter(dn -> !dnsUsed.contains(dn))
            .limit(factor.getNumber())
            .collect(Collectors.toList());
    if (dns.size() < factor.getNumber()) {
      String e = String
          .format("Cannot create pipeline of factor %d using %d nodes.",
              factor.getNumber(), dns.size());
      throw new IOException(e);
    }

    return Pipeline.newBuilder()
        .setId(PipelineID.randomId())
        .setState(LifeCycleState.ALLOCATED)
        .setType(ReplicationType.RATIS)
        .setFactor(factor)
        .setNodes(dns)
        .build();
  }

  @Override
  public Pipeline create(List<DatanodeDetails> nodes) throws IOException {
    ReplicationFactor factor = ReplicationFactor.valueOf(nodes.size());
    if (factor == null) {
      throw new IOException(String
          .format("Nodes size=%d does not match any replication factor",
              nodes.size()));
    }
    return Pipeline.newBuilder()
        .setId(PipelineID.randomId())
        .setState(LifeCycleState.ALLOCATED)
        .setType(ReplicationType.RATIS)
        .setFactor(factor)
        .setNodes(nodes)
        .build();
  }
}
