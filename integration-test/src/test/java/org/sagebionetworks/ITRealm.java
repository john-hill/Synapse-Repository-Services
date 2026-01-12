package org.sagebionetworks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.auth.IdentityProvider;
import org.sagebionetworks.repo.model.auth.OAuthIdentityProvider;
import org.sagebionetworks.repo.model.auth.Realm;
import org.sagebionetworks.repo.model.auth.RealmPrincipal;
import org.sagebionetworks.repo.model.oauth.OAuthProvider;

@ExtendWith(ITTestExtension.class)
public class ITRealm {

	private SynapseClient synapse;
	private SynapseAdminClient adminSynapse;

    public ITRealm(SynapseAdminClient adminSynapse, SynapseClient synapse) {
		this.adminSynapse = adminSynapse;
		this.synapse = synapse;
	}
    
    private static final String NAME = "test-realm";
    private static final List<IdentityProvider> IDPS = 
    		List.of(new OAuthIdentityProvider().setProvider(OAuthProvider.ARCUS_BIOSCIENCES));

	@Test
	public void testRoundTrip() throws SynapseException {
		// create realm
		Realm realm = new Realm();
		realm.setName(NAME);
		realm.setIdentityProvider(IDPS);
		Realm created = adminSynapse.createRealm(realm);
		String id = created.getId();
		assertNotNull(id);
		
		// list realms
		List<String> realms = synapse.listRealmIds().getRealms();
		assertTrue(realms.contains(id));
		
		// get realm by id
		Realm retrieved = synapse.getRealm(id);
		
		assertEquals(created, retrieved);
		
		// get realm principals
		RealmPrincipal principals = synapse.getRealmPrincipals(id);
		assertEquals(id, principals.getRealmId());
		assertNotNull(principals.getAnonymousUser());
		assertNotNull(principals.getAuthenticatedUsers());
		assertNotNull(principals.getPublicGroup());
		assertNotNull(principals.getAdministrativeGroup());
		
		// delete realm
		adminSynapse.deleteRealm(id);
	}

}
