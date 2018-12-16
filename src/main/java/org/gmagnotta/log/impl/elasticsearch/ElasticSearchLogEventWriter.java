package org.gmagnotta.log.impl.elasticsearch;

import org.gmagnotta.log.LogEvent;
import org.gmagnotta.log.LogEventWriter;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Executors;


public class ElasticSearchLogEventWriter implements LogEventWriter {

    private String index;
    private String app;
    private ElasticSearchLogClient client;

    /**
     * Constructor
     *
     * @param elasticSearchUrl      url of elastic search
     * @param index                 index name where log events will be putted
     * @param app                   application name
     */
    public ElasticSearchLogEventWriter(URL elasticSearchUrl, String index, String app) {
        try {
            this.app = app;
            this.index = index;
            client = new ElasticSearchLogClient(elasticSearchUrl);
            if (!client.isLogIndexExist(index)) {
                client.createLogIndex(index);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void write(LogEvent logEvent) {
        if (client != null && logEvent != null) {
            client.putLogEvent(index, app, logEvent);
        }
    }

    @Override
    public void stop() {
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String newIndex) {
        if (client != null) {

            String oldIndex = this.index;

            // ElasticSearch doesn't have renaming operation, so we need to create new index,
            // move all documents to new index and delete old index.
            try {
                if (!client.isLogIndexExist(newIndex)) {
                    client.createLogIndex(newIndex);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Since moving documents can take much time, we run this task in separated
            // thread as async operation.
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    // if prev index exist - reindex documents to new index
                    if (oldIndex != null) {
                        client.reIndexLog(oldIndex, newIndex);
                        client.deleteLogIndex(oldIndex);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            this.index = newIndex;
        }
    }
}