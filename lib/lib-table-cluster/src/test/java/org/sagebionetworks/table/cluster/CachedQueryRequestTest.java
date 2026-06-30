package org.sagebionetworks.table.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.repo.model.table.SelectColumn;

public class CachedQueryRequestTest {

	@Test
	public void testClone() {
		CachedQueryRequest request = new CachedQueryRequest().setExpiresInSec(60).setIncludeEntityEtag(true)
				.setIncludeBenefactorId(true).setIncludesRowIdAndVersion(false)
				.setOutputSQL("select * from syn123")
				.setParameters(Map.of("key", "value")).setSingleTableId("syn123")
				.setSelectColumns(List.of(new SelectColumn().setName("foo"))).setTableHash("hash");

		// call under test
		CachedQueryRequest clone = CachedQueryRequest.clone(request);
		assertEquals(request, clone);
	}

	@Test
	public void testCloneEmpty() {
		CachedQueryRequest request = new CachedQueryRequest();

		// call under test
		CachedQueryRequest clone = CachedQueryRequest.clone(request);
		assertEquals(request, clone);
	}

	@Test
	public void testIncludeBenefactorIdDefaultsFalse() {
		// call under test
		assertFalse(new CachedQueryRequest().getIncludeBenefactorId());
	}

	@Test
	public void testIncludeBenefactorIdSetTrue() {
		CachedQueryRequest request = new CachedQueryRequest().setIncludeBenefactorId(true);

		// call under test
		assertTrue(request.getIncludeBenefactorId());
	}

	@Test
	public void testClonePreservesIncludeBenefactorId() {
		CachedQueryRequest request = new CachedQueryRequest().setIncludeBenefactorId(true);

		// call under test
		CachedQueryRequest clone = CachedQueryRequest.clone(request);
		assertTrue(clone.getIncludeBenefactorId());
	}

}
