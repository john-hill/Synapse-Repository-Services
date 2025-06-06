package org.sagebionetworks.repo.manager.opensearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.search.SearchConstants;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OpenSearchIndexInitializer {
    private static final Logger log = LogManager.getLogger(OpenSearchIndexInitializer.class.getName());

    private OpenSearchClient client;

    public OpenSearchIndexInitializer(OpenSearchClient client) {
        this.client = client;

    }

    public void init() throws IOException {
        createIndex(client.indices());
    }

    void createIndex(OpenSearchIndicesClient indicesClient) {
        try {
            if (!indicesClient.exists(request -> request.index(SearchConstants.OPEN_SEARCH_INDEX_NAME)).value()) {
                //create template
                CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                        .mappings(m -> m
                                .properties("type", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("id", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.name", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.description", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.parent_id", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.node_type", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.etag", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.created_on", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.modified_on", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.created_by", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.modified_by", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.acl", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.update_acl", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.diagnosis", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.tissue", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.consortium", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                                .properties("fields.organ", p -> p
                                        .text(text -> text
                                                .fields("keyword", f -> f.keyword(k -> k))))
                        ).build();

                CreateIndexResponse response = indicesClient.create(request);

                if (Boolean.TRUE.equals(response.acknowledged())) {
                    log.info("Index {} creation completed.", SearchConstants.OPEN_SEARCH_INDEX_NAME);
                } else {
                    log.info("Index {} creation was not acknowledged.", SearchConstants.OPEN_SEARCH_INDEX_NAME);
                }
            }
        } catch (OpenSearchException e) {
            if ("resource_already_exists_exception".equals(e.error().type())) {
                log.error("Index {} already exists {} ", SearchConstants.OPEN_SEARCH_INDEX_NAME, e.error().reason());
            } else {
                log.error("Index {} creation failed {}", SearchConstants.OPEN_SEARCH_INDEX_NAME, e.error().reason());
            }
        } catch (IOException e) {
            log.error("Index {} creation failed {} ", SearchConstants.OPEN_SEARCH_INDEX_NAME, e.getStackTrace());
        }
    }
}
