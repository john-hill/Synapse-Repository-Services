package org.sagebionetworks.grid.db;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder;
import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder.Entry;
import org.sagebionetworks.repo.model.grid.encoding.IndexedNodeCodecMapper;
import org.sagebionetworks.repo.model.grid.encoding.SeekingNodeReader;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.node.ValueNode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.stereotype.Service;

import com.google.common.collect.Iterables;

@Service
@GridTransaction(readOnly = true)
public class GridIndexManagerImpl implements GridIndexManager {

	public static final Duration MAX_MESSAGE_DURATION = Duration.ofSeconds(60);
	public static final int MAX_MESSAGE_ID = 65535;

	// The maximum number of nodes to process in a single batch during snapshot import.
	private static final int SNAPSHOT_BATCH_SIZE = 1000;

	private static final Logger log = LogManager.getLogger(GridIndexManagerImpl.class);

	private final GridIndexDao dao;
	private final OperationDispatcher operationDispatcher;
	private final HttpClient httpClient;
	private final IndexedModelDecoderProvider decoderProvider;
	private final SeekingNodeReaderProvider readerProvider;

	public GridIndexManagerImpl(GridIndexDao dao, OperationDispatcher operationDispatcher, HttpClient httpClient,
			IndexedModelDecoderProvider decoderProvider, SeekingNodeReaderProvider readerProvider) {
		super();
		this.dao = dao;
		this.operationDispatcher = operationDispatcher;
		this.httpClient = httpClient;
		this.decoderProvider = decoderProvider;
		this.readerProvider = readerProvider;
	}

	@Override
	@GridTransaction(readOnly = false)
	public Map<IndexType, Set<LogicalTimestamp>> applyPatch(String sessionId, Long replicaId, Patch patch) {
		ValidateArgument.required(sessionId, "sessionId");
		ValidateArgument.required(replicaId, "replicaId");
		ValidateArgument.required(patch, "patch");
		ValidateArgument.required(patch.getOperations(), "patch.operations");

		createReplicaIfNotExist(sessionId, replicaId);

		if (isPatchAlreadyApplied(sessionId, replicaId, patch.getPatchId())) {
			log.info("Patch: {}.{} has already been applied to session: {} replica: {}",
					patch.getPatchId().getReplicaId(), patch.getPatchId().getSequenceNumber(), sessionId, replicaId);
			return Collections.emptyMap();
		}

		// Operations are batched and processed by type.
		Map<IndexType, Set<LogicalTimestamp>> changes = operationDispatcher.processAll(sessionId, replicaId,
				patch.getOperations());

		LogicalTimestamp patchClock = LogicalTimestamp.newIncrement(patch.getPatchId(), patch.getSpan());

		/*
		 * Set the replica's clock to reflect the applied patch. For bootstrap patches
		 * (created during grid initialization), we must be careful not to increment
		 * this replica's sequence beyond other replicas' sequences, as this could cause
		 * outstanding bootstrap patches to be ignored during synchronization.
		 */
		dao.setClock(sessionId, replicaId, patchClock);
		return changes;
	}

	@Override
	@GridTransaction(readOnly = false)
	public void applySnapshot(String sessionId, Long replicaId, URL snapshotPresignedUrl) {
		ValidateArgument.required(sessionId, "sessionId");
		ValidateArgument.required(replicaId, "replicaId");
		ValidateArgument.required(snapshotPresignedUrl, "snapshotPresignedUrl");

		Path snapshotFile = null;
		try {
			snapshotFile = downloadSnapshotFile(snapshotPresignedUrl);
			importSnapshot(sessionId, replicaId, snapshotFile);
		} finally {
			if (snapshotFile != null) {
				try {
					Files.deleteIfExists(snapshotFile);
				} catch (IOException e) {
					log.warn("Failed to delete temp file: {}", snapshotFile, e);
				}
			}
		}
	}

    Path downloadSnapshotFile(URL snapshotPresignedUrl) {
		ValidateArgument.required(snapshotPresignedUrl, "snapshotPresignedUrl");
		Path tempFile;
		try {
			tempFile = Files.createTempFile("grid-snapshot-", ".cbor");

			HttpRequest request = HttpRequest.newBuilder()
					.uri(snapshotPresignedUrl.toURI())
					.GET()
					.build();

			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE));

			if (response.statusCode() != 200) {
				throw new RuntimeException("Failed to download snapshot. Status: " + response.statusCode());
			}

			return response.body();
		} catch (IOException e) {
			throw new RuntimeException("Failed to download snapshot from: " + snapshotPresignedUrl, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while downloading snapshot from: " + snapshotPresignedUrl, e);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid snapshot URL: " + snapshotPresignedUrl, e);
		}
    }

	/**
	 * Import a snapshot using type-based batch processing for optimized database operations.
	 * This method builds an index of nodes grouped by type, then processes each type in a single
	 * batch operation to minimize database round-trips.
	 *
	 * @param sessionId the session ID
	 * @param replicaId the replica ID
	 * @param snapshotFile the path to the snapshot CBOR file
	 * @return a map of index types to the set of node IDs that were imported
	 */
	void importSnapshot(String sessionId, Long replicaId, Path snapshotFile) {
		// Delete the replica to clear the index; the snapshot will repopulate the index.
		dao.deleteReplica(sessionId, replicaId);

		// Recreate the replica. Exclude the root node, which is included in the snapshot.
		boolean insertRootNode = false;
		createReplicaIfNotExist(sessionId, replicaId, insertRootNode);

		// Build the decoder (extracts ClockTable and rootNodeId, and builds a node index in a single pass)
		IndexedModelDecoder index;
		try {
			index = decoderProvider.build(snapshotFile);
		} catch (IOException e) {
			throw new RuntimeException("Failed to build snapshot index: " + snapshotFile, e);
		}

		ClockTable snapshotClockTable = index.getClockTable();

		// Process each type in order using seeking reads
		try (SeekingNodeReader reader = readerProvider.create(snapshotFile, snapshotClockTable)) {
			// Nodes are batched by type to minimize database round-trips.
			importConstantsFromSnapshot(sessionId, replicaId, index, reader);
			importObjectsFromSnapshot(sessionId, replicaId, index, reader);
			importValuesFromSnapshot(sessionId, replicaId, index, reader);
			importArraysFromSnapshot(sessionId, replicaId, index, reader);
			importVectorsFromSnapshot(sessionId, replicaId, index, reader);
		} catch (IOException e) {
			throw new RuntimeException("Failed to import snapshot from file: " + snapshotFile, e);
		}

		// Update the replica clock
		dao.setClocks(sessionId, replicaId, snapshotClockTable.getClocks());
	}

	/**
	 * Import constant nodes in a snapshot into the database.
	 */
	private void importConstantsFromSnapshot(String sessionId, Long replicaId, IndexedModelDecoder index, SeekingNodeReader reader) throws IOException {
		Map<LogicalTimestamp, Entry> entries = index.getEntriesForType(IndexedNodeCodecMapper.CONSTANT);
		if (entries.isEmpty()) {
			return;
		}

		// Save the nodes to the index
		dao.saveIndex(sessionId, replicaId, IndexType.con, new ArrayList<>(entries.keySet()));

		// Save the node data
		for (List<Map.Entry<LogicalTimestamp, Entry>> batch : Iterables.partition(entries.entrySet(), SNAPSHOT_BATCH_SIZE)) {
			List<ConstantNode> nodes = reader.readNodes(batch).stream()
				.map(n -> (ConstantNode) n)
				.collect(Collectors.toList());
			dao.saveNewConstants(sessionId, replicaId, nodes);
		}
	}

	/**
	 * Import object nodes in a snapshot into the database.
	 */
	private void importObjectsFromSnapshot(String sessionId, Long replicaId, IndexedModelDecoder index, SeekingNodeReader reader) throws IOException {
		Map<LogicalTimestamp, Entry> entries = index.getEntriesForType(IndexedNodeCodecMapper.OBJECT);
		if (entries.isEmpty()) {
			return;
		}

		dao.saveIndex(sessionId, replicaId, IndexType.obj, new ArrayList<>(entries.keySet()));

		for (List<Map.Entry<LogicalTimestamp, Entry>> batch : Iterables.partition(entries.entrySet(), SNAPSHOT_BATCH_SIZE)) {
			// Save data for this batch
			List<ObjectNode> nodes = reader.readNodes(batch).stream()
				.map(n -> (ObjectNode) n)
				.collect(Collectors.toList());
			dao.saveObjects(sessionId, replicaId, nodes);
		}
	}

	/**
	 * Import value nodes in a snapshot into the database.
	 */
	private void importValuesFromSnapshot(String sessionId, Long replicaId, IndexedModelDecoder index, SeekingNodeReader reader) throws IOException {
		Map<LogicalTimestamp, Entry> entries = index.getEntriesForType(IndexedNodeCodecMapper.VAL);
		if (entries.isEmpty()) {
			return;
		}
		dao.saveIndex(sessionId, replicaId, IndexType.val, new ArrayList<>(entries.keySet()));

		for (List<Map.Entry<LogicalTimestamp, Entry>> batch : Iterables.partition(entries.entrySet(), SNAPSHOT_BATCH_SIZE)) {
			// Save data for this batch
			List<ValueNode> nodes = reader.readNodes(batch).stream()
				.map(n -> (ValueNode) n)
				.collect(Collectors.toList());
			dao.saveValues(sessionId, replicaId, nodes);
		}
	}

	/**
	 * Import array nodes in a snapshot into the database.
	 */
	private void importArraysFromSnapshot(String sessionId, Long replicaId, IndexedModelDecoder index, SeekingNodeReader reader) throws IOException {
		Map<LogicalTimestamp, Entry> entries = index.getEntriesForType(IndexedNodeCodecMapper.ARRAY);
		if (entries.isEmpty()) {
			return;
		}

		List<LogicalTimestamp> ids = new ArrayList<>(entries.keySet());
		dao.saveIndex(sessionId, replicaId, IndexType.arr, ids);
		dao.createArrayBatch(sessionId, replicaId, ids);

		for (List<Map.Entry<LogicalTimestamp, Entry>> batch : Iterables.partition(entries.entrySet(), SNAPSHOT_BATCH_SIZE)) {
			// Read nodes and insert RGA elements for this batch
			List<ArrayNode> nodes = reader.readNodes(batch).stream()
				.map(n -> (ArrayNode) n)
				.collect(Collectors.toList());

			List<RGANode> allElements = nodes.stream()
				.flatMap(arr -> arr.getElements().stream())
				.collect(Collectors.toList());

			if (!allElements.isEmpty()) {
				// Since this Arrays are guaranteed empty (fresh import) - use fast path directly
				dao.batchInsertRgaNodes(sessionId, replicaId, allElements);
			}
		}
	}

	/**
	 * Import vector nodes in a snapshot into the database.
	 */
	private void importVectorsFromSnapshot(String sessionId, Long replicaId, IndexedModelDecoder index, SeekingNodeReader reader) throws IOException {
		Map<LogicalTimestamp, Entry> entries = index.getEntriesForType(IndexedNodeCodecMapper.VECTOR);
		Map<LogicalTimestamp, Entry> constantEntries = index.getEntriesForType(IndexedNodeCodecMapper.CONSTANT);
		if (entries.isEmpty()) {
			return;
		}

		dao.saveIndex(sessionId, replicaId, IndexType.vec, new ArrayList<>(entries.keySet()));


		for (List<Map.Entry<LogicalTimestamp, Entry>> batch : Iterables.partition(entries.entrySet(), SNAPSHOT_BATCH_SIZE)) {
			List<VectorNode> nodes = reader.readNodes(batch).stream()
				.map(n -> (VectorNode) n)
				.collect(Collectors.toList());

			// Populate each vector with its constant values
			for (VectorNode vector : nodes) {
				if (vector.getValues() != null) {
					vector.getValues().forEach((idx, constantNodeStub) -> {
						if (constantNodeStub != null) {
                            ConstantNode constantNodeWithData;
                            try {
                                constantNodeWithData = (ConstantNode) reader.readNode(constantNodeStub.getId(), constantEntries.get(constantNodeStub.getId()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            if (constantNodeWithData != null) {
								constantNodeStub.setValue(constantNodeWithData.getConValue());
							}
						}
					});
				}
			}

			dao.saveVectors(sessionId, replicaId, nodes);
		}
	}

	void createReplicaIfNotExist(String sessionId, Long replicaId) {
	 		createReplicaIfNotExist(sessionId, replicaId, true);
	}
	void createReplicaIfNotExist(String sessionId, Long replicaId, boolean insertRootNode) {
		if (dao.createReplicaIfNotExists(sessionId, replicaId)) {
			// this is the first patch of a replica.
			LogicalTimestamp rootId = new LogicalTimestamp().setReplicaId(0L).setSequenceNumber(0L);
			if (insertRootNode) {
				// create the root value of the document.
				dao.saveIndex(sessionId, replicaId, IndexType.val, List.of(rootId));
				dao.saveValues(sessionId, replicaId, List.of(new ValueNode().setId(rootId)));
			}
		}
	}

	/**
	 * Has the given patch already been applied to this replica?
	 * 
	 * @param sessionId
	 * @param replicaId
	 * @param patchId
	 * @return
	 */
	boolean isPatchAlreadyApplied(String sessionId, Long replicaId, LogicalTimestamp patchId) {
		return dao.getClockSequenceNumber(sessionId, replicaId, patchId.getReplicaId())
				.map(seq -> patchId.getSequenceNumber() < seq).orElse(false);
	}

	@Override
	public List<LogicalTimestamp> getClock(String sessionId, Long replicaId) {
		return dao.getClock(sessionId, replicaId);
	}

	@Override
	@GridTransaction(readOnly = false)
	public MessageChain startMessageChain(String sessionId, Long replicaId, String method) {
		createReplicaIfNotExist(sessionId, replicaId);
		Integer id = dao.createNextMessageId(sessionId, replicaId, MAX_MESSAGE_ID);
		return dao.createMessageChain(
				new MessageChain().setSessionId(sessionId).setReplicaId(replicaId).setMethod(method).setId(id),
				MAX_MESSAGE_DURATION);
	}

	@Override
	public Optional<MessageChain> getMessageChain(String sessionId, Long replicaId, Integer chainId) {
		return dao.getMessageChain(sessionId, replicaId, chainId);
	}

	@Override
	@GridTransaction(readOnly = false)
	public void completeMessageChain(String sessionId, Long replicaId, Integer chainId) {
		dao.deleteMessageChain(sessionId, replicaId, chainId);
	}

	@Override
	@GridTransaction(readOnly = false)
	public void truncateAll() {
		dao.truncateAll();
	}

	@Override
	@GridTransaction(readOnly = false)
	public boolean refreshMessageChain(String sessionId, Long replicaId, Integer chainId) {
		return dao.refreshMessageChain(sessionId, replicaId, chainId, MAX_MESSAGE_DURATION);
	}

	@Override
	public Optional<MessageChain> getNonExpiredMessageChain(String sessionId, Long replicaId, String method) {
		return dao.getNonExpiredMessageChain(sessionId, replicaId, method);
	}
}
