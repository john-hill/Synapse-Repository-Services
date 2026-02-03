package org.sagebionetworks.grid.db;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.model.grid.ClockTable;
import org.sagebionetworks.repo.model.grid.encoding.IndexedModelDecoder;
import org.sagebionetworks.repo.model.grid.node.ArrayNode;
import org.sagebionetworks.repo.model.grid.node.ConstantNode;
import org.sagebionetworks.repo.model.grid.node.IndexType;
import org.sagebionetworks.repo.model.grid.node.Node;
import org.sagebionetworks.repo.model.grid.node.ObjectNode;
import org.sagebionetworks.repo.model.grid.node.RGANode;
import org.sagebionetworks.repo.model.grid.node.ValueNode;
import org.sagebionetworks.repo.model.grid.node.VectorNode;
import org.sagebionetworks.repo.model.grid.patch.LogicalTimestamp;
import org.sagebionetworks.repo.model.grid.patch.Patch;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@GridTransaction(readOnly = true)
public class GridIndexManagerImpl implements GridIndexManager {

	public static final Duration MAX_MESSAGE_DURATION = Duration.ofSeconds(60);
	public static final int MAX_MESSAGE_ID = 65535;

	private static final int MAX_IMPORT_NODE_BATCH_SIZE = 500;
	private static final int MAX_VECTOR_NODE_BATCH_SIZE_FOR_CONSTANT_DENORMALIZE = 100;

	private static final Logger log = LogManager.getLogger(GridIndexManagerImpl.class);

	private final GridIndexDao dao;
	private final OperationDispatcher operationDispatcher;
	private final HttpClient httpClient;
	private final TransactionTemplate transactionTemplate;

	public GridIndexManagerImpl(GridIndexDao dao, OperationDispatcher operationDispatcher, HttpClient httpClient,
			@Qualifier("gridTransactionManager") PlatformTransactionManager transactionManager) {
		super();
		this.dao = dao;
		this.operationDispatcher = operationDispatcher;
		this.httpClient = httpClient;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
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
	public Map<IndexType, Set<LogicalTimestamp>> applySnapshot(String sessionId, Long replicaId, URL snapshotPresignedUrl) {
		ValidateArgument.required(sessionId, "sessionId");
		ValidateArgument.required(replicaId, "replicaId");
		ValidateArgument.required(snapshotPresignedUrl, "snapshotPresignedUrl");

		Path snapshotFile = null;
		try {
			snapshotFile = downloadSnapshotFile(snapshotPresignedUrl);
			return importSnapshot(sessionId, replicaId, snapshotFile);
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

			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

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

	Map<IndexType, Set<LogicalTimestamp>> importSnapshot(String sessionId, Long replicaId, Path snapshotFile) {
		// Delete the replica to clear the index; the snapshot will repopulate the index.
		dao.deleteReplica(sessionId, replicaId);

		// Recreate the replica. Exclude the root node, which is included in the snapshot.
		boolean insertRootNode = false;
		createReplicaIfNotExist(sessionId, replicaId, insertRootNode);

		Map<IndexType, Set<LogicalTimestamp>> changes = new EnumMap<>(IndexType.class);
		List<VectorNode> deferredVectors = new ArrayList<>();

		Supplier<InputStream> streamSupplier = () -> {
			try {
				return new BufferedInputStream(new FileInputStream(snapshotFile.toFile()));
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
		};
		ClockTable snapshotClockTable;
		try (IndexedModelDecoder decoder = new IndexedModelDecoder(streamSupplier)) {
			snapshotClockTable = decoder.getClockTable();
			AtomicInteger counter = new AtomicInteger();
			decoder.stream()
				.collect(Collectors.groupingBy(node -> counter.getAndIncrement() / MAX_VECTOR_NODE_BATCH_SIZE_FOR_CONSTANT_DENORMALIZE))
				.values()
				.forEach(batch -> {
					List<ConstantNode> constantNodeBatch = new ArrayList<>();
					List<ObjectNode> objectNodeBatch = new ArrayList<>();
					List<ValueNode> valueNodeBatch = new ArrayList<>();
					List<ArrayNode> arrayNodeBatch = new ArrayList<>();

					batch.forEach(node -> {
						if (node instanceof ConstantNode) {
							constantNodeBatch.add((ConstantNode) node);
						} else if (node instanceof ObjectNode) {
							objectNodeBatch.add((ObjectNode) node);
						} else if (node instanceof ValueNode) {
							valueNodeBatch.add((ValueNode) node);
						} else if (node instanceof ArrayNode) {
							arrayNodeBatch.add((ArrayNode) node);
						} else if (node instanceof VectorNode) {
							deferredVectors.add((VectorNode) node);
						} else {
							throw new IllegalArgumentException("Unsupported node type in batch: " + node.getClass().getName());
						}
						IndexType type = getIndexType(node);
						changes.computeIfAbsent(type, k -> new HashSet<>()).add(node.getId());
					});

					if (!constantNodeBatch.isEmpty()) {
						dao.saveIndex(sessionId, replicaId, IndexType.con, constantNodeBatch.stream().map(ConstantNode::getId).collect(Collectors.toList()));
						dao.saveNewConstants(sessionId, replicaId, constantNodeBatch);
					}
					if (!objectNodeBatch.isEmpty()) {
						dao.saveIndex(sessionId, replicaId, IndexType.obj, objectNodeBatch.stream().map(ObjectNode::getId).collect(Collectors.toList()));
						dao.saveObjects(sessionId, replicaId, objectNodeBatch);
					}
					if (!valueNodeBatch.isEmpty()) {
						dao.saveIndex(sessionId, replicaId, IndexType.val, valueNodeBatch.stream().map(ValueNode::getId).collect(Collectors.toList()));
						dao.saveValues(sessionId, replicaId, valueNodeBatch);
					}
					if (!arrayNodeBatch.isEmpty()) {
						dao.saveIndex(sessionId, replicaId, IndexType.arr, arrayNodeBatch.stream().map(ArrayNode::getId).collect(Collectors.toList()));
						dao.createArrayBatch(sessionId, replicaId, arrayNodeBatch.stream().map(ArrayNode::getId).collect(Collectors.toList()));
						List<RGANode> arrayElements = arrayNodeBatch.stream().flatMap((arrayNode) -> arrayNode.getElements().stream()).collect(Collectors.toList());
						if (!arrayElements.isEmpty()) {
							dao.insertIntoRepeatedGrowableArrayBatch(sessionId, replicaId, arrayElements);
						}
					} else {
						throw new IllegalArgumentException("Unsupported node type: " + batch.getClass().getName());
					}
				});
		} catch (IOException e) {
			throw new RuntimeException("Failed to import snapshot from file: " + snapshotFile.toString(), e);
		}

		// Process deferred vectors - constants are now in DB
		if (!deferredVectors.isEmpty()) {
			saveVectorsWithResolvedConstants(sessionId, replicaId, deferredVectors);
		}

		// Update the replica clock
		dao.setClocks(sessionId, replicaId, snapshotClockTable.getClocks());

		return changes;
	}

	/**
	 * Resolves constant values for deferred vectors and saves them to the database.
	 * This is called after all constants have been imported, ensuring constant values
	 * are available for lookup.
	 */
	void saveVectorsWithResolvedConstants(String sessionId, Long replicaId, List<VectorNode> vectors) {
		AtomicInteger counter = new AtomicInteger();
		vectors.stream()
			// Maximum number of vectors to process at once to avoid loading all constant data in memory
			.collect(Collectors.groupingBy(node -> counter.getAndIncrement() / MAX_VECTOR_NODE_BATCH_SIZE_FOR_CONSTANT_DENORMALIZE))
			.values().forEach(batch -> {
				// Collect ALL constant IDs referenced in the batch
				Set<LogicalTimestamp> allConstantIds = batch.stream()
						.filter(v -> v.getValues() != null)
						.flatMap(v -> v.getValues().values().stream())
						.filter(Objects::nonNull)
						.map(ConstantNode::getId)
						.collect(Collectors.toSet());

				// Get the constant values
				Map<LogicalTimestamp, ConstantNode> constantMap = dao
						.getConstants(sessionId, replicaId, new ArrayList<>(allConstantIds)).stream()
						.collect(Collectors.toMap(ConstantNode::getId, c -> c));

				// Populate each vector with resolved constant values
				for (VectorNode vector : batch) {
					if (vector.getValues() != null) {
						vector.getValues().forEach((index, stub) -> {
							if (stub != null) {
								ConstantNode full = constantMap.get(stub.getId());
								if (full != null) {
									stub.setValue(full.getConValue());
								}
							}
						});
					}
				}

				// Save the batch
				dao.saveIndex(sessionId, replicaId, IndexType.vec, batch.stream().map(VectorNode::getId).collect(Collectors.toList()));
				dao.saveVectors(sessionId, replicaId, batch);
			});


	}

	private IndexType getIndexType(Node node) {
		if (node instanceof ConstantNode) {
			return IndexType.con;
		} else if (node instanceof ObjectNode) {
			return IndexType.obj;
		} else if (node instanceof ValueNode) {
			return IndexType.val;
		} else if (node instanceof VectorNode) {
			return IndexType.vec;
		} else if (node instanceof ArrayNode) {
			return IndexType.arr;
		}
		throw new IllegalArgumentException("Unknown node type: " + node.getClass().getName());
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
