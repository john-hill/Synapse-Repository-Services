package org.sagebionetworks.repo.manager.opensearch;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.GetResponse;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;


@Service
public class SearchDaoImpl implements SearchDao {
    static final private String INDEX_NAME = "synapse";
    @Autowired
    OpenSearchClient openSearchClient;

    @Override
    public BulkResponse sendDocuments(BulkRequest bulkRequest) throws IOException {
        ValidateArgument.required(bulkRequest, "bulkRequest");
        return openSearchClient.bulk(bulkRequest);
    }

    @Override
    public boolean doesDocumentExistInSearchIndex(String id){
        ValidateArgument.required(id, "id");
        try {
            GetRequest getRequest = GetRequest.of(g -> g
                    .index(INDEX_NAME)
                    .id(id)
            );

            GetResponse<Document> response = openSearchClient.get(getRequest, Document.class);

            return response.found();
        } catch (OpenSearchException e) {
            //OpenSearch send 404 if document does not exists.
            if (e.response().status() == 404) {
                return false;
            } else {
                throw e; // rethrow unexpected errors
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
