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
package org.apache.hadoop.ozone.protocol.commands;

import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMDeleteBlocksCmdResponseProto;
import org.apache.hadoop.ozone.protocol.proto.StorageContainerDatanodeProtocolProtos.Type;

import java.util.List;

/**
 * A SCM command asks a datanode to delete a number of blocks.
 */
public class DeleteBlocksCommand extends
    SCMCommand<SCMDeleteBlocksCmdResponseProto> {

  private List<DeletedBlocksTransaction> blocksTobeDeleted;


  public DeleteBlocksCommand(List<DeletedBlocksTransaction> blocks) {
    this.blocksTobeDeleted = blocks;
  }

  public List<DeletedBlocksTransaction> blocksTobeDeleted() {
    return this.blocksTobeDeleted;
  }

  @Override
  public Type getType() {
    return Type.deleteBlocksCommand;
  }

  @Override
  public byte[] getProtoBufMessage() {
    return getProto().toByteArray();
  }

  public static DeleteBlocksCommand getFromProtobuf(
      SCMDeleteBlocksCmdResponseProto deleteBlocksProto) {
    return new DeleteBlocksCommand(deleteBlocksProto
        .getDeletedBlocksTransactionsList());
  }

  public SCMDeleteBlocksCmdResponseProto getProto() {
    return SCMDeleteBlocksCmdResponseProto.newBuilder()
        .addAllDeletedBlocksTransactions(blocksTobeDeleted).build();
  }
}
