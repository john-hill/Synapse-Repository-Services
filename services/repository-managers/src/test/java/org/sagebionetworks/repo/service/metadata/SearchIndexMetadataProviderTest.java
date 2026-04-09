package org.sagebionetworks.repo.service.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.search.SearchIndexValidator;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.TeamConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.search.table.SearchIndex;

@ExtendWith(MockitoExtension.class)
public class SearchIndexMetadataProviderTest {

	@Mock
	private SearchIndexValidator mockValidator;

	@InjectMocks
	private SearchIndexMetadataProvider provider;

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
		verifyZeroInteractions(mockValidator);
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

		verify(mockValidator).validateDefiningSQL("SELECT * FROM syn123");
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

		verify(mockValidator).validateDefiningSQL("SELECT * FROM syn456");
	}

	@Test
	public void testValidateDefiningSqlWithValidSql() {
		// call under test
		provider.validateDefiningSql("SELECT * FROM syn789");

		verify(mockValidator).validateDefiningSQL("SELECT * FROM syn789");
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
		verifyZeroInteractions(mockValidator);
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

		verify(mockValidator).validateDefiningSQL("SELECT * FROM syn999");
	}
}
