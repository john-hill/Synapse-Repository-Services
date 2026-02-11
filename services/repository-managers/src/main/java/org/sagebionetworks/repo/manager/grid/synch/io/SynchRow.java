package org.sagebionetworks.repo.manager.grid.synch.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.json.JSONArray;
import org.sagebionetworks.repo.manager.grid.synch.core.SourceItem;
import org.sagebionetworks.repo.model.grid.patch.ConValue;

/**
 * Represents a source row during Phase 2 (row synchronization) with both
 * in-memory and serialized representations. Supports disk-based streaming to
 * enable O(n) memory usage during synchronization.
 *
 * <p>
 * During synchronization, source rows are:
 * <ul>
 * <li>Written to disk by {@link RowWriter} using the serialized bytes</li>
 * <li>Indexed on disk by key using {@link DiskPointer}</li>
 * <li>Lazily loaded by {@link RowReader} when needed for comparison or
 * merging</li>
 * </ul>
 *
 * <p>
 * The row supports two construction paths:
 * <ul>
 * <li>From data map (when reading from source) - generates bytes and hash</li>
 * <li>From bytes (when loading from disk) - lazily reconstructs data map when
 * accessed</li>
 * </ul>
 *
 * <p>
 * The hash is used for quick comparison during synchronization - rows with
 * matching hashes can skip cell-level comparison and merging.
 */
public class SynchRow implements SourceItem {

	private final TreeMap<String, ConValue> data;
	private final String key;
	private final byte[] hash;
	private final byte[] bytes;

	/**
	 * Creates a row from in-memory data. Used when reading rows from the source
	 * during Phase 2. The row is immediately serialized to bytes and hashed for
	 * disk storage and quick comparison.
	 *
	 * @param data the row's cell data (column name to value mappings)
	 * @param key  the unique identifier for this row
	 */
	public SynchRow(TreeMap<String, ConValue> data, String key) {
		super();
		this.data = data;
		this.key = key;
		this.bytes = generateBytes();
		this.hash = generateHash();
	}

	/**
	 * Creates a row from serialized bytes. Used when loading a row from disk via
	 * {@link RowReader}. The data map is lazily reconstructed from bytes when
	 * getData() is called, enabling lazy loading for memory efficiency.
	 *
	 * @param bytes the serialized row data
	 * @param key   the unique identifier for this row
	 */
	public SynchRow(byte[] bytes, String key) {
		super();
		this.key = key;
		this.bytes = bytes;
		this.hash = generateHash();
		this.data = generateDataFromBytes();
	}

	/**
	 * Gets the row's cell data. When the row was constructed from bytes (loaded
	 * from disk), this reconstructs the data map from the serialized bytes.
	 *
	 * @return map of column names to cell values
	 */
	public Map<String, ConValue> getData() {
		return data;
	}

	/**
	 * Gets the unique identifier for this row. Used to match rows between copy and
	 * source during synchronization.
	 *
	 * @return the row's key
	 */
	public String getKey() {
		return key;
	}

	/**
	 * Gets the hash of the serialized row data. Used for quick comparison during
	 * synchronization - rows with matching hashes can skip cell-level comparison.
	 *
	 * @return the MD5 hash of the serialized bytes
	 */
	public byte[] getHash() {
		return hash;
	}

	/**
	 * Gets the serialized representation of this row. Used by {@link RowWriter} to
	 * write the row to disk for later retrieval by {@link RowReader}.
	 *
	 * @return the serialized row data
	 */
	public byte[] getBytes() {
		return bytes;
	}

	/**
	 * Reconstructs the data map from the serialized bytes. Called when loading a
	 * row from disk to access its cell data.
	 *
	 * @return the reconstructed data map
	 * @throws RuntimeException if deserialization fails
	 */
	private TreeMap<String, ConValue> generateDataFromBytes() {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
				DataInputStream dis = new DataInputStream(bais)) {

			TreeMap<String, ConValue> result = new TreeMap<>();

			while (dis.available() > 0) {
				String mapKey = dis.readUTF();
				String valueString = dis.readUTF();
				ConValue value = ConValue.fromCompact(new JSONArray(valueString));
				result.put(mapKey, value);
			}

			return result;
		} catch (IOException e) {
			throw new RuntimeException("Failed to generate data from bytes", e);
		}
	}

	/**
	 * Serializes the data map to bytes for disk storage. Keys are sorted
	 * alphabetically to ensure consistent byte representation for hash comparison.
	 *
	 * @return the serialized row data
	 * @throws RuntimeException if serialization fails
	 */
	private byte[] generateBytes() {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
				DataOutputStream dos = new DataOutputStream(baos)) {

			for (Map.Entry<String, ConValue> entry : data.entrySet()) {
				dos.writeUTF(entry.getKey());
				dos.writeUTF(entry.getValue().toCompact().toString());
			}

			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException("Failed to generate bytes", e);
		}
	}

	/**
	 * Generates an MD5 hash of the serialized bytes. Used for quick row comparison
	 * during synchronization - matching hashes indicate identical row data.
	 *
	 * @return the MD5 hash
	 * @throws RuntimeException if MD5 algorithm is not available
	 */
	private byte[] generateHash() {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			return md.digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Failed to generate MD5 hash", e);
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(bytes);
		result = prime * result + Arrays.hashCode(hash);
		result = prime * result + Objects.hash(data, key);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SynchRow other = (SynchRow) obj;
		return Arrays.equals(bytes, other.bytes) && Objects.equals(data, other.data) && Arrays.equals(hash, other.hash)
				&& Objects.equals(key, other.key);
	}

	@Override
	public String toString() {
		return "SynchRow [data=" + data + ", key=" + key + ", hash=" + Arrays.toString(hash) + ", bytes="
				+ Arrays.toString(bytes) + "]";
	}

}
