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
package org.apache.hadoop.cblock.storage;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.cblock.exception.CBlockException;
import org.apache.hadoop.cblock.meta.ContainerDescriptor;
import org.apache.hadoop.cblock.meta.VolumeDescriptor;
import org.apache.hadoop.cblock.meta.VolumeInfo;
import org.apache.hadoop.cblock.proto.MountVolumeResponse;
import org.apache.hadoop.cblock.util.KeyUtil;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.scm.client.ScmClient;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class maintains the key space of CBlock, more specifically, the
 * volume to container mapping. The core data structure
 * is a map from users to their volumes info, where volume info is a handler
 * to a volume, containing information for IO on that volume.
 *
 * and a storage client responsible for talking to the SCM
 *
 * TODO : all the volume operations are fully serialized, which can potentially
 * be optimized.
 *
 * TODO : if the certain operations (e.g. create) failed, the failure-handling
 * logic may not be properly implemented currently.
 */
public class StorageManager {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(StorageManager.class);
  private final ScmClient storageClient;
  /**
   * We will NOT have the situation where same kv pair getting
   * processed, but it is possible to have multiple kv pair being
   * processed at same time.
   *
   * So using just ConcurrentHashMap should be sufficient
   *
   * Again since currently same user accessing from multiple places
   * is not allowed, no need to consider concurrency of volume map
   * within one user
   */
  private ConcurrentHashMap<String, HashMap<String, VolumeDescriptor>>
      user2VolumeMap;
  // size of an underlying container.
  // TODO : assuming all containers are of the same size
  private long containerSizeB;

  public StorageManager(ScmClient storageClient,
      OzoneConfiguration ozoneConfig) throws IOException {
    this.storageClient = storageClient;
    this.user2VolumeMap = new ConcurrentHashMap<>();
    this.containerSizeB = storageClient.getContainerSize(null);
  }

  /**
   * This call will put the volume into in-memory map.
   *
   * more specifically, make the volume discoverable on jSCSI server
   * and keep it's reference in-memory for look up.
   * @param userName the user name of the volume.
   * @param volumeName the name of the volume,
   * @param volume a {@link VolumeDescriptor} object encapsulating the
   *               information about the volume.
   */
  private void makeVolumeReady(String userName, String volumeName,
      VolumeDescriptor volume) {
    HashMap<String, VolumeDescriptor> userVolumes;
    if (user2VolumeMap.containsKey(userName)) {
      userVolumes = user2VolumeMap.get(userName);
    } else {
      userVolumes = new HashMap<>();
      user2VolumeMap.put(userName, userVolumes);
    }
    userVolumes.put(volumeName, volume);
  }

  /**
   * Called by CBlockManager to add volumes read from persistent store into
   * memory, need to contact SCM to setup the reference to the containers given
   * their id.
   *
   * Only for failover process where container meta info is read from
   * persistent store, and containers themselves are alive.
   *
   * TODO : Currently, this method is not being called as failover process
   * is not implemented yet.
   *
   * @param volumeDescriptor a {@link VolumeDescriptor} object encapsulating
   *                         the information about a volume.
   * @throws IOException when adding the volume failed. e.g. volume already
   * exist, or no more container available.
   */
  public synchronized void addVolume(VolumeDescriptor volumeDescriptor)
      throws IOException{
    String userName = volumeDescriptor.getUserName();
    String volumeName = volumeDescriptor.getVolumeName();
    LOGGER.info("addVolume:" + userName + ":" + volumeName);
    if (user2VolumeMap.containsKey(userName)
        && user2VolumeMap.get(userName).containsKey(volumeName)) {
      throw new CBlockException("Volume already exist for "
          + userName + ":" + volumeName);
    }
    // the container ids are read from levelDB, setting up the
    // container handlers here.
    String[] containerIds = volumeDescriptor.getContainerIDs();

    for (String containerId : containerIds) {
      try {
        Pipeline pipeline = storageClient.getContainer(containerId);
        ContainerDescriptor containerDescriptor =
            new ContainerDescriptor(containerId);
        containerDescriptor.setPipeline(pipeline);
        volumeDescriptor.addContainer(containerDescriptor);
      } catch (IOException e) {
        LOGGER.error("Getting container failed! Container:{} error:{}",
            containerId, e);
        throw e;
      }
    }
    // now ready to put into in-memory map.
    makeVolumeReady(userName, volumeName, volumeDescriptor);
  }


  /**
   * Called by CBlock server when creating a fresh volume. The core
   * logic is adding needed information into in-memory meta data.
   *
   * @param userName the user name of the volume.
   * @param volumeName the name of the volume.
   * @param volumeSize the size of the volume.
   * @param blockSize the block size of the volume.
   * @throws CBlockException when the volume can not be created.
   */
  public synchronized void createVolume(String userName, String volumeName,
      long volumeSize, int blockSize) throws CBlockException {
    LOGGER.debug("createVolume:" + userName + ":" + volumeName);
    if (user2VolumeMap.containsKey(userName)
        && user2VolumeMap.get(userName).containsKey(volumeName)) {
      throw new CBlockException("Volume already exist for "
          + userName + ":" + volumeName);
    }
    if (volumeSize < blockSize) {
      throw new CBlockException("Volume size smaller than block size? " +
          "volume size:" + volumeSize + " block size:" + blockSize);
    }
    VolumeDescriptor volume;
    int containerIdx = 0;
    try {
      volume = new VolumeDescriptor(userName, volumeName,
          volumeSize, blockSize);
      long allocatedSize = 0;
      ArrayList<String> containerIds = new ArrayList<>();
      while (allocatedSize < volumeSize) {
        Pipeline pipeline = storageClient.createContainer(
            KeyUtil.getContainerName(userName, volumeName, containerIdx),
            ScmClient.ReplicationFactor.ONE);
        ContainerDescriptor container =
            new ContainerDescriptor(pipeline.getContainerName());
        container.setPipeline(pipeline);
        container.setContainerIndex(containerIdx);
        volume.addContainer(container);
        containerIds.add(container.getContainerID());
        allocatedSize += containerSizeB;
        containerIdx += 1;
      }
      volume.setContainerIDs(containerIds);
    } catch (IOException e) {
      throw new CBlockException("Error when creating volume:" + e.getMessage());
      // TODO : delete already created containers? or re-try policy
    }
    makeVolumeReady(userName, volumeName, volume);
  }

  /**
   * Called by CBlock server to delete a specific volume. Mainly
   * to check whether it can be deleted, and remove it from in-memory meta
   * data.
   *
   * @param userName the user name of the volume.
   * @param volumeName the name of the volume.
   * @param force if set to false, only delete volume it is empty, otherwise
   *              throw exception. if set to true, delete regardless.
   * @throws CBlockException when the volume can not be deleted.
   */
  public synchronized void deleteVolume(String userName, String volumeName,
      boolean force) throws CBlockException {
    if (!user2VolumeMap.containsKey(userName)
        || !user2VolumeMap.get(userName).containsKey(volumeName)) {
      throw new CBlockException("Deleting non-exist volume "
          + userName + ":" + volumeName);
    }
    if (!force && !user2VolumeMap.get(userName).get(volumeName).isEmpty()) {
      throw new CBlockException("Deleting a non-empty volume without force!");
    }
    VolumeDescriptor volume = user2VolumeMap.get(userName).remove(volumeName);
    for (String containerID : volume.getContainerIDsList()) {
      try {
        Pipeline pipeline = storageClient.getContainer(containerID);
        storageClient.deleteContainer(pipeline);
      } catch (IOException e) {
        LOGGER.error("Error deleting container Container:{} error:{}",
            containerID, e);
        throw new CBlockException(e.getMessage());
      }
    }
    if (user2VolumeMap.get(userName).size() == 0) {
      user2VolumeMap.remove(userName);
    }
  }

  /**
   * Called by CBlock server to get information of a specific volume.
   *
   * @param userName the user name of the volume.
   * @param volumeName the name of the volume.
   * @return a {@link VolumeInfo} object encapsulating the information of the
   * volume.
   * @throws CBlockException when the information can not be retrieved.
   */
  public synchronized VolumeInfo infoVolume(String userName, String volumeName)
      throws CBlockException {
    if (!user2VolumeMap.containsKey(userName)
        || !user2VolumeMap.get(userName).containsKey(volumeName)) {
      throw new CBlockException("Getting info for non-exist volume "
          + userName + ":" + volumeName);
    }
    return user2VolumeMap.get(userName).get(volumeName).getInfo();
  }

  /**
   * Called by CBlock server to check whether the given volume can be
   * mounted, i.e. whether it can be found in the meta data.
   *
   * return a {@link MountVolumeResponse} with isValid flag to indicate
   * whether the volume can be mounted or not.
   *
   * @param userName the user name of the volume.
   * @param volumeName the name of the volume
   * @return a {@link MountVolumeResponse} object encapsulating whether the
   * volume is valid, and if yes, the requried information for client to
   * read/write the volume.
   */
  public synchronized MountVolumeResponse isVolumeValid(
      String userName, String volumeName) {
    if (!user2VolumeMap.containsKey(userName)
        || !user2VolumeMap.get(userName).containsKey(volumeName)) {
      // in the case of invalid volume, no need to set any value other than
      // isValid flag.
      return new MountVolumeResponse(false, null, null, 0, 0, null, null);
    }
    VolumeDescriptor volume = user2VolumeMap.get(userName).get(volumeName);
    return new MountVolumeResponse(true, userName,
        volumeName, volume.getVolumeSize(), volume.getBlockSize(),
        volume.getContainerPipelines(), volume.getPipelines());
  }

  /**
   * Called by CBlock manager to list all volumes.
   *
   * @param userName the userName whose volume to be listed, if set to null,
   *                 all volumes will be listed.
   * @return a list of {@link VolumeDescriptor} representing all volumes
   * requested.
   */
  public synchronized List<VolumeDescriptor> getAllVolume(String userName) {
    ArrayList<VolumeDescriptor> allVolumes = new ArrayList<>();
    if (userName == null) {
      for (Map.Entry<String, HashMap<String, VolumeDescriptor>> entry
          : user2VolumeMap.entrySet()) {
        allVolumes.addAll(entry.getValue().values());
      }
    } else {
      if (user2VolumeMap.containsKey(userName)) {
        allVolumes.addAll(user2VolumeMap.get(userName).values());
      }
    }
    return allVolumes;
  }

  /**
   * Only for testing the behavior of create/delete volumes.
   */
  @VisibleForTesting
  public VolumeDescriptor getVolume(String userName, String volumeName) {
    if (!user2VolumeMap.containsKey(userName)
        || !user2VolumeMap.get(userName).containsKey(volumeName)) {
      return null;
    }
    return user2VolumeMap.get(userName).get(volumeName);
  }
}
