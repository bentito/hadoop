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
package org.apache.hadoop.ozone.protocolPB;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.scm.container.common.helpers.DeleteBlockResult;
import org.apache.hadoop.scm.protocol.LocatedContainer;
import org.apache.hadoop.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.scm.protocol.StorageContainerLocationProtocol;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos
    .GetStorageContainerLocationsRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos
    .GetStorageContainerLocationsResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos
    .ScmLocatedBlockProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.LocatedContainerProto;
import static org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.ContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.ContainerResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.AllocateScmBlockRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.AllocateScmBlockResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.DeleteScmBlocksRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.DeleteScmBlocksResponseProto;
import static org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.DeleteScmBlockResult;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.GetScmBlockLocationsRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.GetScmBlockLocationsResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.GetContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.GetContainerResponseProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.DeleteContainerRequestProto;
import org.apache.hadoop.ozone.protocol.proto
    .StorageContainerLocationProtocolProtos.DeleteContainerResponseProto;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.scm.protocolPB.StorageContainerLocationProtocolPB;

/**
 * This class is the server-side translator that forwards requests received on
 * {@link StorageContainerLocationProtocolPB} to the
 * {@link StorageContainerLocationProtocol} server implementation.
 */
@InterfaceAudience.Private
public final class StorageContainerLocationProtocolServerSideTranslatorPB
    implements StorageContainerLocationProtocolPB {

  private final StorageContainerLocationProtocol impl;
  private final ScmBlockLocationProtocol blockImpl;

  /**
   * Creates a new StorageContainerLocationProtocolServerSideTranslatorPB.
   *
   * @param impl {@link StorageContainerLocationProtocol} server implementation
   */
  public StorageContainerLocationProtocolServerSideTranslatorPB(
      StorageContainerLocationProtocol impl,
      ScmBlockLocationProtocol blockImpl) throws IOException {
    this.impl = impl;
    this.blockImpl = blockImpl;
  }

  @Override
  public GetStorageContainerLocationsResponseProto getStorageContainerLocations(
      RpcController unused, GetStorageContainerLocationsRequestProto req)
      throws ServiceException {
    Set<String> keys = Sets.newLinkedHashSetWithExpectedSize(
        req.getKeysCount());
    for (String key : req.getKeysList()) {
      keys.add(key);
    }
    final Set<LocatedContainer> locatedContainers;
    try {
      locatedContainers = impl.getStorageContainerLocations(keys);
    } catch (IOException e) {
      throw new ServiceException(e);
    }
    GetStorageContainerLocationsResponseProto.Builder resp =
        GetStorageContainerLocationsResponseProto.newBuilder();
    for (LocatedContainer locatedContainer : locatedContainers) {
      LocatedContainerProto.Builder locatedContainerProto =
          LocatedContainerProto.newBuilder()
              .setKey(locatedContainer.getKey())
              .setMatchedKeyPrefix(locatedContainer.getMatchedKeyPrefix())
              .setContainerName(locatedContainer.getContainerName());
      for (DatanodeInfo location : locatedContainer.getLocations()) {
        locatedContainerProto.addLocations(PBHelperClient.convert(location));
      }
      locatedContainerProto.setLeader(
          PBHelperClient.convert(locatedContainer.getLeader()));
      resp.addLocatedContainers(locatedContainerProto.build());
    }
    return resp.build();
  }

  @Override
  public ContainerResponseProto allocateContainer(RpcController unused,
      ContainerRequestProto request) throws ServiceException {
    try {
      Pipeline pipeline = impl.allocateContainer(request.getContainerName());
      return ContainerResponseProto.newBuilder()
          .setPipeline(pipeline.getProtobufMessage())
          .setErrorCode(ContainerResponseProto.Error.success)
          .build();

    } catch (IOException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public GetContainerResponseProto getContainer(
      RpcController controller, GetContainerRequestProto request)
      throws ServiceException {
    try {
      Pipeline pipeline = impl.getContainer(request.getContainerName());
      return GetContainerResponseProto.newBuilder()
          .setPipeline(pipeline.getProtobufMessage())
          .build();
    } catch (IOException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public DeleteContainerResponseProto deleteContainer(
      RpcController controller, DeleteContainerRequestProto request)
      throws ServiceException {
    try {
      impl.deleteContainer(request.getContainerName());
      return DeleteContainerResponseProto.newBuilder().build();
    } catch (IOException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public GetScmBlockLocationsResponseProto getScmBlockLocations(
      RpcController controller, GetScmBlockLocationsRequestProto req)
      throws ServiceException {
    Set<String> keys = Sets.newLinkedHashSetWithExpectedSize(
        req.getKeysCount());
    for (String key : req.getKeysList()) {
      keys.add(key);
    }
    final Set<AllocatedBlock> blocks;
    try {
      blocks = blockImpl.getBlockLocations(keys);
    } catch (IOException ex) {
      throw new ServiceException(ex);
    }
    GetScmBlockLocationsResponseProto.Builder resp =
        GetScmBlockLocationsResponseProto.newBuilder();
    for (AllocatedBlock block: blocks) {
      ScmLocatedBlockProto.Builder locatedBlock =
          ScmLocatedBlockProto.newBuilder()
              .setKey(block.getKey())
              .setPipeline(block.getPipeline().getProtobufMessage());
      resp.addLocatedBlocks(locatedBlock.build());
    }
    return resp.build();
  }

  @Override
  public AllocateScmBlockResponseProto allocateScmBlock(
      RpcController controller, AllocateScmBlockRequestProto request)
      throws ServiceException {
    try {
      AllocatedBlock allocatedBlock =
          blockImpl.allocateBlock(request.getSize());
      if (allocatedBlock != null) {
        return
            AllocateScmBlockResponseProto.newBuilder()
            .setKey(allocatedBlock.getKey())
            .setPipeline(allocatedBlock.getPipeline().getProtobufMessage())
            .setCreateContainer(allocatedBlock.getCreateContainer())
            .setErrorCode(AllocateScmBlockResponseProto.Error.success)
            .build();
      } else {
        return
            AllocateScmBlockResponseProto.newBuilder()
            .setErrorCode(AllocateScmBlockResponseProto.Error.unknownFailure)
            .build();
      }
    } catch (IOException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public DeleteScmBlocksResponseProto deleteScmBlocks(
      RpcController controller, DeleteScmBlocksRequestProto req)
      throws ServiceException {
    Set<String> keys = Sets.newLinkedHashSetWithExpectedSize(
        req.getKeysCount());
    for (String key : req.getKeysList()) {
      keys.add(key);
    }
    final List<DeleteBlockResult> results = blockImpl.deleteBlocks(keys);
    DeleteScmBlocksResponseProto.Builder resp =
        DeleteScmBlocksResponseProto.newBuilder();
    for (DeleteBlockResult result: results) {
      DeleteScmBlockResult.Builder deleteResult = DeleteScmBlockResult
          .newBuilder().setKey(result.getKey()).setResult(result.getResult());
      resp.addResults(deleteResult.build());
    }
    return resp.build();
  }
}
