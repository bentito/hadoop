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
package org.apache.hadoop.ozone.scm.cli.container;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.hadoop.ozone.scm.cli.OzoneCommandHandler;
import org.apache.hadoop.scm.client.ScmClient;

import java.io.IOException;
import java.util.Arrays;

import static org.apache.hadoop.ozone.scm.cli.SCMCLI.CMD_WIDTH;
import static org.apache.hadoop.ozone.scm.cli.SCMCLI.HELP_OP;
import static org.apache.hadoop.ozone.scm.cli.container.CreateContainerHandler.CONTAINER_CREATE;

/**
 * The handler class of container-specific commands, e.g. createContainer.
 */
public class ContainerCommandHandler extends OzoneCommandHandler {

  public static final String CONTAINER_CMD = "container";

  public ContainerCommandHandler(ScmClient scmClient) {
    super(scmClient);
  }

  @Override
  public void execute(CommandLine cmd) throws IOException {
    // all container commands should contain -container option
    if (!cmd.hasOption(CONTAINER_CMD)) {
      throw new IOException("Expecting container cmd");
    }
    // check which each the sub command it is
    OzoneCommandHandler handler = null;
    if (cmd.hasOption(CONTAINER_CREATE)) {
      handler = new CreateContainerHandler(getScmClient());
    }
    // execute the sub command, throw exception if no sub command found
    // unless -help option is given.
    if (handler != null) {
      handler.execute(cmd);
    } else {
      displayHelp();
      if (!cmd.hasOption(HELP_OP)) {
        throw new IOException("Unrecognized command "
            + Arrays.asList(cmd.getArgs()));
      }
    }
  }

  @Override
  public void displayHelp() {
    Options options = new Options();
    addCommandsOption(options);
    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.printHelp(CMD_WIDTH,
        "hdfs scm -container <commands> <options>",
        "where <commands> can be one of the following", options, "");
  }

  private static void addCommandsOption(Options options) {
    Option createContainer =
        new Option(CONTAINER_CREATE, false, "Create container");
    options.addOption(createContainer);
    // TODO : add other options such as delete, close etc.
  }

  public static void addOptions(Options options) {
    addCommandsOption(options);
    // for create container options.
    CreateContainerHandler.addOptions(options);
    // TODO : add other options such as delete, close etc.
  }
}
