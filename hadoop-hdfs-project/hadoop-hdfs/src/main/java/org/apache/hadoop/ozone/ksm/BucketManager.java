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

import org.apache.hadoop.ksm.helpers.KsmBucketArgs;
import org.apache.hadoop.ksm.helpers.KsmBucketInfo;

import java.io.IOException;

/**
 * BucketManager handles all the bucket level operations.
 */
public interface BucketManager {
  /**
   * Creates a bucket.
   * @param bucketInfo - KsmBucketInfo for creating bucket.
   */
  void createBucket(KsmBucketInfo bucketInfo) throws IOException;
  /**
   * Returns Bucket Information.
   * @param volumeName - Name of the Volume.
   * @param bucketName - Name of the Bucket.
   */
  KsmBucketInfo getBucketInfo(String volumeName, String bucketName)
      throws IOException;

  /**
   * Sets bucket property from args.
   * @param args - BucketArgs.
   * @throws IOException
   */
  void setBucketProperty(KsmBucketArgs args) throws IOException;
}
