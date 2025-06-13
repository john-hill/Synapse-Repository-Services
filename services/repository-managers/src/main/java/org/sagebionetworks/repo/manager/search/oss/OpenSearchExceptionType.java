package org.sagebionetworks.repo.manager.search.oss;

public enum OpenSearchExceptionType {
    RejectedExecution("es_rejected_execution_exception"),
    TimeOut("timeout_exception"),
    ConnectionTransport("connect_transport_exception"),
    ClusterBlock("cluster_block_exception"),
    ResourceAlreadyExists("resource_already_exists_exception"),
    MapperParsing("mapper_parsing_exception"),
    IndexNotFound("index_not_found_exception"),
    InvalidIndexName("invalid_index_name_exception"),
    IllegalArgument("illegal_argument_exception"),
    IndexClosed("index_closed_exception");
    private String value;


    OpenSearchExceptionType(String value) {
        this.value = value;

    }

    public String toString() {
        return this.value;
    }
}
