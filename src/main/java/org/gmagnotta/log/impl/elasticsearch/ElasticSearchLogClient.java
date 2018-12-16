package org.gmagnotta.log.impl.elasticsearch;

import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.*;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.gmagnotta.log.LogEvent;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

public class ElasticSearchLogClient {

    private URL elasticSearchUrl;
    private RestHighLevelClient client;
    private BulkProcessor bulkProcessor;

    private BulkProcessor.Listener listener = new BulkProcessor.Listener() {
        @Override
        public void beforeBulk(long executionId, BulkRequest request) {

        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {

        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, Throwable failure) {

        }
    };

    /**
     * Constructor
     *
     * @param url      url of elastic search
     */
    public ElasticSearchLogClient(URL url) {
        elasticSearchUrl = url;
        HttpHost host = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
        RestClientBuilder clientBuilder = RestClient.builder(host);
        client = new RestHighLevelClient(clientBuilder);

        BulkProcessor.Builder bulkBuilder = BulkProcessor.builder(
                (request, bulkListener) ->
                        client.bulkAsync(request, RequestOptions.DEFAULT, bulkListener),
                listener);

        // Configure elastic search bulk processor
        // More info https://www.elastic.co/guide/en/elasticsearch/client/java-rest/master/java-rest-high-document-bulk.html
        bulkBuilder.setBulkActions(500);    // flush bulk when reach number of actions
        bulkBuilder.setBulkSize(new ByteSizeValue(5L, ByteSizeUnit.MB));   // flush bulk request when reach size
        bulkBuilder.setConcurrentRequests(0);   // use only single request to send bulk actions
        bulkBuilder.setFlushInterval(TimeValue.timeValueSeconds(10L));  // flush bulk request when reach time
        bulkBuilder.setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(1L), 3));

        bulkProcessor = bulkBuilder.build();
    }


    /**
     * Close all resources related to ElasticSearch
     *
     * @throws IOException
     */
    public void close() throws IOException {
        bulkProcessor.close();
        client.close();
    }


    /**
     * Method create index with given name
     *
     * @param index     index name to create
     * @throws IOException
     */
    public void createLogIndex(String index) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest(index);
        request.mapping("_doc",
                "date", "type=date",
                "logLevel", "type=keyword");

        client.indices().create(request, RequestOptions.DEFAULT);
    }


    /**
     * Method delete index by name
     *
     * @param index     index name to delete
     * @throws IOException
     */
    public void deleteLogIndex(String index) throws IOException {
        DeleteIndexRequest request = new DeleteIndexRequest(index);
        client.indices().delete(request, RequestOptions.DEFAULT);
    }


    /**
     * Method implements elasticsearch reindex operation allows copy
     * all documents from source to destination index
     *
     * Since RestHighLevelClient is not support flag 'wait_for_completion'
     * which is need to avoid Connection timeout exception, was implemented
     * raw http call for reindex operation.
     *
     * @param sourceIndex   source index name
     * @param destIndex     destination index name
     * @throws IOException
     */
    public void reIndexLog(String sourceIndex, String destIndex) throws IOException {

        String requestBody = "{\"source\": {\"index\": \"" + sourceIndex + "\"},\"dest\": {\"index\": \"" + destIndex + "\"}}";
        byte[] body = requestBody.getBytes(StandardCharsets.UTF_8);

        URL url = new URL(elasticSearchUrl, "/_reindex?wait_for_completion=true");
        URLConnection con = url.openConnection();
        HttpURLConnection http = (HttpURLConnection)con;
        http.setRequestMethod("POST");
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.setRequestProperty("Content-Length", String.valueOf(body.length));
        http.setDoOutput(true);
        http.getOutputStream().write(body);
        http.connect();

        if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Server returned unexpected http status response " + http.getResponseCode() + ": " + http.getResponseMessage());
        }
    }


    /**
     * Method send log event to elastic search
     *
     * @param index     index name where log event need to be putted
     * @param app       application name
     * @param logEvent  log event that need to be putted
     */
    public void putLogEvent(String index, String app, LogEvent logEvent) {
        IndexRequest request = new IndexRequest(index, "_doc", null);
        request.source("date", logEvent.getDate().getTime(),
                "app", app,
                "logLevel", logEvent.getLogLevel(),
                "sourceClass", logEvent.getSourceClass(),
                "thread", logEvent.getThreadName(),
                "message", logEvent.getMessage());
        bulkProcessor.add(request);
    }

    public boolean isLogIndexExist(String indexName) throws IOException {
        GetIndexRequest request = new GetIndexRequest();
        request.indices(indexName);

        return client.indices().exists(request, RequestOptions.DEFAULT);
    }
}