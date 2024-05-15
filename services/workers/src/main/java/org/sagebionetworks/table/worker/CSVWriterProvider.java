package org.sagebionetworks.table.worker;

import java.io.FileWriter;

import org.sagebionetworks.repo.model.table.CsvTableDescriptor;

import au.com.bytecode.opencsv.CSVWriter;

public interface CSVWriterProvider {

	CSVWriter createWriter(FileWriter fileWriter, CsvTableDescriptor csvTableDescriptor);

}
