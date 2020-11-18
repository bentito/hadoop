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

import com.google.protobuf.BlockingService;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ksm.helpers.KsmBucketInfo;
import org.apache.hadoop.ksm.helpers.KsmKeyArgs;
import org.apache.hadoop.ksm.helpers.KsmKeyInfo;
import org.apache.hadoop.ksm.helpers.KsmVolumeArgs;
import org.apache.hadoop.ksm.protocol.KeySpaceManagerProtocol;
import org.apache.hadoop.ksm.protocolPB.KeySpaceManagerProtocolPB;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.OzoneClientUtils;
import org.apache.hadoop.ozone.OzoneConfiguration;
import org.apache.hadoop.ozone.protocolPB
    .KeySpaceManagerProtocolServerSideTranslatorPB;
import org.apache.hadoop.ozone.scm.StorageContainerManager;
import org.apache.hadoop.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.scm.protocolPB.ScmBlockLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.scm.protocolPB.ScmBlockLocationProtocolPB;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.apache.hadoop.ozone.ksm.KSMConfigKeys
    .OZONE_KSM_ADDRESS_KEY;
import static org.apache.hadoop.ozone.ksm.KSMConfigKeys
    .OZONE_KSM_HANDLER_COUNT_DEFAULT;
import static org.apache.hadoop.ozone.ksm.KSMConfigKeys
    .OZONE_KSM_HANDLER_COUNT_KEY;
import static org.apache.hadoop.ozone.protocol.proto
    .KeySpaceManagerProtocolProtos.KeySpaceManagerService
    .newReflectiveBlockingService;
import static org.apache.hadoop.util.ExitUtil.terminate;

/**
 * Ozone Keyspace manager is the metadata manager of ozone.
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "CBLOCK", "OZONE", "HBASE"})
public class KeySpaceManager implements KeySpaceManagerProtocol {
  private static final Logger LOG =
      LoggerFactory.getLogger(KeySpaceManager.class);

  private final RPC.Server ksmRpcServer;
  private final InetSocketAddress ksmRpcAddress;
  private final MetadataManager metadataManager;
  private final VolumeManager volumeManager;
  private final BucketManager bucketManager;
  private final KeyManager keyManager;
  private final KSMMetrics metrics;

  public KeySpaceManager(OzoneConfiguration conf) throws IOException {
    final int handlerCount = conf.getInt(OZONE_KSM_HANDLER_COUNT_KEY,
        OZONE_KSM_HANDLER_COUNT_DEFAULT);

    RPC.setProtocolEngine(conf, KeySpaceManagerProtocolPB.class,
        ProtobufRpcEngine.class);

    BlockingService ksmService = newReflectiveBlockingService(
        new KeySpaceManagerProtocolServerSideTranslatorPB(this));
    final InetSocketAddress ksmNodeRpcAddr = OzoneClientUtils.
        getKsmAddress(conf);
    ksmRpcServer = startRpcServer(conf, ksmNodeRpcAddr,
        KeySpaceManagerProtocolPB.class, ksmService,
        handlerCount);
    ksmRpcAddress = updateListenAddress(conf,
        OZONE_KSM_ADDRESS_KEY, ksmNodeRpcAddr, ksmRpcServer);
    metadataManager = new MetadataManagerImpl(conf);
    volumeManager = new VolumeManagerImpl(metadataManager, conf);
    bucketManager = new BucketManagerImpl(metadataManager);
    metrics = KSMMetrics.create();
    keyManager = new KeyManagerImpl(getScmBlockClient(conf), metadataManager);
  }

  /**
   * Create a scm block client, used by putKey() and getKey().
   *
   * @param conf
   * @return
   * @throws IOException
   */
  private ScmBlockLocationProtocol getScmBlockClient(OzoneConfiguration conf)
      throws IOException {
    RPC.setProtocolEngine(conf, ScmBlockLocationProtocolPB.class,
        ProtobufRpcEngine.class);
    long scmVersion =
        RPC.getProtocolVersion(ScmBlockLocationProtocolPB.class);
    InetSocketAddress scmBlockAddress =
        OzoneClientUtils.getScmAddressForBlockClients(conf);
    ScmBlockLocationProtocolClientSideTranslatorPB scmBlockLocationClient =
        new ScmBlockLocationProtocolClientSideTranslatorPB(
            RPC.getProxy(ScmBlockLocationProtocolPB.class, scmVersion,
                scmBlockAddress, UserGroupInformation.getCurrentUser(), conf,
                NetUtils.getDefaultSocketFactory(conf),
                Client.getRpcTimeout(conf)));
    return scmBlockLocationClient;
  }

  /**
   * Starts an RPC server, if configured.
   *
   * @param conf configuration
   * @param addr configured address of RPC server
   * @param protocol RPC protocol provided by RPC server
   * @param instance RPC protocol implementation instance
   * @param handlerCount RPC server handler count
   *
   * @return RPC server
   * @throws IOException if there is an I/O error while creating RPC server
   */
  private static RPC.Server startRpcServer(OzoneConfiguration conf,
      InetSocketAddress addr, Class<?> protocol, BlockingService instance,
      int handlerCount) throws IOException {
    RPC.Server rpcServer = new RPC.Builder(conf)
        .setProtocol(protocol)
        .setInstance(instance)
        .setBindAddress(addr.getHostString())
        .setPort(addr.getPort())
        .setNumHandlers(handlerCount)
        .setVerbose(false)
        .setSecretManager(null)
        .build();

    DFSUtil.addPBProtocol(conf, protocol, instance, rpcServer);
    return rpcServer;
  }

  public KSMMetrics getMetrics() {
    return metrics;
  }

  /**
   * Main entry point for starting KeySpaceManager.
   *
   * @param argv arguments
   * @throws IOException if startup fails due to I/O error
   */
  public static void main(String[] argv) throws IOException {
    StringUtils.startupShutdownMessage(StorageContainerManager.class,
        argv, LOG);
    try {
      KeySpaceManager ksm = new KeySpaceManager(new OzoneConfiguration());
      ksm.start();
      ksm.join();
    } catch (Throwable t) {
      LOG.error("Failed to start the KeyspaceManager.", t);
      terminate(1, t);
    }
  }

  /**
   * Builds a message for logging startup information about an RPC server.
   *
   * @param description RPC server description
   * @param addr RPC server listening address
   * @return server startup message
   */
  private static String buildRpcServerStartMessage(String description,
      InetSocketAddress addr) {
    return addr != null ? String.format("%s is listening at %s",
        description, addr.toString()) :
        String.format("%s not started", description);
  }

  /**
   * After starting an RPC server, updates configuration with the actual
   * listening address of that server. The listening address may be different
   * from the configured address if, for example, the configured address uses
   * port 0 to request use of an ephemeral port.
   *
   * @param conf configuration to update
   * @param rpcAddressKey configuration key for RPC server address
   * @param addr configured address
   * @param rpcServer started RPC server.
   */
  private static InetSocketAddress updateListenAddress(OzoneConfiguration conf,
      String rpcAddressKey, InetSocketAddress addr, RPC.Server rpcServer) {
    InetSocketAddress listenAddr = rpcServer.getListenerAddress();
    InetSocketAddress updatedAddr = new InetSocketAddress(
        addr.getHostString(), listenAddr.getPort());
    conf.set(rpcAddressKey,
        listenAddr.getHostString() + ":" + listenAddr.getPort());
    return updatedAddr;
  }

  /**
   * Start service.
   */
  public void start() {
    LOG.info(buildRpcServerStartMessage("KeyspaceManager RPC server",
        ksmRpcAddress));
    metadataManager.start();
    ksmRpcServer.start();
  }

  /**
   * Stop service.
   */
  public void stop() {
    try {
      ksmRpcServer.stop();
      metadataManager.stop();
    } catch (IOException e) {
      LOG.info("Key Space Manager stop failed.", e);
    }
  }

  /**
   * Wait until service has completed shutdown.
   */
  public void join() {
    try {
      ksmRpcServer.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("Interrupted during KeyspaceManager join.", e);
    }
  }

  /**
   * Creates a volume.
   *
   * @param args - Arguments to create Volume.
   * @throws IOException
   */
  @Override
  public void createVolume(KsmVolumeArgs args) throws IOException {
    try {
      metrics.incNumVolumeCreates();
      volumeManager.createVolume(args);
    } catch (Exception ex) {
      metrics.incNumVolumeCreateFails();
      throw ex;
    }
  }

  /**
   * Changes the owner of a volume.
   *
   * @param volume - Name of the volume.
   * @param owner - Name of the owner.
   * @throws IOException
   */
  @Override
  public void setOwner(String volume, String owner) throws IOException {
    try {
      metrics.incNumVolumeModifies();
      volumeManager.setOwner(volume, owner);
    } catch (Exception ex) {
      metrics.incNumVolumeModifyFails();
      throw ex;
    }
  }

  /**
   * Changes the Quota on a volume.
   *
   * @param volume - Name of the volume.
   * @param quota - Quota in bytes.
   * @throws IOException
   */
  @Override
  public void setQuota(String volume, long quota) throws IOException {
    try {
      metrics.incNumVolumeModifies();
      volumeManager.setQuota(volume, quota);
    } catch (Exception ex) {
      metrics.incNumVolumeModifyFails();
      throw ex;
    }
  }

  /**
   * Checks if the specified user can access this volume.
   *
   * @param volume - volume
   * @param userName - user name
   * @throws IOException
   */
  @Override
  public void checkVolumeAccess(String volume, String userName) throws
      IOException {

  }

  /**
   * Gets the volume information.
   *
   * @param volume - Volume name.
   * @return VolumeArgs or exception is thrown.
   * @throws IOException
   */
  @Override
  public KsmVolumeArgs getVolumeInfo(String volume) throws IOException {
    try {
      metrics.incNumVolumeInfos();
      return volumeManager.getVolumeInfo(volume);
    } catch (Exception ex) {
      metrics.incNumVolumeInfoFails();
      throw ex;
    }
  }

  /**
   * Deletes an existing empty volume.
   *
   * @param volume - Name of the volume.
   * @throws IOException
   */
  @Override
  public void deleteVolume(String volume) throws IOException {

  }

  /**
   * Lists volume owned by a specific user.
   *
   * @param userName - user name
   * @param prefix - Filter prefix -- Return only entries that match this.
   * @param prevKey - Previous key -- List starts from the next from the
   * prevkey
   * @param maxKeys - Max number of keys to return.
   * @return List of Volumes.
   * @throws IOException
   */
  @Override
  public List<KsmVolumeArgs> listVolumeByUser(String userName, String prefix,
      String prevKey, long maxKeys) throws IOException {
    return null;
  }

  /**
   * Lists volume all volumes in the cluster.
   *
   * @param prefix - Filter prefix -- Return only entries that match this.
   * @param prevKey - Previous key -- List starts from the next from the
   * prevkey
   * @param maxKeys - Max number of keys to return.
   * @return List of Volumes.
   * @throws IOException
   */
  @Override
  public List<KsmVolumeArgs> listAllVolumes(String prefix, String prevKey, long
      maxKeys) throws IOException {
    return null;
  }

  /**
   * Creates a bucket.
   *
   * @param bucketInfo - BucketInfo to create bucket.
   * @throws IOException
   */
  @Override
  public void createBucket(KsmBucketInfo bucketInfo) throws IOException {
    try {
      metrics.incNumBucketCreates();
      bucketManager.createBucket(bucketInfo);
    } catch (Exception ex) {
      metrics.incNumBucketCreateFails();
      throw ex;
    }
  }

  /**
   * Gets the bucket information.
   *
   * @param volume - Volume name.
   * @param bucket - Bucket name.
   * @return KsmBucketInfo or exception is thrown.
   * @throws IOException
   */
  @Override
  public KsmBucketInfo getBucketInfo(String volume, String bucket)
      throws IOException {
    try {
      metrics.incNumBucketInfos();
      return bucketManager.getBucketInfo(volume, bucket);
    } catch (Exception ex) {
      metrics.incNumBucketInfoFails();
      throw ex;
    }
  }

  /**
   * Allocate a key.
   *
   * @param args - attributes of the key.
   * @return KsmKeyInfo - the info about the allocated key.
   * @throws IOException
   */
  @Override
  public KsmKeyInfo allocateKey(KsmKeyArgs args) throws IOException {
    try {
      metrics.incNumKeyAllocates();
      return keyManager.allocateKey(args);
    } catch (Exception ex) {
      metrics.incNumKeyAllocateFails();
      throw ex;
    }
  }

  /**
   * Lookup a key.
   *
   * @param args - attributes of the key.
   * @return KsmKeyInfo - the info about the requested key.
   * @throws IOException
   */
  @Override
  public KsmKeyInfo lookupKey(KsmKeyArgs args) throws IOException {
    try {
      metrics.incNumKeyLookups();
      return keyManager.lookupKey(args);
    } catch (Exception ex) {
      metrics.incNumKeyLookupFails();
      throw ex;
    }
  }
}
