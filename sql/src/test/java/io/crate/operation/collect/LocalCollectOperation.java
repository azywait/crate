/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.collect;

import io.crate.executor.transport.TransportActionProvider;
import io.crate.jobs.JobContextService;
import io.crate.metadata.Functions;
import io.crate.metadata.ReferenceResolver;
import io.crate.operation.projectors.CollectingProjector;
import io.crate.operation.projectors.ResultProvider;
import io.crate.planner.node.StreamerVisitor;
import io.crate.planner.node.dql.CollectNode;
import org.elasticsearch.action.bulk.BulkRetryCoordinatorPool;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * only used for testing
 */
@Singleton
public class LocalCollectOperation extends MapSideDataCollectOperation {

    @Inject
    public LocalCollectOperation(ClusterService clusterService,
                                 Settings settings,
                                 TransportActionProvider transportActionProvider,
                                 BulkRetryCoordinatorPool bulkRetryCoordinatorPool,
                                 Functions functions,
                                 ReferenceResolver referenceResolver,
                                 IndicesService indicesService,
                                 ThreadPool threadPool,
                                 CollectServiceResolver collectServiceResolver,
                                 StreamerVisitor streamerVisitor,
                                 JobContextService jobContextService,
                                 TransportService transportService) {
        super(clusterService, settings, transportActionProvider, bulkRetryCoordinatorPool, functions, referenceResolver,
                indicesService, threadPool, collectServiceResolver, streamerVisitor,
                jobContextService, transportService);
    }

    @Override
    public ResultProvider createDownstream(CollectNode node) {
        return new CollectingProjector();
    }
}
