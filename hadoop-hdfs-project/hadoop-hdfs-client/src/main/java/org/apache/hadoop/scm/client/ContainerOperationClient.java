/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.scm.client;

import org.apache.hadoop.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.scm.XceiverClient;
import org.apache.hadoop.scm.XceiverClientManager;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.scm.storage.ContainerProtocolCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

/**
 * This class provides the client-facing APIs of container operations.
 */
public class ContainerOperationClient implements ScmClient {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerOperationClient.class);
  private static long containerSizeB = -1;
  private final StorageContainerLocationProtocolClientSideTranslatorPB
      storageContainerLocationClient;
  private final XceiverClientManager xceiverClientManager;

  public ContainerOperationClient(
      StorageContainerLocationProtocolClientSideTranslatorPB
          storageContainerLocationClient,
      XceiverClientManager xceiverClientManager) {
    this.storageContainerLocationClient = storageContainerLocationClient;
    this.xceiverClientManager = xceiverClientManager;
  }

  /**
   * Return the capacity of containers. The current assumption is that all
   * containers have the same capacity. Therefore one static is sufficient for
   * any container.
   * @return The capacity of one container in number of bytes.
   */
  public static long getContainerSizeB() {
    return containerSizeB;
  }

  /**
   * Set the capacity of container. Should be exactly once on system start.
   * @param size Capacity of one container in number of bytes.
   */
  public static void setContainerSizeB(long size) {
    containerSizeB = size;
  }

  /**
   * Create a container with the given ID as its name.
   * @param containerId - String container ID
   * @return A Pipeline object to actually write/read from the container.
   * @throws IOException
   */
  @Override
  public Pipeline createContainer(String containerId)
      throws IOException {
    XceiverClient client = null;
    try {
      Pipeline pipeline =
          storageContainerLocationClient.allocateContainer(containerId);
      client = xceiverClientManager.acquireClient(pipeline);
      String traceID = UUID.randomUUID().toString();
      ContainerProtocolCalls.createContainer(client, traceID);
      LOG.info("Created container " + containerId +
          " leader:" + pipeline.getLeader() +
          " machines:" + pipeline.getMachines());
      return pipeline;
    } finally {
      if (client != null) {
        xceiverClientManager.releaseClient(client);
      }
    }
  }

  /**
   * Delete the container, this will release any resource it uses.
   * @param pipeline - Pipeline that represents the container.
   * @throws IOException
   */
  @Override
  public void deleteContainer(Pipeline pipeline) throws IOException {
    // TODO
  }

  /**
   * Given an id, return the pipeline associated with the container.
   * @param containerId - String Container ID
   * @return Pipeline of the existing container, corresponding to the given id.
   * @throws IOException
   */
  @Override
  public Pipeline getContainer(String containerId) throws
      IOException {
    // TODO
    return null;
  }

  /**
   * Get the the current usage information.
   * @param pipeline - Pipeline
   * @return the size of the given container.
   * @throws IOException
   */
  @Override
  public long getContainerSize(Pipeline pipeline) throws IOException {
    // TODO : Pipeline can be null, handle it correctly.
    long size = getContainerSizeB();
    if (size == -1) {
      throw new IOException("Container size unknown!");
    }
    return size;
  }
}
