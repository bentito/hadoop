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
package org.apache.hadoop.ozone.web.handlers;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.UriInfo;

/**
 * UserArgs is used to package caller info
 * and pass it down to file system.
 */
public class UserArgs {
  private String userName;
  private final long requestID;
  private final String hostName;
  private final UriInfo uri;
  private final Request request;
  private final HttpHeaders headers;


  /**
   * Constructs  user args.
   *
   * @param userName - User name
   * @param requestID _ Request ID
   * @param hostName - Host Name
   * @param req  - Request
   * @param info - Uri Info
   * @param httpHeaders - http headers
   */
  public UserArgs(String userName, long requestID, String hostName, Request req,
                  UriInfo info, HttpHeaders httpHeaders) {
    this.hostName = hostName;
    this.userName = userName;
    this.requestID = requestID;
    this.uri = info;
    this.request = req;
    this.headers = httpHeaders;
  }

  /**
   * Returns hostname.
   *
   * @return String
   */
  public String getHostName() {
    return hostName;
  }

  /**
   * Returns RequestID.
   *
   * @return Long
   */
  public long getRequestID() {
    return requestID;
  }

  /**
   * Returns User Name.
   *
   * @return String
   */
  public String getUserName() {
    return userName;
  }

  /**
   * Sets the user name.
   *
   * @param userName Name of the user
   */
  public void setUserName(String userName) {
    this.userName = userName;
  }

  /**
   * Returns the resource Name.
   *
   * @return String Resource.
   */
  public String getResourceName() {
    return getUserName();
  }

  /**
   * Returns Http Headers for this call.
   *
   * @return httpHeaders
   */
  public HttpHeaders getHeaders() {
    return headers;
  }

  /**
   * Returns Request Object.
   *
   * @return Request
   */
  public Request getRequest() {
    return request;
  }

  /**
   * Returns UriInfo.
   *
   * @return UriInfo
   */
  public UriInfo getUri() {
    return uri;
  }
}
