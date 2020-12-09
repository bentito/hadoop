/*
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
package org.apache.hadoop.cblock.jscsiHelper.cache.impl;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.cblock.jscsiHelper.cache.LogicalBlock;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.scm.XceiverClientManager;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.utils.LevelDBStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.hadoop.cblock.CBlockConfigKeys.DFS_CBLOCK_CACHE_BLOCK_BUFFER_SIZE;
import static org.apache.hadoop.cblock.CBlockConfigKeys.DFS_CBLOCK_CACHE_BLOCK_BUFFER_SIZE_DEFAULT;

/**
 * A Queue that is used to write blocks asynchronously to the container.
 */
public class AsyncBlockWriter {
  private static final Logger LOG =
      LoggerFactory.getLogger(AsyncBlockWriter.class);

  /**
   * Right now we have a single buffer and we block when we write it to
   * the file.
   */
  private final ByteBuffer blockIDBuffer;

  /**
   * XceiverClientManager is used to get client connections to a set of
   * machines.
   */
  private final XceiverClientManager xceiverClientManager;

  /**
   * This lock is used as a signal to re-queuing thread. The requeue thread
   * wakes up as soon as it is signaled some blocks are in the retry queue.
   * We try really aggressively since this new block will automatically move
   * to the end of the queue.
   * <p>
   * In the event a container is unavailable for a long time, we can either
   * fail all writes or remap and let the writes succeed. The easier
   * semantics is to fail the volume until the container is recovered by SCM.
   */
  private final Lock lock;
  private final Condition notEmpty;
  /**
   * The cache this writer is operating against.
   */
  private final CBlockLocalCache parentCache;
  private final int blockBufferSize;
  private final static String DIRTY_LOG_PREFIX = "DirtyLog";
  private AtomicLong localIoCount;

  /**
   * Constructs an Async Block Writer.
   *
   * @param config - Config
   * @param cache - Parent Cache for this writer
   */
  public AsyncBlockWriter(Configuration config, CBlockLocalCache cache) {

    Preconditions.checkNotNull(cache, "Cache cannot be null.");
    Preconditions.checkNotNull(cache.getCacheDB(), "DB cannot be null.");
    localIoCount = new AtomicLong();
    blockBufferSize = config.getInt(DFS_CBLOCK_CACHE_BLOCK_BUFFER_SIZE,
        DFS_CBLOCK_CACHE_BLOCK_BUFFER_SIZE_DEFAULT) * 1024;
    LOG.info("Cache: Block Size: {}", blockBufferSize);
    lock = new ReentrantLock();
    notEmpty = lock.newCondition();
    parentCache = cache;
    xceiverClientManager = cache.getClientManager();
    blockIDBuffer = ByteBuffer.allocateDirect(blockBufferSize);
  }

  /**
   * Return the log to write to.
   *
   * @return Logger.
   */
  public static Logger getLOG() {
    return LOG;
  }

  /**
   * Get the CacheDB.
   *
   * @return LevelDB Handle
   */
  LevelDBStore getCacheDB() {
    return parentCache.getCacheDB();
  }

  /**
   * Returns the client manager.
   *
   * @return XceiverClientManager
   */
  XceiverClientManager getXceiverClientManager() {
    return xceiverClientManager;
  }

  /**
   * Incs the localIoPacket Count that has gone into this device.
   */
  public long incrementLocalIO() {
    return localIoCount.incrementAndGet();
  }

  /**
   * Return the local io counts to this device.
   * @return the count of io
   */
  public long getLocalIOCount() {
    return localIoCount.get();
  }

  /**
   * Writes a block to LevelDB store and queues a work item for the system to
   * sync the block to containers.
   *
   * @param block - Logical Block
   */
  public void writeBlock(LogicalBlock block) throws IOException {
    byte[] keybuf = Longs.toByteArray(block.getBlockID());
    if (parentCache.isShortCircuitIOEnabled()) {
      long startTime = Time.monotonicNow();
      getCacheDB().put(keybuf, block.getData().array());
      incrementLocalIO();
      long endTime = Time.monotonicNow();
      parentCache.getTargetMetrics().updateDBWriteLatency(
                                                    endTime - startTime);
      if (parentCache.isTraceEnabled()) {
        String datahash = DigestUtils.sha256Hex(block.getData().array());
        parentCache.getTracer().info(
            "Task=WriterTaskDBPut,BlockID={},Time={},SHA={}",
            block.getBlockID(), endTime - startTime, datahash);
      }
      block.clearData();
    } else {
      // TODO : Support Direct I/O
      LOG.error("Non-Cache I/O is not supported at this point of time.");
      throw new IllegalStateException("Cache is required and cannot be " +
          "disabled now.");
    }
    if (blockIDBuffer.remaining() <= (Long.SIZE / Byte.SIZE)) {
      long startTime = Time.monotonicNow();
      blockIDBuffer.flip();
      writeBlockBufferToFile(blockIDBuffer);
      blockIDBuffer.clear();
      long endTime = Time.monotonicNow();
      if (parentCache.isTraceEnabled()) {
        parentCache.getTracer().info(
            "Task=DirtyBlockLogWrite,Time={}", endTime - startTime);
      }
    }
    blockIDBuffer.putLong(block.getBlockID());
  }

  /**
   * Write Block Buffer to file.
   *
   * @param blockID - ByteBuffer
   * @throws IOException
   */
  private void writeBlockBufferToFile(ByteBuffer blockID)
      throws IOException {
    boolean append = false;
    String fileName =
        String.format("%s.%s", DIRTY_LOG_PREFIX, Time.monotonicNow());
    File logDir = new File(parentCache.getDbPath().toString());
    if (!logDir.exists() && !logDir.mkdirs()) {
      LOG.error("Unable to create the log directory, Critical error cannot " +
          "continue. Log Dir : {}", logDir);
      throw new IllegalStateException("Cache Directory create failed, Cannot " +
          "continue. Log Dir: {}" + logDir);
    }
    String log = Paths.get(parentCache.getDbPath().toString(), fileName)
        .toString();

    try (FileChannel channel = new FileOutputStream(log, append).getChannel()) {
      channel.write(blockID);
    }
    blockID.clear();
    parentCache.processDirtyMessage(fileName);
  }

  /**
   * Shutdown by writing any pending I/O to dirtylog buffer.
   */
  public void shutdown() {
    try {
      writeBlockBufferToFile(this.blockIDBuffer);
    } catch (IOException e) {
      LOG.error("Unable to sync the Block map to disk -- This might cause a " +
          "data loss or corruption");
    }
  }
  /**
   * Returns tracer.
   *
   * @return Tracer
   */
  Logger getTracer() {
    return parentCache.getTracer();
  }

}
