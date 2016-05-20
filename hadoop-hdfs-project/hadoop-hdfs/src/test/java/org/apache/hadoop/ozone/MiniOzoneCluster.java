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
package org.apache.hadoop.ozone;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import com.google.common.base.Supplier;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.test.GenericTestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.protocolPB.StorageContainerLocationProtocolPB;
import org.apache.hadoop.ozone.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.ozone.storage.StorageContainerManager;
import org.apache.hadoop.ozone.web.client.OzoneClient;
import org.apache.hadoop.ozone.web.exceptions.OzoneException;
import org.apache.hadoop.security.UserGroupInformation;

/**
 * MiniOzoneCluster creates a complete in-process Ozone cluster suitable for
 * running tests.  The cluster consists of a StorageContainerManager and
 * multiple DataNodes.  This class subclasses {@link MiniDFSCluster} for
 * convenient reuse of logic for starting DataNodes.  Unlike MiniDFSCluster, it
 * does not start a NameNode, because Ozone does not require a NameNode.
 */
@InterfaceAudience.Private
public class MiniOzoneCluster extends MiniDFSCluster implements Closeable {

  private static final Logger LOG =
      LoggerFactory.getLogger(MiniOzoneCluster.class);

  private static final String USER_AUTH = "hdfs";

  private final OzoneConfiguration conf;
  private final StorageContainerManager scm;

  /**
   * Creates a new MiniOzoneCluster.
   *
   * @param builder cluster builder
   * @param scm StorageContainerManager, already running
   * @throws IOException if there is an I/O error
   */
  private MiniOzoneCluster(Builder builder, StorageContainerManager scm)
        throws IOException {
    super(builder);
    this.conf = builder.conf;
    this.scm = scm;
  }

  /**
   * Builder for configuring the MiniOzoneCluster to run.
   */
  public static class Builder
      extends org.apache.hadoop.hdfs.MiniDFSCluster.Builder {

    private final OzoneConfiguration conf;
    private Optional<String> ozoneHandlerType = Optional.absent();

    /**
     * Creates a new Builder.
     *
     * @param conf configuration
     */
    public Builder(OzoneConfiguration conf) {
      super(conf);
      this.conf = conf;
      this.nnTopology(new MiniDFSNNTopology()); // No NameNode required
    }

    @Override
    public Builder numDataNodes(int val) {
      super.numDataNodes(val);
      return this;
    }

    public Builder setHandlerType(String handler) {
      ozoneHandlerType = Optional.of(handler);
      return this;
    }

    @Override
    public MiniOzoneCluster build() throws IOException {
      if (!ozoneHandlerType.isPresent()) {
        throw new IllegalArgumentException(
            "The Ozone handler type must be specified.");
      }

      conf.setBoolean(OzoneConfigKeys.OZONE_ENABLED, true);
      conf.setBoolean(OzoneConfigKeys.OZONE_TRACE_ENABLED_KEY, true);
      conf.set(OzoneConfigKeys.OZONE_HANDLER_TYPE_KEY, ozoneHandlerType.get());
      conf.set(OzoneConfigKeys.OZONE_SCM_CLIENT_ADDRESS_KEY, "127.0.0.1:0");
      conf.set(OzoneConfigKeys.OZONE_SCM_DATANODE_ADDRESS_KEY, "127.0.0.1:0");
      StorageContainerManager scm = new StorageContainerManager(conf);
      scm.start();
      MiniOzoneCluster cluster = new MiniOzoneCluster(this, scm);
      try {
        cluster.waitOzoneReady();
      } catch(Exception e) {
        // A workaround to propagate MiniOzoneCluster failures without
        // changing the method signature (which would require cascading
        // changes to hundreds of unrelated HDFS tests).
        throw new IOException("Failed to start MiniOzoneCluster", e);
      }
      return cluster;
    }
  }

  @Override
  public void close() {
    shutdown();
  }

  @Override
  public void shutdown() {
    super.shutdown();
    LOG.info("Shutting down the Mini Ozone Cluster");
    if (scm == null) {
      return;
    }
    LOG.info("Shutting down the StorageContainerManager");
    scm.stop();
    scm.join();
  }

  /**
   * Creates an {@link OzoneClient} connected to this cluster's REST service.
   * Callers take ownership of the client and must close it when done.
   *
   * @return OzoneClient connected to this cluster's REST service
   * @throws OzoneException if Ozone encounters an error creating the client
   */
  public OzoneClient createOzoneClient() throws OzoneException {
    Preconditions.checkState(!getDataNodes().isEmpty(),
        "Cannot create OzoneClient if the cluster has no DataNodes.");
    // An Ozone request may originate at any DataNode, so pick one at random.
    int dnIndex = new Random().nextInt(getDataNodes().size());
    String uri = String.format("http://127.0.0.1:%d",
        getDataNodes().get(dnIndex).getInfoPort());
    LOG.info("Creating Ozone client to DataNode {} with URI {} and user {}",
        dnIndex, uri, USER_AUTH);
    try {
      return new OzoneClient(uri, USER_AUTH);
    } catch (URISyntaxException e) {
      // We control the REST service URI, so it should never be invalid.
      throw new IllegalStateException("Unexpected URISyntaxException", e);
    }
  }

  /**
   * Creates an RPC proxy connected to this cluster's StorageContainerManager
   * for accessing container location information.  Callers take ownership of
   * the proxy and must close it when done.
   *
   * @return RPC proxy for accessing container location information
   * @throws IOException if there is an I/O error
   */
  public StorageContainerLocationProtocolClientSideTranslatorPB
      createStorageContainerLocationClient() throws IOException {
    long version = RPC.getProtocolVersion(
        StorageContainerLocationProtocolPB.class);
    InetSocketAddress address = scm.getClientRpcAddress();
    LOG.info(
        "Creating StorageContainerLocationProtocol RPC client with address {}",
        address);
    return new StorageContainerLocationProtocolClientSideTranslatorPB(
        RPC.getProxy(StorageContainerLocationProtocolPB.class, version,
        address, UserGroupInformation.getCurrentUser(), conf,
        NetUtils.getDefaultSocketFactory(conf), Client.getTimeout(conf)));
  }

  /**
   * Waits for the Ozone cluster to be ready for processing requests.
   */
  public void waitOzoneReady() throws TimeoutException, InterruptedException {
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        final DatanodeInfo[] reports =
            scm.getDatanodeReport(DatanodeReportType.LIVE);
        if (reports.length >= numDataNodes) {
          return true;
        }
        LOG.info("Waiting for cluster to be ready. Got {} of {} DN reports.",
            reports.length, numDataNodes);
        return false;
      }
    }, 100, 45000);
  }
}
