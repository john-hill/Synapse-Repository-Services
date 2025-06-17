package org.sagebionetworks.repo.manager.search.oss;

import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.repo.model.search.DocumentFields;
import org.sagebionetworks.repo.model.search.DocumentTypeNames;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.search.SearchConstants;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;


@Service
public class SearchManagerImpl implements SearchManager {
    private Logger log;
    private ChangeMessageToOpenSearchDocumentTranslator translator;
    private OpenSearchIndexInitializer openSearchIndexInitializer;
    @Qualifier("synSearchOssClient")
    private OpenSearchClient openSearchClient;

    public SearchManagerImpl(LoggerProvider logProvider, ChangeMessageToOpenSearchDocumentTranslator translator,
                             OpenSearchIndexInitializer openSearchIndexInitializer, OpenSearchClient openSearchClient) {
        this.log = logProvider.getLogger(SearchManagerImpl.class.getName());
        this.translator = translator;
        this.openSearchIndexInitializer = openSearchIndexInitializer;
        this.openSearchClient = openSearchClient;
    }

    @PostConstruct()
    public void init() throws IOException {
        openSearchIndexInitializer.init();
    }

    @Override
    public void documentChangeMessages(List<ChangeMessage> messages) throws TemporarilyUnavailableException {
        try {
            List<BulkOperation> operations = messages.stream().map(message -> {
                Document document = translator.generateSearchDocumentIfNecessary(message);
                if (document.getType() == DocumentTypeNames.add) {
                    return BulkOperation.of(op -> op
                            .index(idx -> idx
                                    .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                                    .id(document.getId())
                                    .document(document.getFields())));
                } else {
                    return BulkOperation.of(op -> op
                            .delete(idx -> idx
                                    .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                                    .id(document.getId())));
                }
            }).collect(Collectors.toList());

            BulkResponse response = openSearchClient.bulk(req -> req.operations(operations));

            // if any message fails to process we will throw exception to reprocess it.
            boolean hasError = response.items().stream().filter(item -> item.error() != null)
                    .peek(item -> log.error("Document {} has error {} with reason {}.", item.id(), item.error().type(),
                            item.error().reason())).findAny().isPresent();

            if (hasError) {
                throw new RecoverableMessageException();
            }
        } catch (OpenSearchException e) {
            log.error("OpenSearch error {} with reason {}.", e.error().type(), e.error().reason());
            throw new RecoverableMessageException(e);
        } catch (IOException e) {
            log.error("IOException {}.", e.getMessage());
            throw new RecoverableMessageException(e);
        }
    }

    @Override
    public boolean doesDocumentExist(String id, String etag) throws OpenSearchException, IOException {
        try {
            SearchRequest request = SearchRequest.of(r -> r
                    .index(SearchConstants.OPEN_SEARCH_INDEX_NAME)
                    .query( q ->q.bool(
                            b ->b.must(List.of(
                                    Query.of( q1 ->q1.term(t ->t.field(SearchConstants.FIELD_ID).value(FieldValue.of(id)))),
                                    Query.of(q2 -> q2.term( t ->t.field(SearchConstants.FIELD_ETAG).value(FieldValue.of(etag))))

                            )))));


            SearchResponse<DocumentFields> response = openSearchClient.search(request, DocumentFields.class);

            return !response.hits().hits().isEmpty();
        } catch (OpenSearchException | IOException e) {
            log.error("Error {} occurred while searching document.", e.getMessage());
            throw e;
        }
    }
}
