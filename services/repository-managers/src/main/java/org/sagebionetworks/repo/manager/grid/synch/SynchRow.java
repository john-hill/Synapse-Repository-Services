package org.sagebionetworks.repo.manager.grid.synch;

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
import org.sagebionetworks.repo.model.grid.patch.ConValue;

public class SynchRow {

	private final Map<String, ConValue> data;
	private final String key;
	private final byte[] hash;
	private final byte[] bytes;

	public SynchRow(Map<String, ConValue> data, String key) {
		super();
		this.data = data;
		this.key = key;
		this.bytes = generateBytes();
		this.hash = generateHash();
	}

	public SynchRow(byte[] bytes, String key) {
		super();
		this.key = key;
		this.bytes = bytes;
		this.hash = generateHash();
		this.data = generateDataFromBytes();
	}

	public Map<String, ConValue> getData() {
		return data;
	}

	public String getKey() {
		return key;
	}

	public byte[] getHash() {
		return hash;
	}

	public byte[] getBytes() {
		return bytes;
	}

	private Map<String, ConValue> generateDataFromBytes() {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
				DataInputStream dis = new DataInputStream(bais)) {

			Map<String, ConValue> result = new TreeMap<>();

			while (dis.available() > 0) {
				String mapKey = dis.readUTF();
				String valueString = dis.readUTF();
				// Convert string back to ConValue - adjust based on ConValue's structure
				ConValue value = ConValue.fromCompact(new JSONArray(valueString));
				result.put(mapKey, value);
			}

			return result;
		} catch (IOException e) {
			throw new RuntimeException("Failed to generate data from bytes", e);
		}
	}

	private byte[] generateBytes() {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
				DataOutputStream dos = new DataOutputStream(baos)) {

			// Sort keys alphabetically
			TreeMap<String, ConValue> sortedData = new TreeMap<>(data);

			for (Map.Entry<String, ConValue> entry : sortedData.entrySet()) {
				dos.writeUTF(entry.getKey());
				// Write ConValue data - adjust based on ConValue's structure
				dos.writeUTF(entry.getValue().toCompact().toString());
			}

			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException("Failed to generate bytes", e);
		}
	}

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

}
