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
package io.arenadata.dtm.query.execution.core.dml.service.impl;

import io.arenadata.dtm.common.metrics.RequestMetrics;
import io.arenadata.dtm.common.model.SqlProcessingType;
import io.arenadata.dtm.common.model.ddl.ColumnType;
import io.arenadata.dtm.common.model.ddl.SystemMetadata;
import io.arenadata.dtm.common.reader.QueryResult;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.query.calcite.core.extension.dml.DmlType;
import io.arenadata.dtm.query.calcite.core.extension.dml.SqlUseSchema;
import io.arenadata.dtm.query.execution.core.base.repository.ServiceDbFacade;
import io.arenadata.dtm.query.execution.core.base.repository.zookeeper.DatamartDao;
import io.arenadata.dtm.query.execution.core.dml.dto.DmlRequestContext;
import io.arenadata.dtm.query.execution.core.base.exception.datamart.DatamartNotExistsException;
import io.arenadata.dtm.query.execution.core.dml.service.DmlExecutor;
import io.arenadata.dtm.query.execution.core.metrics.service.MetricsService;
import io.arenadata.dtm.query.execution.core.ddl.utils.ParseQueryUtils;
import io.arenadata.dtm.query.execution.model.metadata.ColumnMetadata;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class UseSchemaDmlExecutor implements DmlExecutor {

    public static final String SCHEMA_COLUMN_NAME = "schema";
    private final DatamartDao datamartDao;
    private final ParseQueryUtils parseQueryUtils;
    private final MetricsService<RequestMetrics> metricsService;

    @Autowired
    public UseSchemaDmlExecutor(ServiceDbFacade serviceDbFacade,
                                ParseQueryUtils parseQueryUtils,
                                MetricsService<RequestMetrics> metricsService) {
        this.datamartDao = serviceDbFacade.getServiceDbDao().getDatamartDao();
        this.parseQueryUtils = parseQueryUtils;
        this.metricsService = metricsService;
    }

    @Override
    public Future<QueryResult> execute(DmlRequestContext context) {
        return sendMetricsAndExecute(context);
    }

    private Future<QueryResult> sendMetricsAndExecute(DmlRequestContext context) {
        return Future.future(promise -> {
            String datamart = parseQueryUtils.getDatamartName(((SqlUseSchema) context.getSqlNode()).getOperandList());
            datamartDao.existsDatamart(datamart)
                    .onComplete(metricsService.sendMetrics(SourceType.INFORMATION_SCHEMA,
                            SqlProcessingType.DML,
                            context.getMetrics(),
                            ar -> {
                                if (ar.succeeded()) {
                                    if (ar.result()) {
                                        promise.complete(createQueryResult(context, datamart));
                                    } else {
                                        promise.fail(new DatamartNotExistsException(datamart));
                                    }
                                } else {
                                    promise.fail(ar.cause());
                                }
                            }));
        });
    }

    private QueryResult createQueryResult(DmlRequestContext context, String datamart) {
        Map<String, Object> rowMap = new HashMap<>();
        rowMap.put(SCHEMA_COLUMN_NAME, datamart);
        return QueryResult.builder()
                .metadata(Collections.singletonList(new ColumnMetadata(SCHEMA_COLUMN_NAME, SystemMetadata.SCHEMA, ColumnType.VARCHAR)))
                .requestId(context.getRequest().getQueryRequest().getRequestId())
                .result(Collections.singletonList(rowMap))
                .build();
    }

    @Override
    public DmlType getType() {
        return DmlType.USE;
    }
}
