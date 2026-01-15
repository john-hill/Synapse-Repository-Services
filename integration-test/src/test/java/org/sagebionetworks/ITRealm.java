package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
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
    
    // parameters for a new user, created inside the realm
    private static final String USER_NAME_IN_REALM = UUID.randomUUID().toString();
    private static final String PASSWORD_IN_REALM = "password"+UUID.randomUUID().toString();
    private static final String EMNAIL_IN_REALM = UUID.randomUUID().toString() + "@sagebase.org";


	@Test
	public void testRoundTrip() throws Exception {
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
		
		Long userIdInRealm = null;
		try {
			SynapseClientImpl synapseClientInNewRealm = new SynapseClientImpl();
			userIdInRealm = SynapseClientHelper.createUser(adminSynapse, synapseClientInNewRealm,
					USER_NAME_IN_REALM, PASSWORD_IN_REALM, EMNAIL_IN_REALM, true, false, IDPS.get(0));
			// can also determine the realm's principals from the user, inferring the realm through their access token:
			RealmPrincipal principals2 = synapseClientInNewRealm.getRealmPrincipals();
			assertEquals(principals, principals2);
		} finally {
			adminSynapse.deleteUser(userIdInRealm);
		}
		// delete realm
		adminSynapse.deleteRealm(id);
	}

}
