// LEAVE COMMENTED OUT DO NOT USE
//package org.sagebionetworks;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertNotNull;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
//import java.io.File;
//import java.io.FileWriter;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.UUID;
//
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.sagebionetworks.client.AsynchJobType;
//import org.sagebionetworks.client.SynapseAdminClient;
//import org.sagebionetworks.client.SynapseClient;
//import org.sagebionetworks.client.exceptions.SynapseException;
//import org.sagebionetworks.repo.model.AuthorizationConstants;
//import org.sagebionetworks.repo.model.Entity;
//import org.sagebionetworks.repo.model.Project;
//import org.sagebionetworks.repo.model.file.FileHandle;
//import org.sagebionetworks.repo.model.table.ColumnModel;
//import org.sagebionetworks.repo.model.table.ColumnType;
//import org.sagebionetworks.repo.model.table.TableEntity;
//import org.sagebionetworks.repo.model.table.UploadToTableRequest;
//import org.sagebionetworks.repo.model.table.UploadToTableResult;
//import org.sagebionetworks.repo.model.search.table.SearchIndex;
//import org.sagebionetworks.repo.model.search.SearchQueryType;
//import org.sagebionetworks.repo.model.search.SearchQueryResults;
//import org.sagebionetworks.repo.model.search.table.SearchIndexQuery;
//
//import au.com.bytecode.opencsv.CSVWriter;
//
///**
// * Performance comparison test for SearchIndex at different data scales.
// * <p>
// * Builds search indexes over tables with 1,000 and 500,000 rows,
// * then measures and reports timing for each phase:
// * row insertion, index build, full-text query, and autocomplete.
// * <p>
// * Run independently:
// * <pre>
// * mvn test -pl integration-test -Dtest=ITSearchQueryPerformanceTest
// * </pre>
// */
//@ExtendWith(ITTestExtension.class)
//public class ITSearchQueryPerformanceTest {
//
//	private static final long MAX_QUERY_TIMEOUT_MS = 1000 * 60 * 10; // 10 min for large indexes
//	private static final long MAX_CSV_UPLOAD_TIMEOUT = 1000 * 60 * 10; // 10 min for large CSV uploads
//
//	private final SynapseAdminClient adminSynapse;
//	private final SynapseClient synapse;
//	private final List<Entity> entitiesToDelete = new ArrayList<>();
//
//	private static final String SAGE_TEAM_ID = AuthorizationConstants.BOOTSTRAP_PRINCIPAL.SAGE_BIONETWORKS
//			.getPrincipalId().toString();
//
//	// Sample data pools for realistic content
//	private static final String[] STUDY_NAMES = {
//		"Alzheimer's Disease Genetics Study", "Cancer Genomics Atlas", "Parkinson's Research Initiative",
//		"Cardiovascular Health Study", "Diabetes Prevention Program", "Multiple Sclerosis Consortium",
//		"Lung Cancer Screening Trial", "Autism Spectrum Research", "Breast Cancer Genomics Project",
//		"Inflammatory Bowel Disease Study", "Rare Disease Network", "Huntington's Disease Registry",
//		"Epilepsy Genome Project", "Cystic Fibrosis Foundation Study", "Sickle Cell Disease Consortium",
//		"Melanoma Genomics Initiative", "Leukemia Research Program", "Liver Disease Cohort",
//		"Kidney Function Study", "Osteoporosis Prevention Trial"
//	};
//
//	private static final String[] DESCRIPTIONS = {
//		"A large-scale genome-wide association study investigating genetic risk factors",
//		"Comprehensive molecular characterization of tumors across multiple cancer types",
//		"Investigating dopaminergic neuron degeneration and potential therapeutic targets",
//		"Longitudinal cohort study examining cardiovascular risk in diverse populations",
//		"Randomized controlled trial of lifestyle interventions for diabetes prevention",
//		"Multi-center consortium studying autoimmune demyelination mechanisms",
//		"Evaluating low-dose CT screening for early lung cancer detection",
//		"Examining genetic and environmental factors in neurodevelopmental disorders",
//		"Whole-genome sequencing analysis of hereditary breast cancer susceptibility",
//		"Studying gut microbiome interactions in inflammatory bowel conditions",
//		"Collaborative network for diagnosis and treatment of rare genetic diseases",
//		"International registry tracking disease progression and biomarker changes",
//		"Identifying genetic variants associated with epilepsy subtypes",
//		"Studying CFTR mutations and novel therapeutic approaches",
//		"Multi-ethnic study of sickle cell disease complications and outcomes",
//		"Genomic profiling of melanoma for targeted immunotherapy approaches",
//		"Investigating molecular subtypes and treatment resistance in leukemia",
//		"Prospective cohort study of non-alcoholic fatty liver disease progression",
//		"Genome-wide study of renal function decline and chronic kidney disease",
//		"Clinical trial of bisphosphonate therapy for postmenopausal osteoporosis"
//	};
//
//	private static final String[] INSTITUTIONS = {
//		"Harvard Medical School", "Stanford University", "Johns Hopkins University",
//		"Mayo Clinic", "NIH Clinical Center", "MIT", "University of Cambridge",
//		"Karolinska Institute", "Max Planck Institute", "University of Tokyo",
//		"UCSF Medical Center", "Duke University", "Columbia University",
//		"University of Michigan", "Broad Institute", "Sanger Institute",
//		"Baylor College of Medicine", "Vanderbilt University", "University of Washington",
//		"Memorial Sloan Kettering"
//	};
//
//	public ITSearchQueryPerformanceTest(SynapseAdminClient adminSynapse, SynapseClient synapse) {
//		this.adminSynapse = adminSynapse;
//		this.synapse = synapse;
//	}
//
//	@BeforeEach
//	public void before() throws SynapseException {
//		adminSynapse.clearAllLocks();
//		String userId = synapse.getMyProfile().getOwnerId();
//		adminSynapse.addTeamMember(SAGE_TEAM_ID, userId, null, null);
//	}
//
//	@AfterEach
//	public void after() {
//		try {
//			adminSynapse.removeTeamMember(SAGE_TEAM_ID, synapse.getMyProfile().getOwnerId());
//		} catch (SynapseException e) {
//			// ignore
//		}
//		for (int i = entitiesToDelete.size() - 1; i >= 0; i--) {
//			try {
//				adminSynapse.deleteEntity(entitiesToDelete.get(i));
//			} catch (SynapseException e) {
//				// ignore
//			}
//		}
//	}
//
////	@Test
////	public void testPerformanceComparison_1Rows() throws Exception {
////		runPerformanceBenchmark(1, "1_ROWS");
////	}
//
//	@Test
//	public void testPerformanceComparison_1kRows() throws Exception {
//		runPerformanceBenchmark(1_000, "1K_ROWS");
//	}
////
////	@Test
////	public void testPerformanceComparison_500kRows() throws Exception {
////		runPerformanceBenchmark(500_000, "500K_ROWS");
////	}
//
//	private void runPerformanceBenchmark(int rowCount, String label) throws Exception {
//		System.out.println("\n========================================");
//		System.out.println("  PERFORMANCE BENCHMARK: " + label + " (" + rowCount + " rows)");
//		System.out.println("========================================");
//
//		// --- Phase 1: Setup (project + columns + table) ---
//		long phaseStart = System.currentTimeMillis();
//
//		Project project = new Project();
//		project.setName("ITPerfTest_" + label + "_" + UUID.randomUUID());
//		project = synapse.createEntity(project);
//		entitiesToDelete.add(project);
//
//		ColumnModel studyNameCol = new ColumnModel();
//		studyNameCol.setName("studyName");
//		studyNameCol.setColumnType(ColumnType.STRING);
//		studyNameCol.setMaximumSize(200L);
//		studyNameCol = synapse.createColumnModel(studyNameCol);
//
//		ColumnModel descriptionCol = new ColumnModel();
//		descriptionCol.setName("description");
//		descriptionCol.setColumnType(ColumnType.STRING);
//		descriptionCol.setMaximumSize(500L);
//		descriptionCol = synapse.createColumnModel(descriptionCol);
//
//		ColumnModel institutionCol = new ColumnModel();
//		institutionCol.setName("institution");
//		institutionCol.setColumnType(ColumnType.STRING);
//		institutionCol.setMaximumSize(200L);
//		institutionCol = synapse.createColumnModel(institutionCol);
//
//		ColumnModel yearCol = new ColumnModel();
//		yearCol.setName("year");
//		yearCol.setColumnType(ColumnType.INTEGER);
//		yearCol = synapse.createColumnModel(yearCol);
//
//		TableEntity table = new TableEntity();
//		table.setName("PerfTestTable_" + label);
//		table.setParentId(project.getId());
//		table.setColumnIds(Arrays.asList(
//				studyNameCol.getId(), descriptionCol.getId(),
//				institutionCol.getId(), yearCol.getId()));
//		table = synapse.createEntity(table);
//		entitiesToDelete.add(table);
//
//		long setupMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Setup (project + columns + table): " + setupMs + " ms");
//
//		// --- Phase 2: CSV bulk upload ---
//		phaseStart = System.currentTimeMillis();
//
//		File csvFile = File.createTempFile("PerfTest_" + label + "_", ".csv");
//		csvFile.deleteOnExit();
//		try (CSVWriter csv = new CSVWriter(new FileWriter(csvFile))) {
//			csv.writeNext(new String[] { "studyName", "description", "institution", "year" });
//			for (int i = 0; i < rowCount; i++) {
//				csv.writeNext(new String[] {
//					STUDY_NAMES[i % STUDY_NAMES.length] + " #" + i,
//					DESCRIPTIONS[i % DESCRIPTIONS.length] + " (cohort " + i + ")",
//					INSTITUTIONS[i % INSTITUTIONS.length],
//					String.valueOf(2000 + (i % 26))
//				});
//			}
//		}
//
//		long csvWriteMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] CSV file generated (" + rowCount + " rows): " + csvWriteMs + " ms");
//
//		phaseStart = System.currentTimeMillis();
//		FileHandle fileHandle = synapse.multipartUpload(csvFile, null, false, false);
//
//		long uploadMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] CSV file uploaded: " + uploadMs + " ms");
//
//		phaseStart = System.currentTimeMillis();
//		UploadToTableRequest uploadRequest = new UploadToTableRequest();
//		uploadRequest.setTableId(table.getId());
//		uploadRequest.setUploadFileHandleId(fileHandle.getId());
//
//		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.TableCSVUpload, uploadRequest,
//			(UploadToTableResult uploadResult) -> {
//				assertNotNull(uploadResult.getEtag());
//				assertEquals(Long.valueOf(rowCount), uploadResult.getRowsProcessed());
//			},
//			MAX_CSV_UPLOAD_TIMEOUT
//		);
//
//		long insertMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] CSV to table import (" + rowCount + " rows): " + insertMs + " ms");
//
//		// --- Phase 3: Create SearchIndex + wait for it to become ACTIVE ---
//		phaseStart = System.currentTimeMillis();
//
//		SearchIndex searchIndex = new SearchIndex();
//		searchIndex.setName("PerfSearchIndex_" + label);
//		searchIndex.setParentId(project.getId());
//		searchIndex.setDefiningSQL("select * from " + table.getId());
//		searchIndex = synapse.createEntity(searchIndex);
//		entitiesToDelete.add(searchIndex);
//
//		long createEntityMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] SearchIndex entity creation: " + createEntityMs + " ms");
//
//		// Wait for the index to be fully built by querying until we get all rows back
//		phaseStart = System.currentTimeMillis();
//		final long expectedRows = rowCount;
//
//		SearchIndexQuery waitIndexQuery = new SearchIndexQuery();
//		waitIndexQuery.setSearchIndexId(searchIndex.getId());
//
//		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, waitIndexQuery,
//			(SearchQueryResults results) -> {
//				assertNotNull(results);
//				assertEquals(expectedRows, (long) results.getTotalHits(),
//						"Expected all " + expectedRows + " rows indexed");
//			},
//			MAX_QUERY_TIMEOUT_MS,
//			AsyncJobHelper.INFINITE_RETRIES
//		);
//
//		long indexBuildMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Index build + activation (match-all returns all rows): "
//				+ indexBuildMs + " ms");
//
//		// --- Phase 4: Full-text search query ---
//		phaseStart = System.currentTimeMillis();
//
//		SearchIndexQuery searchIndexQuery = new SearchIndexQuery();
//		searchIndexQuery.setSearchIndexId(searchIndex.getId());
//		searchIndexQuery.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
//		searchIndexQuery.setQueryText("Alzheimer");
//
//		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, searchIndexQuery,
//			(SearchQueryResults results) -> {
//				assertNotNull(results);
//				assertTrue(results.getTotalHits() > 0, "Expected hits for 'Alzheimer'");
//			},
//			MAX_QUERY_TIMEOUT_MS,
//			AsyncJobHelper.INFINITE_RETRIES
//		);
//
//		long fullTextMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Full-text query ('Alzheimer'): " + fullTextMs + " ms");
//
//		// --- Phase 5: Another full-text query (different term) ---
//		phaseStart = System.currentTimeMillis();
//
//		SearchIndexQuery searchIndexQuery2 = new SearchIndexQuery();
//		searchIndexQuery2.setSearchIndexId(searchIndex.getId());
//		searchIndexQuery2.setQueryType(SearchQueryType.SIMPLE_QUERY_STRING);
//		searchIndexQuery2.setQueryText("genome-wide association");
//
//		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, searchIndexQuery2,
//			(SearchQueryResults results) -> {
//				assertNotNull(results);
//				assertTrue(results.getTotalHits() > 0, "Expected hits for 'genome-wide association'");
//			},
//			MAX_QUERY_TIMEOUT_MS,
//			AsyncJobHelper.INFINITE_RETRIES
//		);
//
//		long fullText2Ms = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Full-text query ('genome-wide association'): " + fullText2Ms + " ms");
//
//		// --- Phase 6: Autocomplete query ---
//		phaseStart = System.currentTimeMillis();
//
//		SearchIndexQuery autocompleteIndexQuery = new SearchIndexQuery();
//		autocompleteIndexQuery.setSearchIndexId(searchIndex.getId());
//		autocompleteIndexQuery.setQueryText("Canc");
//
//		SearchQueryResults autocompleteResults = synapse.searchAutocomplete(autocompleteIndexQuery);
//		assertNotNull(autocompleteResults);
//		assertTrue(autocompleteResults.getTotalHits() > 0, "Expected autocomplete hits for 'Canc'");
//
//		long autocompleteMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Autocomplete query ('Canc'): " + autocompleteMs + " ms");
//
//		// --- Phase 7: Match-all query (measures full scan overhead) ---
//		phaseStart = System.currentTimeMillis();
//
//		SearchIndexQuery matchAllIndexQuery = new SearchIndexQuery();
//		matchAllIndexQuery.setSearchIndexId(searchIndex.getId());
//
//		AsyncJobHelper.assertAysncJobResult(synapse, AsynchJobType.SearchIndexQuery, matchAllIndexQuery,
//			(SearchQueryResults results) -> {
//				assertNotNull(results);
//				assertEquals(expectedRows, (long) results.getTotalHits());
//			},
//			MAX_QUERY_TIMEOUT_MS,
//			AsyncJobHelper.INFINITE_RETRIES
//		);
//
//		long matchAllMs = System.currentTimeMillis() - phaseStart;
//		System.out.println("[" + label + "] Match-all query: " + matchAllMs + " ms");
//
//		// --- Summary ---
//		System.out.println("\n--- SUMMARY: " + label + " ---");
//		System.out.println("  Setup:                " + setupMs + " ms");
//		System.out.println("  Row insertion:        " + insertMs + " ms");
//		System.out.println("  Index build:          " + indexBuildMs + " ms");
//		System.out.println("  Full-text #1:         " + fullTextMs + " ms");
//		System.out.println("  Full-text #2:         " + fullText2Ms + " ms");
//		System.out.println("  Autocomplete:         " + autocompleteMs + " ms");
//		System.out.println("  Match-all:            " + matchAllMs + " ms");
//		System.out.println("  TOTAL:                " + (setupMs + insertMs + indexBuildMs
//				+ fullTextMs + fullText2Ms + autocompleteMs + matchAllMs) + " ms");
//		System.out.println("----------------------------------------\n");
//	}
//
//}
