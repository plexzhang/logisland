/**
 * Copyright (C) 2016 Hurence (support@hurence.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.kafka.registry.exceptions;

/**
 * Indicates that some schema registry operation timed out.
 */
public class RegistryTimeoutException extends RegistryException {

  public RegistryTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }

  public RegistryTimeoutException(String message) {
    super(message);
  }

  public RegistryTimeoutException(Throwable cause) {
    super(cause);
  }

  public RegistryTimeoutException() {
    super();
  }
}
