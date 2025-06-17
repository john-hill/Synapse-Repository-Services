package org.sagebionetworks.repo.manager.search.oss;

import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.search.SearchConstants;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OpenSearchIndexInitializer {
    private static final String RESOURCE_EXISTS = "resource_already_exists_exception";
    private Logger log;
    private OpenSearchClient client;

    public OpenSearchIndexInitializer(LoggerProvider logProvider, OpenSearchClient client) {
        this.log = logProvider.getLogger(OpenSearchIndexInitializer.class.getName());
        this.client = client;

    }

    public void init() throws IOException {
        try {
            OpenSearchIndicesClient indicesClient = client.indices();
            if (!indicesClient.exists(request -> request.index(SearchConstants.OPEN_SEARCH_INDEX_NAME)).value()) {
                CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .mappings(m -> m
                                .properties(SearchConstants.FIELD_NAME, p -> p.text(text ->
                                        text.analyzer("english")))
                                .properties(SearchConstants.FIELD_DESCRIPTION,
                                        p -> p.text(text ->
                                                text.analyzer("english")))
                                .properties(SearchConstants.FIELD_CREATED_ON,
                                        p -> p.integer( i ->i))
                                .properties(SearchConstants.FIELD_MODIFIED_ON,
                                        p -> p.integer(i -> i))
                                .properties(SearchConstants.FIELD_NODE_TYPE,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_ETAG,
                                        p -> p.keyword(k ->k))
                                .properties(SearchConstants.FIELD_PARENT_ID,
                                        p -> p.keyword( k ->k))
                                .properties(SearchConstants.FIELD_CREATED_BY,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_MODIFIED_BY,
                                        p-> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_ACL,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_UPDATE_ACL,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_DIAGNOSIS,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_TISSUE,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_CONSORTIUM,
                                        p -> p.keyword(k -> k))
                                .properties(SearchConstants.FIELD_ORGAN,
                                        p-> p.keyword(k -> k))
                        ).build();

                CreateIndexResponse response = indicesClient.create(request);

                if (Boolean.TRUE.equals(response.acknowledged())) {
                    log.info(String.format("Index %s creation completed.", SearchConstants.OPEN_SEARCH_INDEX_NAME));
                } else {
                    log.error(String.format("Index %s creation was not acknowledged.", SearchConstants.OPEN_SEARCH_INDEX_NAME));
                }
            }
        } catch (OpenSearchException e) {
            if (RESOURCE_EXISTS.equals(e.error().type())) {
                log.error(String.format("Index %s already exists.", SearchConstants.OPEN_SEARCH_INDEX_NAME));
            } else {
                log.error(String.format("Index %s creation failed %s", SearchConstants.OPEN_SEARCH_INDEX_NAME, e.error().reason()));
            }
        } catch (IOException e) {
            log.error(String.format("Index %s creation failed %s", SearchConstants.OPEN_SEARCH_INDEX_NAME, e.getMessage()));
        }
    }
}
