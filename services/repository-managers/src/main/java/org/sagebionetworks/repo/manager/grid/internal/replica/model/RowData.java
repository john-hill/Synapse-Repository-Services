package org.sagebionetworks.repo.manager.grid.internal.replica.model;

import java.util.Map;
import java.util.Objects;

import org.json.JSONObject;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.patch.ConValue;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;

public class RowData {

    private Map<Integer, ConstantNode> nodes;
    private LogicalTimestamp vectorId;
    private JSONObject rowJsonDocument;

    /**
     * The row's CRDT cell nodes, keyed by the cell's index in the query's selected columns. A column
     * that has no node for this row is absent from the map.
     */
    public Map<Integer, ConstantNode> getNodes() {
        return nodes;
    }

    public RowData setNodes(Map<Integer, ConstantNode> nodes) {
        this.nodes = nodes;
        return this;
    }

    /**
     * @return the value of the cell at the given index in the query's selected columns, or null when
     *         the row has no CRDT node for that column.
     */
    public ConValue getCell(int selectedColumnIndex) {
        ConstantNode node = nodes == null ? null : nodes.get(selectedColumnIndex);
        return node == null ? null : node.getConValue();
    }

    public LogicalTimestamp getVectorId() {
        return vectorId;
    }

    public RowData setVectorId(LogicalTimestamp vectorId) {
        this.vectorId = vectorId;
        return this;
    }

    public JSONObject getRowJsonDocument() {
        return rowJsonDocument;
    }

    public RowData setRowJsonDocument(JSONObject rowJsonDocument) {
        this.rowJsonDocument = rowJsonDocument;
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowJsonDocument, vectorId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RowData other = (RowData) obj;
        if (vectorId == null) {
            if (other.vectorId != null)
                return false;
        } else if (!vectorId.equals(other.vectorId)) {
            return false;
        }
        if (rowJsonDocument == null) {
            if (other.rowJsonDocument != null)
                return false;
        } else if (!rowJsonDocument.similar(other.rowJsonDocument)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "RowData [nodes=" + nodes + ", vectorId=" + vectorId + ", rowJsonDocument=" + rowJsonDocument + "]";
    }

}
