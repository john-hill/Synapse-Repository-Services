package org.sagebionetworks.repo.manager.search.oss;

import org.sagebionetworks.repo.model.search.query.SearchFieldName;
import org.sagebionetworks.search.SearchConstants;
import org.sagebionetworks.util.ValidateArgument;

public enum SynapseToOpenSearchField {
    CONSORTIUM(SearchFieldName.Consortium, SearchConstants.FIELD_CONSORTIUM),
    DIAGNOSIS(SearchFieldName.Diagnosis, SearchConstants.FIELD_DIAGNOSIS),
    ORGAN(SearchFieldName.Organ, SearchConstants.FIELD_ORGAN),
    TISSUE(SearchFieldName.Tissue, SearchConstants.FIELD_TISSUE),

    ID(SearchFieldName.Id, SearchConstants.FIELD_ID),
    NAME(SearchFieldName.Name, SearchConstants.FIELD_NAME),
    ENTITY_TYPE(SearchFieldName.EntityType, SearchConstants.FIELD_NODE_TYPE),
    MODIFIED_BY(SearchFieldName.ModifiedBy, SearchConstants.FIELD_MODIFIED_BY),
    MODIFIED_ON(SearchFieldName.ModifiedOn, SearchConstants.FIELD_MODIFIED_ON),
    CREATED_BY(SearchFieldName.CreatedBy, SearchConstants.FIELD_CREATED_BY),
    CREATED_ON(SearchFieldName.CreatedOn, SearchConstants.FIELD_CREATED_ON),
    DESCRIPTION(SearchFieldName.Description, SearchConstants.FIELD_DESCRIPTION);

    private final SearchFieldName synapseSearchField;
    private final String name;

    SynapseToOpenSearchField(SearchFieldName synapseSearchField, String name) {
        this.synapseSearchField = synapseSearchField;
        this.name = name;
    }

    public static String OpenSearchFieldFor(SearchFieldName synapseSearchFieldName) {
        ValidateArgument.required(synapseSearchFieldName, "synapseSearchFieldName");

        for (SynapseToOpenSearchField synapseToOpenSearchField : values()) {
            if(synapseSearchFieldName == synapseToOpenSearchField.synapseSearchField){
                return synapseToOpenSearchField.name;
            }
        }
        throw new IllegalArgumentException("Unknown SearchField");
    }
}
