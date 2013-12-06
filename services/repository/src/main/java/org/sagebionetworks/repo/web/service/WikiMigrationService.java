package org.sagebionetworks.repo.web.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.PaginatedResults;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.dao.DBOWikiMigrationDAO;
import org.sagebionetworks.repo.model.migration.WikiMigrationResult;
import org.sagebionetworks.repo.model.migration.WikiMigrationResultType;
import org.sagebionetworks.repo.model.v2.wiki.V2WikiPage;
import org.sagebionetworks.repo.model.wiki.WikiPage;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.WikiModelTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class WikiMigrationService {
	@Autowired
	private DBOWikiMigrationDAO wikiMigrationDao;
	@Autowired
	private WikiModelTranslator wikiModelTranslationHelper;
	@Autowired
	private UserManager userManager;

	public WikiMigrationService() {
	}
	
	/**
	 * Migrates a group of wikis from the V1 WikiPage DB to the V2 WikiPage DB.
	 * @param username
	 * @param limit
	 * @param offset
	 * @param servletPath
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	public PaginatedResults<WikiMigrationResult> migrateSomeWikis(Long username, long limit, long offset, String servletPath) throws NotFoundException, IOException {
		// Service is restricted to admins
		UserInfo userInfo;
		try {
			userInfo = userManager.getUserInfo(username);
			if (!userInfo.isAdmin()) {
				throw new UnauthorizedException("Must be an admin to use this service");
			}
		} catch (NotFoundException e1) {
			throw new UnauthorizedException("Must be an admin to use this service");
		}
		
		List<WikiMigrationResult> migrationResults = new ArrayList<WikiMigrationResult>();
		List<WikiPage> wikisToMigrate = wikiMigrationDao.getWikiPages(limit, offset);

		for(WikiPage wiki: wikisToMigrate) {
			WikiMigrationResult result = new WikiMigrationResult();
			result.setWikiId(wiki.getId());
			
			try {
				V2WikiPage clone = migrate(wiki, userInfo);
				if(clone != null) {
					if(clone.getId() != null) {
						result.setEtag(clone.getEtag());
						result.setResultType(WikiMigrationResultType.SUCCESS);
						result.setMessage("WikiPage with ID " + wiki.getId() + " has successfully migrated to the V2 WikiPage DB.");
					} else {
						result.setEtag(null);
						result.setResultType(WikiMigrationResultType.SKIPPED);
						result.setMessage("WikiPage with ID " + wiki.getId() + "has not been modified since last migration. Nothing was changed.");
					}
				
				} else {
					result.setResultType(WikiMigrationResultType.FAILURE);
					result.setMessage("WikiPage with ID " + wiki.getId() + " failed to migrate completely.");
				}
			} catch(Exception e) {
				result.setResultType(WikiMigrationResultType.FAILURE);
				result.setMessage("WikiPage with ID " + wiki.getId() + 
						" failed to migrate to the V2 WikiPage DB because: \n" +
						e + "\n");
			}

			migrationResults.add(result);
		}
		return new PaginatedResults<WikiMigrationResult>(servletPath + UrlHelpers.ADMIN_MIGRATE_WIKI, migrationResults, 
			wikiMigrationDao.getTotalCount(), offset, limit, "", false);
	}
		
	/**
	 * Converts the wiki to a V2 wiki, ensures that its parent wiki 
	 * is already migrated, and then migrates a wiki.
	 * 
	 * @param wiki
	 * @param userInfo
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public V2WikiPage migrate(WikiPage wiki, UserInfo userInfo) throws NotFoundException, IOException {	
		// Compare etag of wiki page to the etag of the V2 wiki page
		// If they are different, continue migration
		// Otherwise, return an empty wiki page to signal this skip
		if(wiki.getEtag().equals(wikiMigrationDao.getV2WikiPageEtag(wiki.getId()))) {
			return new V2WikiPage();
		}
		
		String parentId = wiki.getParentWikiId();
		if(parentId != null && !wikiMigrationDao.hasWikiMigrated(parentId)) {
			//get parent wiki
			WikiPage parent = wikiMigrationDao.getWikiPage(parentId);
			try {
				V2WikiPage migratedParent = migrate(parent, userInfo);
				if(migratedParent == null) {
					return null;
				}
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		V2WikiPage clone;
		try {
			// Convert the WikiPage to a V2WikiPage
			V2WikiPage translated = wikiModelTranslationHelper.convertToV2WikiPage(wiki, userInfo);
			// Migrate it to the V2 DB
			clone = wikiMigrationDao.migrateWiki(translated);
			
		} catch(Exception e) {
			throw new RuntimeException(e);
		}

		return clone;
	}
}
