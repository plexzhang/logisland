/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.engine;

import com.hurence.logisland.component.AbstractConfigurableComponent;
import com.hurence.logisland.component.ValidationContext;
import com.hurence.logisland.component.ValidationResult;

import java.util.Collection;
import java.util.Collections;

public abstract class AbstractProcessingEngine extends AbstractConfigurableComponent implements ProcessingEngine {

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext context){


        return Collections.emptySet();
    }
}