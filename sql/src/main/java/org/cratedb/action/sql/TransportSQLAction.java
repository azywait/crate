package org.cratedb.action.sql;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.DefaultTraversalVisitor;
import io.crate.sql.tree.QualifiedName;
import io.crate.sql.tree.Statement;
import io.crate.sql.tree.Table;
import org.cratedb.action.DistributedSQLRequest;
import org.cratedb.action.TransportDistributedSQLAction;
import org.cratedb.action.import_.ImportRequest;
import org.cratedb.action.import_.ImportResponse;
import org.cratedb.action.import_.TransportImportAction;
import org.cratedb.action.parser.ESRequestBuilder;
import org.cratedb.action.parser.SQLResponseBuilder;
import org.cratedb.action.sql.analyzer.TransportClusterUpdateCrateSettingsAction;
import org.cratedb.service.InformationSchemaService;
import org.cratedb.service.SQLParseService;
import org.cratedb.sql.ExceptionHelper;
import org.cratedb.sql.TableUnknownException;
import org.cratedb.sql.parser.StandardException;
import org.cratedb.sql.parser.parser.*;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.create.TransportCreateIndexAction;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.delete.TransportDeleteIndexAction;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.TransportBulkAction;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.count.TransportCountAction;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.delete.TransportDeleteAction;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequest;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.TransportDeleteByQueryAction;
import org.elasticsearch.action.get.*;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.index.TransportIndexAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.action.update.TransportUpdateAction;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.BaseTransportRequestHandler;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportService;

import java.util.concurrent.atomic.AtomicReference;


public class TransportSQLAction extends TransportAction<SQLRequest, SQLResponse> {

    private final TransportSearchAction transportSearchAction;
    private final TransportIndexAction transportIndexAction;
    private final TransportDeleteByQueryAction transportDeleteByQueryAction;
    private final TransportBulkAction transportBulkAction;
    private final TransportCountAction transportCountAction;
    private final TransportGetAction transportGetAction;
    private final TransportMultiGetAction transportMultiGetAction;
    private final TransportDeleteAction transportDeleteAction;
    private final TransportUpdateAction transportUpdateAction;
    private final TransportDistributedSQLAction transportDistributedSQLAction;
    private final TransportCreateIndexAction transportCreateIndexAction;
    private final TransportDeleteIndexAction transportDeleteIndexAction;
    private final InformationSchemaService informationSchemaService;
    private final SQLParseService sqlParseService;
    private final TransportClusterUpdateCrateSettingsAction transportClusterUpdateCrateSettingsAction;
    private final TransportImportAction transportImportAction;


    @Inject
    protected TransportSQLAction(Settings settings, ThreadPool threadPool,
            SQLParseService sqlParseService,
            TransportService transportService,
            TransportSearchAction transportSearchAction,
            TransportDeleteByQueryAction transportDeleteByQueryAction,
            TransportIndexAction transportIndexAction,
            TransportBulkAction transportBulkAction,
            TransportGetAction transportGetAction,
            TransportMultiGetAction transportMultiGetAction,
            TransportDeleteAction transportDeleteAction,
            TransportUpdateAction transportUpdateAction,
            TransportDistributedSQLAction transportDistributedSQLAction,
            TransportCountAction transportCountAction,
            TransportCreateIndexAction transportCreateIndexAction,
            TransportDeleteIndexAction transportDeleteIndexAction,
            TransportClusterUpdateCrateSettingsAction transportClusterUpdateCrateSettingsAction,
            TransportImportAction transportImportAction,
            InformationSchemaService informationSchemaService) {
        super(settings, threadPool);
        this.sqlParseService = sqlParseService;
        transportService.registerHandler(SQLAction.NAME, new TransportHandler());
        this.transportSearchAction = transportSearchAction;
        this.transportIndexAction = transportIndexAction;
        this.transportDeleteByQueryAction = transportDeleteByQueryAction;
        this.transportBulkAction = transportBulkAction;
        this.transportCountAction = transportCountAction;
        this.transportGetAction = transportGetAction;
        this.transportMultiGetAction = transportMultiGetAction;
        this.transportDeleteAction = transportDeleteAction;
        this.transportUpdateAction = transportUpdateAction;
        this.transportDistributedSQLAction = transportDistributedSQLAction;
        this.transportCreateIndexAction = transportCreateIndexAction;
        this.transportDeleteIndexAction = transportDeleteIndexAction;
        this.transportClusterUpdateCrateSettingsAction = transportClusterUpdateCrateSettingsAction;
        this.transportImportAction = transportImportAction;
        this.informationSchemaService = informationSchemaService;
    }

    private abstract class ESResponseToSQLResponseListener<T extends ActionResponse> implements ActionListener<T> {

        protected final ActionListener<SQLResponse> listener;
        protected final SQLResponseBuilder builder;
        protected final long requestStartedTime;

        public ESResponseToSQLResponseListener(ParsedStatement stmt,
                                               ActionListener<SQLResponse> listener,
                                               long requestStartedTime) {
            this.listener = listener;
            this.builder = new SQLResponseBuilder(sqlParseService.context, stmt);
            this.requestStartedTime = requestStartedTime;
        }

        @Override
        public void onFailure(Throwable e) {
            listener.onFailure(ExceptionHelper.transformToCrateException(e));
        }
    }

    private class SearchResponseListener extends ESResponseToSQLResponseListener<SearchResponse> {

        public SearchResponseListener(ParsedStatement stmt,
                                      ActionListener<SQLResponse> listener,
                                      long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(SearchResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class IndexResponseListener extends ESResponseToSQLResponseListener<IndexResponse> {

        public IndexResponseListener(ParsedStatement stmt,
                                     ActionListener<SQLResponse> listener,
                                     long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(IndexResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class DeleteByQueryResponseListener extends ESResponseToSQLResponseListener<DeleteByQueryResponse> {

        public DeleteByQueryResponseListener(ParsedStatement stmt,
                                             ActionListener<SQLResponse> listener,
                                             long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(DeleteByQueryResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class DeleteResponseListener extends ESResponseToSQLResponseListener<DeleteResponse> {

        public DeleteResponseListener(ParsedStatement stmt,
                                      ActionListener<SQLResponse> listener,
                                      long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(DeleteResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }

        @Override
        public void onFailure(Throwable e) {
            DeleteResponse response = ExceptionHelper.deleteResponseFromVersionConflictException(e);
            if (response != null) {
                listener.onResponse(builder.buildResponse(response, requestStartedTime));
            } else {
                listener.onFailure(ExceptionHelper.transformToCrateException(e));
            }
        }
    }

    private class BulkResponseListener extends ESResponseToSQLResponseListener<BulkResponse> {

        public BulkResponseListener(ParsedStatement stmt,
                                    ActionListener<SQLResponse> listener,
                                    long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(BulkResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    @Override
    protected void doExecute(SQLRequest request, ActionListener<SQLResponse> listener) {
        logger.trace("doExecute: " + request);

        try {
            StatementNode akibanNode = getAkibanNode(request.stmt());
            ParsedStatement stmt;
            if (akibanNode != null) {
                stmt = sqlParseService.parse(request.stmt(), akibanNode, request.args());
            } else {
                usePresto(request, listener);
                return;
            }

            ESRequestBuilder builder = new ESRequestBuilder(stmt);
            switch (stmt.type()) {
                case INFORMATION_SCHEMA:
                    informationSchemaService.execute(stmt, listener, request.creationTime());
                    break;
                case INSERT_ACTION:
                    IndexRequest indexRequest = builder.buildIndexRequest();
                    transportIndexAction.execute(indexRequest, new IndexResponseListener(stmt,
                            listener, request.creationTime()));
                    break;
                case DELETE_BY_QUERY_ACTION:
                    DeleteByQueryRequest deleteByQueryRequest = builder.buildDeleteByQueryRequest();
                    transportDeleteByQueryAction.execute(deleteByQueryRequest,
                            new DeleteByQueryResponseListener(stmt, listener, request.creationTime()));
                    break;
                case DELETE_ACTION:
                    DeleteRequest deleteRequest = builder.buildDeleteRequest();
                    transportDeleteAction.execute(deleteRequest,
                            new DeleteResponseListener(stmt, listener, request.creationTime()));
                    break;
                case BULK_ACTION:
                    BulkRequest bulkRequest = builder.buildBulkRequest();
                    transportBulkAction.execute(bulkRequest,
                            new BulkResponseListener(stmt, listener, request.creationTime()));
                    break;
                case GET_ACTION:
                    GetRequest getRequest = builder.buildGetRequest();
                    transportGetAction.execute(getRequest,
                            new GetResponseListener(stmt, listener, request.creationTime()));
                    break;
                case MULTI_GET_ACTION:
                    MultiGetRequest multiGetRequest = builder.buildMultiGetRequest();
                    transportMultiGetAction.execute(multiGetRequest,
                            new MultiGetResponseListener(stmt, listener, request.creationTime()));
                    break;
                case UPDATE_ACTION:
                    UpdateRequest updateRequest = builder.buildUpdateRequest();
                    transportUpdateAction.execute(updateRequest,
                            new UpdateResponseListener(stmt, listener, request.creationTime()));
                    break;
                case CREATE_INDEX_ACTION:
                    CreateIndexRequest createIndexRequest = builder.buildCreateIndexRequest();
                    transportCreateIndexAction.execute(createIndexRequest,
                            new CreateIndexResponseListener(stmt, listener, request.creationTime()));
                    break;
                case DELETE_INDEX_ACTION:
                    DeleteIndexRequest deleteIndexRequest = builder.buildDeleteIndexRequest();
                    transportDeleteIndexAction.execute(deleteIndexRequest,
                            new DeleteIndexResponseListener(stmt, listener, request.creationTime()));
                    break;
                case CREATE_ANALYZER_ACTION:
                    ClusterUpdateSettingsRequest clusterUpdateSettingsRequest = builder.buildClusterUpdateSettingsRequest();
                    transportClusterUpdateCrateSettingsAction.execute(clusterUpdateSettingsRequest,
                            new ClusterUpdateSettingsResponseListener(stmt, listener, request.creationTime()));
                    break;
                case COPY_IMPORT_ACTION:
                    ImportRequest importRequest = builder.buildImportRequest();
                    transportImportAction.execute(importRequest,
                            new ImportResponseListener(stmt, listener, request.creationTime()));
                    break;
                case STATS:
                    transportDistributedSQLAction.execute(
                            new DistributedSQLRequest(request, stmt),
                            new DistributedSQLResponseListener(stmt, listener, request.creationTime()));
                    break;
                default:
                    // TODO: don't simply run globalAggregate Queries like Group By Queries
                    // Disable Reducers!!!
                    if (stmt.hasGroupBy() || stmt.isGlobalAggregate()) {
                        transportDistributedSQLAction.execute(
                            new DistributedSQLRequest(request, stmt),
                            new DistributedSQLResponseListener(stmt, listener, request.creationTime()));
                    } else if (stmt.countRequest()) {
                        CountRequest countRequest = builder.buildCountRequest();
                        transportCountAction.execute(countRequest,
                                new CountResponseListener(stmt, listener, request.creationTime()));
                    } else {
                        SearchRequest searchRequest = builder.buildSearchRequest();
                        transportSearchAction.execute(searchRequest,
                                new SearchResponseListener(stmt, listener, request.creationTime()));
                    }
                    break;
            }
        } catch (Exception e) {
            listener.onFailure(ExceptionHelper.transformToCrateException(e));
        }
    }

    private void usePresto(SQLRequest request, ActionListener<SQLResponse> listener) {
        // TODO: implement

        // tree = parser.parse(request.stmt());
        // boundTree = binder.bind(tree).
        // normalizedTree = analyzer.analyze(boundTree)
        // job = planner.plan(analyzedTree)


        // executor.execute(job)

        listener.onFailure(new UnsupportedOperationException());
    }

    /**
     * for the migration from akiban to the presto based sql-parser
     * if presto should be used it returns null, otherwise it returns the parsed StatementNode for akiban.
     *
     * @param stmt sql statement as string
     * @return null or the akiban StatementNode
     * @throws StandardException
     */
    private StatementNode getAkibanNode(String stmt) throws StandardException {

        SQLParser parser = new SQLParser();
        StatementNode node = parser.parseStatement(stmt);
        final AtomicReference<Boolean> isPresto = new AtomicReference<>(false);

        Visitor visitor = new Visitor() {
            @Override
            public Visitable visit(Visitable node) throws StandardException {
                if (((QueryTreeNode)node).getNodeType() == NodeType.FROM_BASE_TABLE) {
                    TableName tableName = ((FromBaseTable) node).getTableName();
                    if (tableName.getSchemaName() != null
                            && tableName.getSchemaName().equalsIgnoreCase("sys")
                            && tableName.getTableName().equalsIgnoreCase("nodes")) {

                        isPresto.set(true);
                        return null;
                    }
                }
                return node;
            }

            @Override
            public boolean visitChildrenFirst(Visitable node) {
                return false;
            }

            @Override
            public boolean stopTraversal() {
                return isPresto.get();
            }

            @Override
            public boolean skipChildren(Visitable node) throws StandardException {
                return false;
            }
        };

        node.accept(visitor);

        if (isPresto.get()) {
            return null;
        }
        return node;
    }

    private class TransportHandler extends BaseTransportRequestHandler<SQLRequest> {

        @Override
        public SQLRequest newInstance() {
            return new SQLRequest();
        }

        @Override
        public void messageReceived(SQLRequest request, final TransportChannel channel) throws Exception {
            // no need for a threaded listener
            request.listenerThreaded(false);
            execute(request, new ActionListener<SQLResponse>() {
                @Override
                public void onResponse(SQLResponse result) {
                    try {
                        channel.sendResponse(result);
                    } catch (Throwable e) {
                        onFailure(e);
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Exception e1) {
                        logger.warn("Failed to send response for sql query", e1);
                    }
                }
            });
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }

    private class CountResponseListener extends ESResponseToSQLResponseListener<CountResponse> {

        public CountResponseListener(ParsedStatement stmt,
                                     ActionListener<SQLResponse> listener,
                                     long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(CountResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }


    private class GetResponseListener extends ESResponseToSQLResponseListener<GetResponse> {

        public GetResponseListener(ParsedStatement stmt,
                                   ActionListener<SQLResponse> listener,
                                   long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(GetResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class MultiGetResponseListener extends ESResponseToSQLResponseListener<MultiGetResponse> {

        public MultiGetResponseListener(ParsedStatement stmt,
                                        ActionListener<SQLResponse> listener,
                                        long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(MultiGetResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class DistributedSQLResponseListener implements ActionListener<SQLResponse> {

        private final ActionListener<SQLResponse> delegate;
        private final ParsedStatement stmt;
        private final long requestStartedTime;

        public DistributedSQLResponseListener(ParsedStatement stmt,
                                              ActionListener<SQLResponse> listener,
                                              long requestStartedTime) {
            this.stmt = stmt;
            this.delegate = listener;
            this.requestStartedTime = requestStartedTime;
        }

        @Override
        public void onResponse(SQLResponse sqlResponse) {
            sqlResponse.requestStartedTime(requestStartedTime);
            delegate.onResponse(sqlResponse);
        }

        @Override
        public void onFailure(Throwable e) {
            delegate.onFailure(ExceptionHelper.transformToCrateException(e));
        }
    }

    private class UpdateResponseListener extends ESResponseToSQLResponseListener<UpdateResponse> {

        public UpdateResponseListener(ParsedStatement stmt,
                                      ActionListener<SQLResponse> listener,
                                      long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(UpdateResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }

        @Override
        public void onFailure(Throwable e) {
            if (e instanceof DocumentMissingException) {
                listener.onResponse(builder.buildMissingDocumentResponse(requestStartedTime));
            } else {
                listener.onFailure(ExceptionHelper.transformToCrateException(e));
            }
        }
    }

    private class CreateIndexResponseListener extends ESResponseToSQLResponseListener<CreateIndexResponse> {

        public CreateIndexResponseListener(ParsedStatement stmt,
                                           ActionListener<SQLResponse> listener,
                                           long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(CreateIndexResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class DeleteIndexResponseListener extends ESResponseToSQLResponseListener<DeleteIndexResponse> {

        public DeleteIndexResponseListener(ParsedStatement stmt,
                                           ActionListener<SQLResponse> listener,
                                           long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(DeleteIndexResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class ClusterUpdateSettingsResponseListener extends ESResponseToSQLResponseListener<ClusterUpdateSettingsResponse> {

        public ClusterUpdateSettingsResponseListener(ParsedStatement stmt,
                                                     ActionListener<SQLResponse> listener,
                                                     long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(ClusterUpdateSettingsResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

    private class ImportResponseListener extends ESResponseToSQLResponseListener<ImportResponse> {

        public ImportResponseListener(ParsedStatement stmt,
                                      ActionListener<SQLResponse> listener,
                                      long requestStartedTime) {
            super(stmt, listener, requestStartedTime);
        }

        @Override
        public void onResponse(ImportResponse response) {
            listener.onResponse(builder.buildResponse(response, requestStartedTime));
        }
    }

}
