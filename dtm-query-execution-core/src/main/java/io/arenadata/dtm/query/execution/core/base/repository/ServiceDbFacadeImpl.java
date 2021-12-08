/*
 * Copyright © 2021 ProStore
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.arenadata.dtm.query.execution.core.base.repository;

import io.arenadata.dtm.query.execution.core.base.repository.zookeeper.ServiceDbDao;
import io.arenadata.dtm.query.execution.core.delta.repository.zookeeper.DeltaServiceDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ServiceDbFacadeImpl implements ServiceDbFacade {

    private final ServiceDbDao serviceDbDao;
    private final DeltaServiceDao deltaServiceDao;

    @Autowired
    public ServiceDbFacadeImpl(ServiceDbDao serviceDbDao, DeltaServiceDao deltaServiceDao) {
        this.serviceDbDao = serviceDbDao;
        this.deltaServiceDao = deltaServiceDao;
    }

    @Override
    public ServiceDbDao getServiceDbDao() {
        return serviceDbDao;
    }

    @Override
    public DeltaServiceDao getDeltaServiceDao() {
        return deltaServiceDao;
    }
}
