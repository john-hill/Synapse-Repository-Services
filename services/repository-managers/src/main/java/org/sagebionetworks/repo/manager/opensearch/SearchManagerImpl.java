package org.sagebionetworks.repo.manager.opensearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class SearchManagerImpl implements SearchManager {
    static private Logger log = LogManager.getLogger(SearchManagerImpl.class);
    static final private String INDEX_NAME= "synapse";

    @Autowired
    ChangeMessageToOpenSearchDocumentTranslator translator;

    @Autowired
    OpenSearchClient openSearchClient;
    @Autowired
    SearchDao searchDao;

    @Override
    public void documentChangeMessages(List<ChangeMessage> messages) throws TemporarilyUnavailableException {
        try {
            List<BulkOperation> operations = messages.stream()
                    .map(translator::generateSearchDocumentIfNecessary)
                    .filter(Objects::nonNull)
                    .map(doc -> BulkOperation.of(op -> op
                            .index(idx -> idx
                                    .index(INDEX_NAME) // index should be created
                                    .id(doc.getId())
                                    .document(doc)
                            )
                    ))
                    .collect(Collectors.toList());

            BulkResponse response = searchDao.sendDocuments(new BulkRequest.Builder().operations(operations).build());
            response.items().forEach(item -> {
                if (item.error() != null) {
                    log.error(String.format("Error for document Id %s and the stackTrace %s ", item.id(), item.error().stackTrace()));
                }
            });
        } catch (IOException e) {
            throw new TemporarilyUnavailableException(e);
        }
    }

    @Override
    public boolean doesDocumentExist(String id) {
        return searchDao.doesDocumentExistInSearchIndex(id);
    }
}
