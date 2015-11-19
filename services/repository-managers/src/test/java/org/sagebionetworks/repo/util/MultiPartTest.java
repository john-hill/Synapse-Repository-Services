package org.sagebionetworks.repo.util;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class MultiPartTest {
	
	public final long FILE_SIZE = 5611111L;
	public final int PART_SIZE = 5*1024*1024;
	public final int NUMBER_OF_PARTS = (int) (FILE_SIZE/PART_SIZE+1);
	public final int LAST_PART_SIZE = (int) (FILE_SIZE%PART_SIZE);

	@Autowired
	AmazonS3Client s3Client;
	
	File finalFile;
	List<File> parts;
	List<String> partMD5s;
	String finalMD5;
	
	@Before
	public void before() throws Exception {
		// Create a random file
		Random rand = new Random(123L);
		parts = new ArrayList<File>(NUMBER_OF_PARTS);
		partMD5s = new ArrayList<String>(NUMBER_OF_PARTS);
		finalFile = File.createTempFile("UploadTest", ".bin");
		// Add all of the parts
		FileOutputStream finalOut = new FileOutputStream(finalFile);
		MessageDigest finalDigest = MessageDigest.getInstance("MD5");
		byte[] buffer = new byte[PART_SIZE];
		try{
			for(int i=0; i<NUMBER_OF_PARTS; i++){
				if(i+1 == NUMBER_OF_PARTS){
					buffer = new byte[LAST_PART_SIZE];
				}
				// fill the buffer with random bytes
				rand.nextBytes(buffer);
				File partFile = File.createTempFile("Part"+i, ".bin");
				parts.add(partFile);
				finalDigest.update(buffer);
				String partMD5 = new String(Hex.encodeHex(MessageDigest.getInstance("MD5").digest(buffer)));
				partMD5s.add(partMD5);
				FileUtils.writeByteArrayToFile(partFile, buffer);
				// also write the bytes to the final file
				finalOut.write(buffer);
			}
			finalMD5 = new String(Hex.encodeHex(finalDigest.digest()));
		}finally{
			if(finalOut != null){
				finalOut.close();
			}
		}
	}
	
	@After
	public void after(){
		if(finalFile != null){
			finalFile.delete();
		}
		// delete all of the parts
		if(parts != null){
			for(File part: parts){
				part.delete();
			}
		}
	}
	
	@Test
	public void test(){
		System.out.println("finalFile: "+finalFile.getAbsolutePath());
		System.out.println("finalFile.size "+finalFile.length());
		System.out.println("finalFile.md5 "+finalMD5);
		System.out.println("parts.size "+parts.size());
		
		String bucket = "devhill.access.record.sagebase.org";
		String key = "000000007/"+UUID.randomUUID().toString();
		
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentMD5(finalMD5);
		metadata.setContentLength(FILE_SIZE);
		metadata.setContentType("application/octet-stream");
		
		// Start the multi-part upload
		InitiateMultipartUploadResult imur = s3Client.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, key, metadata));
		// Upload each file
		for(int i=0; i<parts.size(); i++){
			File partFile = parts.get(i);
			String partMD5 = partMD5s.get(i);
			String partKey = key+"/"+i;
			
			ObjectMetadata partMetadata = new ObjectMetadata();
			partMetadata.setContentMD5(partMD5);
			partMetadata.setContentLength(partFile.length());
			partMetadata.setContentType("application/octet-stream");
						
			
		}
		
	}
}
