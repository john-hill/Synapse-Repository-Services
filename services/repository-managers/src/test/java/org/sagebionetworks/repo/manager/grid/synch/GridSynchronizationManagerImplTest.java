package org.sagebionetworks.repo.manager.grid.synch;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.grid.synch.io.DiskPointer;
import org.sagebionetworks.repo.manager.grid.synch.io.RowReader;
import org.sagebionetworks.repo.manager.grid.synch.io.RowWriter;
import org.sagebionetworks.repo.manager.grid.synch.io.SynchRow;

@ExtendWith(MockitoExtension.class)
public class GridSynchronizationManagerImplTest {



	@BeforeEach
	public void before() {

	}

	RowReader setupSourceRows(List<SynchRow> rows) throws IOException {
		File temp = File.createTempFile("GridSynchronizationManagerImplTest", ".bin");
		temp.deleteOnExit();
		List<DiskPointer> diskPointers = new ArrayList<>();
		try (RowWriter writer = new RowWriter(new BufferedOutputStream(new FileOutputStream(temp)))) {
			rows.forEach(r -> {
				diskPointers.add(writer.nextRow(r));
			});
		}
		return new RowReader(diskPointers, new RandomAccessFile(temp, "r"));
	}
}
