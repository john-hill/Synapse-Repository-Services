package org.sagebionetworks.repo.service.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.SearchIndex;

public class SearchIndexMetadataProviderTest {

	private SearchIndexMetadataProvider provider;

	@BeforeEach
	public void setUp() {
		provider = new SearchIndexMetadataProvider();
	}

	@Test
	public void testValidateEntityWithNonSageEmployee() {
		UserInfo user = new UserInfo(false);
		user.setId(999L);
		user.setGroups(Collections.emptySet());

		SearchIndex entity = new SearchIndex();
		entity.setDefiningSQL("SELECT * FROM syn123");

		EntityEvent event = new EntityEvent(EventType.CREATE, null, user);

		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			provider.validateEntity(entity, event);
		}).getMessage();

		assertEquals("Only Sage Bionetworks employees or admins can manage search index entities.", message);
	}

	@Test
	public void testValidateEntityWithSageEmployee() {
		UserInfo user = new UserInfo(false);
		user.setId(999L);
		user.setGroups(Set.of(TeamConstants.SAGE_BIONETWORKS_TEAM_ID));

		SearchIndex entity = new SearchIndex();
		entity.setDefiningSQL("SELECT * FROM syn123");

		EntityEvent event = new EntityEvent(EventType.CREATE, null, user);

		// call under test
		provider.validateEntity(entity, event);
	}

	@Test
	public void testValidateEntityWithAdmin() {
		UserInfo user = new UserInfo(true);
		user.setId(1L);

		SearchIndex entity = new SearchIndex();
		entity.setDefiningSQL("SELECT * FROM syn456");

		EntityEvent event = new EntityEvent(EventType.CREATE, null, user);

		// call under test
		provider.validateEntity(entity, event);
	}

	@Test
	public void testValidateEntityWithAnonymousUser() {
		UserInfo anon = new UserInfo(false);
		anon.setId(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		anon.setGroups(Set.of(anon.getId()));

		SearchIndex entity = new SearchIndex();
		entity.setDefiningSQL("SELECT * FROM syn123");

		EntityEvent event = new EntityEvent(EventType.CREATE, null, anon);

		String message = assertThrows(UnauthorizedException.class, () -> {
			// call under test
			provider.validateEntity(entity, event);
		}).getMessage();

		assertEquals("Only Sage Bionetworks employees or admins can manage search index entities.", message);
	}

	@Test
	public void testValidateEntityWithUpdateEvent() {
		UserInfo admin = new UserInfo(true);
		admin.setId(1L);

		SearchIndex entity = new SearchIndex();
		entity.setDefiningSQL("SELECT * FROM syn999");

		EntityEvent event = new EntityEvent(EventType.UPDATE, null, admin);

		// call under test
		provider.validateEntity(entity, event);
	}

	@Test
	public void testValidateDefiningSqlWithValidSingleEntity() {
		// call under test
		provider.validateDefiningSql("SELECT * FROM syn123");
	}

	@Test
	public void testValidateDefiningSqlWithSelectedColumns() {
		// call under test
		provider.validateDefiningSql("SELECT foo, bar FROM syn456");
	}

	@Test
	public void testValidateDefiningSqlWithMultiEntityJoin() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			provider.validateDefiningSql("SELECT a.x, b.y FROM syn123 a JOIN syn456 b ON a.id = b.id");
		});
	}

	@Test
	public void testValidateDefiningSqlWithNull() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			provider.validateDefiningSql(null);
		});
	}

	@Test
	public void testValidateDefiningSqlWithBlank() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			provider.validateDefiningSql("   ");
		});
	}

	@Test
	public void testValidateDefiningSqlWithEmptyString() {
		assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			provider.validateDefiningSql("");
		});
	}

	@ParameterizedTest(name = "SQL with whitespace/casing: {0}")
	@ValueSource(strings = {
		"  SELECT * FROM syn123  ",
		"select * from syn123",
		"SELECT  *  FROM  syn123",
		"Select studyName From syn123"
	})
	public void testValidateDefiningSqlWithWhitespaceAndCasingVariations(String sql) {
		// call under test
		provider.validateDefiningSql(sql);
	}

	@Test
	public void testValidateDefiningSqlWithWhereClause() {
		// call under test
		provider.validateDefiningSql("SELECT studyName FROM syn123 WHERE status = 'Active'");
	}
}
