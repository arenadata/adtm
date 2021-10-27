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
package io.arenadata.dtm.query.execution.plugin.adqm.mppw.kafka.service;

import io.arenadata.dtm.common.model.ddl.ColumnType;
import io.arenadata.dtm.common.reader.QueryResult;
import io.arenadata.dtm.query.execution.model.metadata.ColumnMetadata;
import io.arenadata.dtm.query.execution.plugin.adqm.base.configuration.AppConfiguration;
import io.arenadata.dtm.query.execution.plugin.adqm.base.utils.AdqmDdlUtil;
import io.arenadata.dtm.query.execution.plugin.adqm.ddl.configuration.properties.DdlProperties;
import io.arenadata.dtm.query.execution.plugin.adqm.factory.AdqmCommonSqlFactory;
import io.arenadata.dtm.query.execution.plugin.adqm.mppw.kafka.dto.RestMppwKafkaStopRequest;
import io.arenadata.dtm.query.execution.plugin.adqm.mppw.kafka.service.load.RestLoadClient;
import io.arenadata.dtm.query.execution.plugin.adqm.query.service.DatabaseExecutor;
import io.arenadata.dtm.query.execution.plugin.adqm.status.dto.StatusReportDto;
import io.arenadata.dtm.query.execution.plugin.adqm.status.service.StatusReporter;
import io.arenadata.dtm.query.execution.plugin.api.exception.MppwDatasourceException;
import io.arenadata.dtm.query.execution.plugin.api.mppw.kafka.MppwKafkaRequest;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.arenadata.dtm.query.execution.plugin.adqm.base.utils.AdqmDdlUtil.sequenceAll;
import static io.arenadata.dtm.query.execution.plugin.adqm.base.utils.AdqmDdlUtil.splitQualifiedTableName;
import static io.arenadata.dtm.query.execution.plugin.adqm.base.utils.Constants.*;
import static java.lang.String.format;

@Component("adqmMppwFinishRequestHandler")
@Slf4j
public class MppwFinishRequestHandler extends AbstractMppwRequestHandler {
    private static final String QUERY_TABLE_SETTINGS = "select %s from system.tables where database = '%s' and name = '%s'";
    private static final String SELECT_COLUMNS_QUERY = "select name from system.columns where database = '%s' and table = '%s'";

    private final RestLoadClient restLoadClient;
    private final AppConfiguration appConfiguration;
    private final StatusReporter statusReporter;
    private final AdqmCommonSqlFactory adqmCommonSqlFactory;

    @Autowired
    public MppwFinishRequestHandler(RestLoadClient restLoadClient,
                                    final DatabaseExecutor databaseExecutor,
                                    final DdlProperties ddlProperties,
                                    final AppConfiguration appConfiguration,
                                    StatusReporter statusReporter,
                                    AdqmCommonSqlFactory adqmCommonSqlFactory) {
        super(databaseExecutor, ddlProperties);
        this.restLoadClient = restLoadClient;
        this.appConfiguration = appConfiguration;
        this.statusReporter = statusReporter;
        this.adqmCommonSqlFactory = adqmCommonSqlFactory;
    }

    @Override
    public Future<QueryResult> execute(final MppwKafkaRequest request) {
        val err = AdqmDdlUtil.validateRequest(request);
        if (err.isPresent()) {
            return Future.failedFuture(err.get());
        }

        String fullName = AdqmDdlUtil.getQualifiedTableName(request, appConfiguration);
        long sysCn = request.getSysCn();

        return sequenceAll(Arrays.asList(  // 1. drop shard tables
                fullName + EXT_SHARD_POSTFIX,
                fullName + ACTUAL_LOADER_SHARD_POSTFIX,
                fullName + BUFFER_LOADER_SHARD_POSTFIX
        ), this::dropTable)
                .compose(v -> sequenceAll(Arrays.asList( // 2. flush distributed tables
                        fullName + BUFFER_POSTFIX,
                        fullName + ACTUAL_POSTFIX), this::flushTable))
                .compose(v -> closeDeletedVersions(fullName, sysCn))  // 3. close deleted versions
                .compose(v -> closeByTableActual(fullName, sysCn))  // 4. close version by table actual
                .compose(v -> flushTable(fullName + ACTUAL_POSTFIX))  // 5. flush actual table
                .compose(v -> sequenceAll(Arrays.asList(  // 6. drop buffer tables
                        fullName + BUFFER_POSTFIX,
                        fullName + BUFFER_SHARD_POSTFIX), this::dropTable))
                .compose(v -> optimizeTable(fullName + ACTUAL_SHARD_POSTFIX))// 7. merge shards
                .compose(v -> {
                    final RestMppwKafkaStopRequest mppwKafkaStopRequest = new RestMppwKafkaStopRequest(
                            request.getRequestId().toString(),
                            request.getTopic());
                    log.debug("ADQM: Send mppw kafka stopping rest request {}", mppwKafkaStopRequest);
                    return restLoadClient.stopLoading(mppwKafkaStopRequest);
                })
                .compose(v -> {
                    reportFinish(request.getTopic());
                    return Future.succeededFuture(QueryResult.emptyResult());
                }, f -> {
                    reportError(request.getTopic());
                    return Future.failedFuture(f);
                });
    }

    private Future<Void> flushTable(String table) {
        return databaseExecutor.executeUpdate(adqmCommonSqlFactory.getFlushSql(table));
    }

    private Future<Void> closeDeletedVersions(String table, long deltaHot) {
        Future<String> columnNames = fetchColumnNames(table + ACTUAL_POSTFIX);
        Future<String> sortingKey = fetchSortingKey(table + ACTUAL_SHARD_POSTFIX);

        return CompositeFuture.join(columnNames, sortingKey)
                .compose(r -> databaseExecutor.executeUpdate(
                        adqmCommonSqlFactory.getCloseVersionSqlByTableBuffer(table, r.resultAt(0), r.resultAt(1), deltaHot)));
    }

    private Future<Void> closeByTableActual(String table, long deltaHot) {
        Future<String> columnNames = fetchColumnNames(table + ACTUAL_POSTFIX);
        Future<String> sortingKey = fetchSortingKey(table + ACTUAL_SHARD_POSTFIX);

        return CompositeFuture.join(columnNames, sortingKey)
                .compose(r -> databaseExecutor.executeUpdate(
                        adqmCommonSqlFactory.getCloseVersionSqlByTableActual(table, r.resultAt(0), (String) r.resultAt(1), deltaHot)));
    }

    private Future<Void> optimizeTable(String table) {
        return databaseExecutor.executeUpdate(adqmCommonSqlFactory.getOptimizeSql(table));
    }

    private Future<String> fetchColumnNames(String table) {
        val parts = splitQualifiedTableName(table);
        if (!parts.isPresent()) {
            return Future.failedFuture(
                    new MppwDatasourceException(format("Incorrect table name, cannot split to schema.table: %s",
                            table)));
        }
        String query = format(SELECT_COLUMNS_QUERY, parts.get().getLeft(), parts.get().getRight());
        Promise<String> promise = Promise.promise();
        databaseExecutor.execute(query, createVarcharColumnMetadata("name"))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        promise.fail(ar.cause());
                        return;
                    }
                    promise.complete(getColumnNames(ar.result()));
                });
        return promise.future();
    }

    private List<ColumnMetadata> createVarcharColumnMetadata(String column) {
        List<ColumnMetadata> metadata = new ArrayList<>();
        metadata.add(new ColumnMetadata(column, ColumnType.VARCHAR));
        return metadata;
    }

    private Future<String> fetchSortingKey(String table) {
        val parts = splitQualifiedTableName(table);
        if (!parts.isPresent()) {
            return Future.failedFuture(
                    new MppwDatasourceException(format("Incorrect table name, cannot split to schema.table: %s",
                            table)));
        }
        final String sortingKeyColumn = "sorting_key";
        String query = format(QUERY_TABLE_SETTINGS, sortingKeyColumn, parts.get().getLeft(), parts.get().getRight());
        Promise<String> promise = Promise.promise();
        databaseExecutor.execute(query, createVarcharColumnMetadata(sortingKeyColumn))
                .onComplete(ar -> {
                    if (ar.failed()) {
                        promise.fail(ar.cause());
                        return;
                    }
                    if (ar.result().isEmpty()) {
                        promise.fail(new MppwDatasourceException(format("Cannot find sorting_key for %s", table)));
                        return;
                    }
                    String sortingKey = ar.result().get(0).get(sortingKeyColumn).toString();
                    String withoutSysFrom = Arrays.stream(sortingKey.split(",\\s*"))
                            .filter(c -> !c.equalsIgnoreCase(SYS_FROM_FIELD))
                            .collect(Collectors.joining(", "));

                    promise.complete(withoutSysFrom);
                });
        return promise.future();
    }

    private String getColumnNames(List<Map<String, Object>> result) {
        return result
                .stream()
                .map(o -> o.get("name").toString())
                .filter(f -> !SYSTEM_FIELDS.contains(f))
                .collect(Collectors.joining(", "));
    }

    private void reportFinish(String topic) {
        StatusReportDto start = new StatusReportDto(topic);
        statusReporter.onFinish(start);
    }

    private void reportError(String topic) {
        StatusReportDto start = new StatusReportDto(topic);
        statusReporter.onError(start);
    }
}
