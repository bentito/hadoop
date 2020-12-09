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
package org.apache.hadoop.ozone.scm.cli;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hdfs.ozone.protocol.proto.ContainerProtos.Pipeline;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.utils.LevelDBStore;
import org.iq80.leveldb.DBIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.hadoop.ozone.OzoneConsts.CONTAINER_DB;

/**
 * This is the CLI that can be use to convert a levelDB into a sqlite DB file.
 */
public class SQLCLI  extends Configured implements Tool {

  private Options options;
  private BasicParser parser;
  private final Charset encoding = Charset.forName("UTF-8");

  // for container.db
  private static final String CREATE_CONTAINER_INFO =
      "CREATE TABLE containerInfo (" +
          "containerName TEXT PRIMARY KEY NOT NULL , " +
          "leaderUUID TEXT NOT NULL)";
  private static final String CREATE_CONTAINER_MACHINE =
      "CREATE TABLE containerMembers (" +
          "containerName INTEGER NOT NULL, " +
          "datanodeUUID INTEGER NOT NULL," +
          "PRIMARY KEY(containerName, datanodeUUID));";
  private static final String CREATE_DATANODE_INFO =
      "CREATE TABLE datanodeInfo (" +
          "hostName TEXT NOT NULL, " +
          "datanodeUUId TEXT PRIMARY KEY NOT NULL," +
          "ipAddr TEXT, " +
          "xferPort INTEGER," +
          "infoPort INTEGER," +
          "ipcPort INTEGER," +
          "infoSecurePort INTEGER," +
          "containerPort INTEGER NOT NULL);";
  private static final String INSERT_CONTAINER_INFO =
      "INSERT INTO containerInfo (containerName, leaderUUID) " +
          "VALUES (\"%s\", \"%s\")";
  private static final String INSERT_DATANODE_INFO =
      "INSERT INTO datanodeInfo (hostname, datanodeUUid, ipAddr, xferPort, " +
          "infoPort, ipcPort, infoSecurePort, containerPort) " +
          "VALUES (\"%s\", \"%s\", \"%s\", %d, %d, %d, %d, %d)";
  private static final String INSERT_CONTAINER_MEMBERS =
      "INSERT INTO containerMembers (containerName, datanodeUUID) " +
          "VALUES (\"%s\", \"%s\")";


  private static final Logger LOG =
      LoggerFactory.getLogger(SQLCLI.class);

  public SQLCLI() {
    this.options = getOptions();
    this.parser = new BasicParser();
  }

  @SuppressWarnings("static-access")
  private Options getOptions() {
    Options allOptions = new Options();
    Option dbPathOption = OptionBuilder
        .withArgName("levelDB path")
        .withLongOpt("dbPath")
        .hasArgs(1)
        .withDescription("specify levelDB path")
        .create("p");
    allOptions.addOption(dbPathOption);

    Option outPathOption = OptionBuilder
        .withArgName("output path")
        .withLongOpt("outPath")
        .hasArgs(1)
        .withDescription("specify output path")
        .create("o");
    allOptions.addOption(outPathOption);
    return allOptions;
  }

  @Override
  public int run(String[] args) throws Exception {
    CommandLine commandLine = parseArgs(args);
    if (!commandLine.hasOption("p") || !commandLine.hasOption("o")) {
      LOG.error("Require dbPath option(-p) AND outPath option (-o)");
      return -1;
    }
    String value = commandLine.getOptionValue("p");
    LOG.info("levelDB path {}", value);
    // the value is supposed to be an absolute path to a container file
    Path dbPath = Paths.get(value);
    if (!Files.exists(dbPath)) {
      LOG.error("DB path not exist:{}", dbPath);
    }
    Path parentPath = dbPath.getParent();
    Path dbName = dbPath.getFileName();
    if (parentPath == null || dbName == null) {
      LOG.error("Error processing db path {}", dbPath);
      return -1;
    }

    value = commandLine.getOptionValue("o");
    Path outPath = Paths.get(value);
    if (outPath == null || outPath.getParent() == null) {
      LOG.error("Error processing output path {}", outPath);
      return -1;
    }
    if (!Files.exists(outPath.getParent())) {
      Files.createDirectories(outPath.getParent());
    }
    LOG.info("Parent path [{}] db name [{}]", parentPath, dbName);
    if (dbName.toString().equals(CONTAINER_DB)) {
      LOG.info("Converting container DB");
      convertContainerDB(dbPath, outPath);
    } else {
      LOG.error("Unrecognized db name {}", dbName);
    }
    return 0;
  }

  private Connection connectDB(String dbPath) throws Exception {
    Class.forName("org.sqlite.JDBC");
    String connectPath =
        String.format("jdbc:sqlite:%s", dbPath);
    return DriverManager.getConnection(connectPath);
  }

  private void closeDB(Connection conn) throws SQLException {
    conn.close();
  }

  private void executeSQL(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(sql);
    }
  }

  /**
   * Convert container.db to sqlite. The schema of sql db:
   * three tables, containerId, containerMachines, datanodeInfo
   * (* for primary key)
   *
   * containerInfo:
   * ----------------------------------------------
   * container name* | container lead datanode uuid
   * ----------------------------------------------
   *
   * containerMembers:
   * --------------------------------
   * container name* |  datanodeUUid*
   * --------------------------------
   *
   * datanodeInfo:
   * ---------------------------------------------------------
   * hostname | datanodeUUid* | xferPort | infoPort | ipcPort
   * ---------------------------------------------------------
   *
   * --------------------------------
   * | infoSecurePort | containerPort
   * --------------------------------
   *
   * @param dbPath path to container db.
   * @throws IOException throws exception.
   */
  private void convertContainerDB(Path dbPath, Path outPath)
      throws Exception {
    LOG.info("Create tables for sql container db.");
    File dbFile = dbPath.toFile();
    org.iq80.leveldb.Options dbOptions = new org.iq80.leveldb.Options();
    LevelDBStore dbStore = new LevelDBStore(dbFile, dbOptions);

    Connection conn = connectDB(outPath.toString());
    executeSQL(conn, CREATE_CONTAINER_INFO);
    executeSQL(conn, CREATE_CONTAINER_MACHINE);
    executeSQL(conn, CREATE_DATANODE_INFO);

    DBIterator iter = dbStore.getIterator();
    iter.seekToFirst();
    HashSet<String> uuidChecked = new HashSet<>();
    while(iter.hasNext()) {
      Map.Entry<byte[], byte[]> entry = iter.next();
      String containerName = new String(entry.getKey(), encoding);
      Pipeline pipeline = Pipeline.parseFrom(entry.getValue());
      insertContainerDB(conn, containerName, pipeline, uuidChecked);
    }
    closeDB(conn);
    dbStore.close();
  }

  /**
   * Insert into the sqlite DB of container.db.
   * @param conn the connection to the sqlite DB.
   * @param containerName the name of the container.
   * @param pipeline the actual container pipeline object.
   * @param uuidChecked the uuid that has been already inserted.
   * @throws SQLException throws exception.
   */
  private void insertContainerDB(Connection conn, String containerName,
      Pipeline pipeline, Set<String> uuidChecked) throws SQLException {
    LOG.info("Insert to sql container db, for container {}", containerName);
    String insertContainerInfo = String.format(
        INSERT_CONTAINER_INFO, containerName, pipeline.getLeaderID());
    executeSQL(conn, insertContainerInfo);

    for (HdfsProtos.DatanodeIDProto dnID : pipeline.getMembersList()) {
      String uuid = dnID.getDatanodeUuid();
      if (!uuidChecked.contains(uuid)) {
        // we may also not use this checked set, but catch exception instead
        // but this seems a bit cleaner.
        String ipAddr = dnID.getIpAddr();
        String hostName = dnID.getHostName();
        int xferPort = dnID.hasXferPort() ? dnID.getXferPort() : 0;
        int infoPort = dnID.hasInfoPort() ? dnID.getInfoPort() : 0;
        int securePort =
            dnID.hasInfoSecurePort() ? dnID.getInfoSecurePort() : 0;
        int ipcPort = dnID.hasIpcPort() ? dnID.getIpcPort() : 0;
        int containerPort = dnID.getContainerPort();
        String insertMachineInfo = String.format(
            INSERT_DATANODE_INFO, hostName, uuid, ipAddr, xferPort, infoPort,
            ipcPort, securePort, containerPort);
        executeSQL(conn, insertMachineInfo);
        uuidChecked.add(uuid);
      }
      String insertContainerMembers = String.format(
          INSERT_CONTAINER_MEMBERS, containerName, uuid);
      executeSQL(conn, insertContainerMembers);
    }
    LOG.info("Insertion completed.");
  }

  private CommandLine parseArgs(String[] argv)
      throws ParseException {
    return parser.parse(options, argv);
  }

  public static void main(String[] args) {
    Tool shell = new SQLCLI();
    int res = 0;
    try {
      ToolRunner.run(shell, args);
    } catch (Exception ex) {
      LOG.error(ex.toString());
      res = 1;
    }
    System.exit(res);
  }
}
