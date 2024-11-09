package org.sagebionetworks.utils;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sagebionetworks.downloadtools.FileUtils;

/**
 * Tests for FileUtils.
 * @author John
 *
 */
public class FileUtilsTest {
	
	List<File> toDelete;
	
	@BeforeEach
	public void before(){
		toDelete = new ArrayList<File>();
	}
	
	@AfterEach
	public void after(){
		if(toDelete!=null){
			for(File file: toDelete){
				file.delete();
			}
		}
	}
	
	@Test
	public void testEqualsChunkSize() throws IOException{
		File temp = File.createTempFile("FileUtilsTest", ".tmp");
		toDelete.add(temp);
		// fill it with 5 bytes
		byte[] data = new byte[]{1,2,3,4,5};
		FileOutputStream fos = new FileOutputStream(temp);
		try{
			fos.write(data);
		}finally{
			fos.close();
		}
		// Now chunk this file
		List<File> chunks = FileUtils.chunkFile(temp, data.length);
		assertNotNull(chunks);
		assertEquals(1, chunks.size());
		assertTrue(chunks.contains(temp));
	}

	@Test
	public void testSmallerChunkSizeOdd() throws IOException{
		File temp = File.createTempFile("FileUtilsTest", ".tmp");
		toDelete.add(temp);
		// fill it with 5 bytes
		byte[] data = new byte[]{1,2,3,4,5,6,7,8,9,10};
		FileOutputStream fos = new FileOutputStream(temp);
		try{
			fos.write(data);
		}finally{
			fos.close();
		}
		// Now chunk this file
		List<File> chunks = FileUtils.chunkFile(temp, 3);
		assertNotNull(chunks);
		assertEquals(4, chunks.size());
		assertFalse(chunks.contains(temp));
		toDelete.addAll(chunks);
		// Validate the expected data
		byte[][] allExpected = new byte[][]{ 
				new byte[]{1,2,3},
				new byte[]{4,5,6},
				new byte[]{7,8,9},
				new byte[]{10},
		};
		validateFileContents(chunks, allExpected);
	}
	
	@Test
	public void testExactSplit() throws IOException{
		File temp = File.createTempFile("FileUtilsTest", ".tmp");
		toDelete.add(temp);
		// fill it with 5 bytes
		byte[] data = new byte[]{1,2,3,4,5,6,7,8,9,10};
		FileOutputStream fos = new FileOutputStream(temp);
		try{
			fos.write(data);
		}finally{
			fos.close();
		}
		// Now chunk this file
		List<File> chunks = FileUtils.chunkFile(temp, 5);
		assertNotNull(chunks);
		assertEquals(2, chunks.size());
		assertFalse(chunks.contains(temp));
		toDelete.addAll(chunks);
		// Validate the expected data
		byte[][] allExpected = new byte[][]{ 
				new byte[]{1,2,3,4,5},
				new byte[]{6,7,8,9,10},
		};
		validateFileContents(chunks, allExpected);
	}
	
	@Test
	public void testSmallerChunkSizeEven() throws IOException{
		File temp = File.createTempFile("FileUtilsTest", ".tmp");
		toDelete.add(temp);
		// fill it with 5 bytes
		byte[] data = new byte[]{1,2,3,4,5,6,7,8,9,10,11};
		FileOutputStream fos = new FileOutputStream(temp);
		try{
			fos.write(data);
		}finally{
			fos.close();
		}
		// Now chunk this file
		List<File> chunks = FileUtils.chunkFile(temp, 4);
		assertNotNull(chunks);
		assertEquals(3, chunks.size());
		assertFalse(chunks.contains(temp));
		toDelete.addAll(chunks);
		// Validate the expected data
		byte[][] allExpected = new byte[][]{ 
				new byte[]{1,2,3,4},
				new byte[]{5,6,7,8},
				new byte[]{9,10,11},
		};
		validateFileContents(chunks, allExpected);
	}
	
	@Test
	public void testDeleteFiles() throws IOException{
		File temp = File.createTempFile("FileUtilsTest", ".tmp");
		toDelete.add(temp);
		// fill it with 5 bytes
		byte[] data = new byte[]{1,2,3,4,5,6,7,8,9,10,11};
		FileOutputStream fos = new FileOutputStream(temp);
		try{
			fos.write(data);
		}finally{
			fos.close();
		}
		// Now chunk this file
		List<File> chunks = FileUtils.chunkFile(temp, 4);
		assertNotNull(chunks);
		assertEquals(3, chunks.size());
		assertFalse(chunks.contains(temp));
		// Validate that all of the files currently exist
		for(File toTest: chunks){
			assertTrue(toTest.exists());
		}
		// Add the original file to the list
		chunks.add(temp);
		// Now delete all of the files except the exception
		FileUtils.deleteAllFilesExcludingException(temp, chunks);
		// The tmep fie should still exist since it was the exception
		assertTrue(temp.exists());
		// Remove the temp from the list
		chunks.remove(temp);
		// the remaining files should be delted
		for(File toTest: chunks){
			assertFalse(toTest.exists());
		}
	}

	/**
	 * Validate that the list of files contain the expeed contents
	 * @param chunks
	 * @param allExpected
	 * @throws IOException
	 */
	private void validateFileContents(List<File> chunks, byte[][] allExpected)
			throws IOException {
		// Validate the contents
		for(int i=0; i<allExpected.length; i++){
			byte[] expected  = allExpected[i];
			File chunk = chunks.get(i);
			assertEquals(expected.length, (int)chunk.length());
			// Read the data
			Path path = Paths.get(chunk.getAbsolutePath());
			byte[] fileData = Files.readAllBytes(path);
			assertTrue(Arrays.equals(expected, fileData));
		}
	}
	
	@Test
	public void testWriteStringToCompressed() throws IOException {
		String markdown = "This is a test **markdown** that will be compressed.";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Charset charset = Charset.forName("UTF-8");
		FileUtils.writeString(markdown, charset, /*gzip*/true, baos);
		byte[] bytes = baos.toByteArray();
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		String unzippedString = FileUtils.readStreamAsString(bais, charset, true);
		assertEquals(markdown, unzippedString);
	}
	
	@ParameterizedTest
	@CsvSource({
		"1,1 Bytes",
		"946,946 Bytes",
		"1024,1 KiB",
		"2048,2 KiB",
		"2560,2.5 KiB",
		"1048576,1 MiB",
		"1073741824,1 GiB",
		"107374182400,100 GiB",
		"107911053312,100.5 GiB",
		"1099511627776,1 TiB",
		"1125899906842624,1 PiB",
		"1152921504606846976,1 EiB"
	})
	public void testBytesToHumanReadable(Long size, String expected) {
		assertEquals(expected, FileUtils.bytesToHumanReadable(size));
	}
	
	@Test
	public void testBytesToHumanReadableWithNegativeSize() {
		assertEquals("Invalid file size: -1", assertThrows(IllegalArgumentException.class, () -> {			
			FileUtils.bytesToHumanReadable(-1);
		}).getMessage());
	}
}
