/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.ksm;

import com.google.common.base.Preconditions;
import org.apache.hadoop.ksm.helpers.KsmKeyArgs;
import org.apache.hadoop.ksm.helpers.KsmKeyInfo;
import org.apache.hadoop.ozone.ksm.exceptions.KSMException;
import org.apache.hadoop.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.scm.protocol.ScmBlockLocationProtocol;
import org.iq80.leveldb.DBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Implementation of keyManager.
 */
public class KeyManagerImpl implements KeyManager {
  private static final Logger LOG =
      LoggerFactory.getLogger(KeyManagerImpl.class);

  /**
   * A SCM block client, used to talk to SCM to allocate block during putKey.
   */
  private final ScmBlockLocationProtocol scmBlockClient;
  private final MetadataManager metadataManager;

  public KeyManagerImpl(ScmBlockLocationProtocol scmBlockClient,
      MetadataManager metadataManager) {
    this.scmBlockClient = scmBlockClient;
    this.metadataManager = metadataManager;
  }

  @Override
  public KsmKeyInfo allocateKey(KsmKeyArgs args) throws IOException {
    Preconditions.checkNotNull(args);
    metadataManager.writeLock().lock();
    String volumeName = args.getVolumeName();
    String bucketName = args.getBucketName();
    String keyName = args.getKeyName();
    try {
      byte[] volumeKey = metadataManager.getVolumeKey(volumeName);
      byte[] bucketKey = metadataManager.getBucketKey(volumeName, bucketName);
      byte[] keyKey = metadataManager.getDBKeyForKey(
          volumeName, bucketName, keyName);

      //Check if the volume exists
      if(metadataManager.get(volumeKey) == null) {
        LOG.error("volume not found: {}", volumeName);
        throw new KSMException("Volume not found",
            KSMException.ResultCodes.FAILED_VOLUME_NOT_FOUND);
      }
      //Check if bucket already exists
      if(metadataManager.get(bucketKey) == null) {
        LOG.error("bucket not found: {}/{} ", volumeName, bucketName);
        throw new KSMException("Bucket not found",
            KSMException.ResultCodes.FAILED_BUCKET_NOT_FOUND);
      }
      // TODO throw exception if key exists, may change to support key
      // overwrite in the future
      //Check if key already exists.
      if(metadataManager.get(keyKey) != null) {
        LOG.error("key already exist: {}/{}/{} ", volumeName, bucketName,
            keyName);
        throw new KSMException("Key already exist",
            KSMException.ResultCodes.FAILED_KEY_ALREADY_EXISTS);
      }

      AllocatedBlock allocatedBlock =
          scmBlockClient.allocateBlock(args.getDataSize());
      KsmKeyInfo keyBlock = new KsmKeyInfo.Builder()
          .setVolumeName(args.getVolumeName())
          .setBucketName(args.getBucketName())
          .setKeyName(args.getKeyName())
          .setDataSize(args.getDataSize())
          .setBlockID(allocatedBlock.getKey())
          .setContainerName(allocatedBlock.getPipeline().getContainerName())
          .setShouldCreateContainer(allocatedBlock.getCreateContainer())
          .build();
      metadataManager.put(keyKey, keyBlock.getProtobuf().toByteArray());
      LOG.debug("Key {} allocated in volume {} bucket {}",
          keyName, volumeName, bucketName);
      return keyBlock;
    } catch (DBException ex) {
      LOG.error("Key allocation failed for volume:{} bucket:{} key:{}",
          volumeName, bucketName, keyName, ex);
      throw new KSMException(ex.getMessage(),
          KSMException.ResultCodes.FAILED_INTERNAL_ERROR);
    } finally {
      metadataManager.writeLock().unlock();
    }
  }
}
