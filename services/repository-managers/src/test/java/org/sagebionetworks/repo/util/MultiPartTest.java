package org.sagebionetworks.repo.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.util.Base64;
import com.amazonaws.util.BinaryUtils;
import com.sun.star.lang.IllegalArgumentException;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class MultiPartTest {

	public final int PART_SIZE = 5 * 1024 * 1024;
	public final long FILE_SIZE = PART_SIZE*2+9989;
	public final int LAST_PART_SIZE = (int) (FILE_SIZE % PART_SIZE);
	public final int NUMBER_OF_PARTS = (int) ((FILE_SIZE / PART_SIZE) + (LAST_PART_SIZE > 0 ? 1 : 0));

	@Autowired
	AmazonS3Client s3Client;

	File finalFile;
	List<File> parts;
	List<String> base64PartMD5s;
	String finalMD5;
	String bucket;
	String key;

	@Before
	public void before() throws Exception {
		// Create a random file
		Random rand = new Random(123L);
		parts = new ArrayList<File>(NUMBER_OF_PARTS);
		base64PartMD5s = new ArrayList<String>(NUMBER_OF_PARTS);
		finalFile = File.createTempFile("UploadTest", ".bin");
		// Add all of the parts
		FileOutputStream finalOut = new FileOutputStream(finalFile);
		MessageDigest finalDigest = MessageDigest.getInstance("MD5");
		byte[] buffer = new byte[PART_SIZE];
		try {
			for (int i = 0; i < NUMBER_OF_PARTS; i++) {
				if (i + 1 == NUMBER_OF_PARTS) {
					buffer = new byte[LAST_PART_SIZE];
				}
				// fill the buffer with random bytes
				rand.nextBytes(buffer);
				File partFile = File.createTempFile("Part" + i, ".bin");
				parts.add(partFile);
				finalDigest.update(buffer);
				String partMD5 = new String(Base64.encodeAsString(MessageDigest
						.getInstance("MD5").digest(buffer)));
				base64PartMD5s.add(partMD5);
				FileUtils.writeByteArrayToFile(partFile, buffer);
				// also write the bytes to the final file
				finalOut.write(buffer);
			}
			finalMD5 = new String(Base64.encodeAsString(finalDigest.digest()));
		} finally {
			if (finalOut != null) {
				finalOut.close();
			}
		}

		bucket = "devhill.access.record.sagebase.org";
		key = "000000007/" + UUID.randomUUID().toString();
	}

	@After
	public void after() {
		if (finalFile != null) {
			finalFile.delete();
		}
		// delete all of the parts
		if (parts != null) {
			for (File part : parts) {
				part.delete();
			}
		}
		if (key != null) {
			try {
				s3Client.deleteObject(bucket, key);
			} catch (Exception e) {
				System.out.println("Delete failed: " + e.getMessage());
			}
		}
	}

	@Test
	public void testMultiPartUpload() throws Exception {
		System.out.println("finalFile: " + finalFile.getAbsolutePath());
		System.out.println("finalFile.size " + finalFile.length());
		System.out.println("finalFile.md5 " + finalMD5);
		System.out.println("parts.size " + parts.size());

		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentMD5(finalMD5);
		metadata.setContentLength(FILE_SIZE);
		metadata.setContentType("application/octet-stream");
		List<PartETag> partEtags = new LinkedList<PartETag>();
		long startAll = System.currentTimeMillis();

		// Start the multi-part upload
		long startMs = System.currentTimeMillis();
		InitiateMultipartUploadResult imur = s3Client
				.initiateMultipartUpload(new InitiateMultipartUploadRequest(
						bucket, key, metadata));
		System.out.println(String.format("initiateMultipartUpload %s ms",
				(System.currentTimeMillis() - startMs)));
		// Upload each file
		long sumPutObjectMS = 0;
		long sumAddPart = 0;
		for (int i = 0; i < parts.size(); i++) {
			System.out.println("Starting part: "+i);
			File partFile = parts.get(i);
			String partMD5 = base64PartMD5s.get(i);
			String partKey = key + "/" + i;

			ObjectMetadata partMetadata = new ObjectMetadata();
			partMetadata.setContentMD5(partMD5);
			partMetadata.setContentLength(partFile.length());
			partMetadata.setContentType("application/octet-stream");

			FileInputStream fis = new FileInputStream(partFile);
			try {
				startMs = System.currentTimeMillis();
				System.out.println("starting put object: "+partKey);
				s3Client.putObject(bucket, partKey, fis, partMetadata);
				sumPutObjectMS += (System.currentTimeMillis() - startMs);
				// Add the part to the multi-part.
				startMs = System.currentTimeMillis();
				PartETag partEtag = attemptAddPart(bucket, imur, i + 1, partKey, partMD5);
				sumAddPart += (System.currentTimeMillis() - startMs);
				partEtags.add(partEtag);
			} finally {
				if (fis != null) {
					fis.close();
				}
			}
		}
		System.out.println("finalFile.size " + finalFile.length());
		System.out.println(String.format("average putObject() %d ms",
				(sumPutObjectMS / partEtags.size())));
		System.out.println(String.format("average addPart() %d ms",
				(sumAddPart / partEtags.size())));
		// Complete the upload
		startMs = System.currentTimeMillis();
		CompleteMultipartUploadResult cmp = s3Client
				.completeMultipartUpload(new CompleteMultipartUploadRequest(
						bucket, key, imur.getUploadId(), partEtags));
		System.out.println(String.format("completeMultipartUpload %s ms",
				(System.currentTimeMillis() - startMs)));
		System.out.println("File uploaded to: " + key + " with etag: "
				+ cmp.getETag());
		System.out.println(String.format("Total upload time %d sec",
				((System.currentTimeMillis() - startAll)/1000)));
		String calucatedEtag= calcualteMultipartEtag(base64PartMD5s);
		System.out.println("Caluclated etag: "+calucatedEtag);
		System.out.println("Actaual etag:    "+cmp.getETag());
	}
	
	/**
	 * The multi-part etag is the MD5 of the concatenated list of part MD5s dash number of parts.;
	 * @param base64PartMD5s
	 * @return
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public static String calcualteMultipartEtag(List<String> base64PartMD5s) throws IOException, NoSuchAlgorithmException{
		MessageDigest digest = MessageDigest.getInstance("MD5");
		for(String base64PartMD5: base64PartMD5s){
			digest.update(BinaryUtils.fromBase64(base64PartMD5));
		}
		return BinaryUtils.toHex(digest.digest())+"-"+base64PartMD5s.size();
	}

	/**
	 * Attempt to add a part until it is added.
	 * 
	 * @param bucket
	 * @param imur
	 * @param partNumber
	 * @param partKey
	 * @return
	 * @throws InterruptedException
	 */
	private PartETag attemptAddPart(String bucket,
			InitiateMultipartUploadResult imur, int partNumber, String partKey, String partMD5)
			throws InterruptedException {
		while (true) {
			CopyPartResult result;
			try {
				// Etags are hex encoded MD5s.
				String partEtagIn = BinaryUtils.toHex(BinaryUtils.fromBase64(partMD5));
				result = s3Client.copyPart(new CopyPartRequest()
						.withSourceBucketName(bucket)
						.withSourceKey(partKey)
						.withPartNumber(partNumber)
						.withUploadId(imur.getUploadId())
						.withDestinationBucketName(bucket)
						.withDestinationKey(key)
						.withMatchingETagConstraint(partEtagIn));
				if(result == null){
					throw new IllegalArgumentException("Failed to add a part.  The MD5 of "+partKey+" did not match the passed MD5");
				}
				PartETag partEtag = result.getPartETag();
				System.out.println("In  etag: "+partEtagIn);
				System.out.println("out etag: "+partEtag.getETag());
				// delete the original part object
				s3Client.deleteObject(bucket, partKey);
				return partEtag;
			} catch (Exception e) {
				System.out.println("Copy part failed: " + e.getMessage());
				Thread.sleep(2000);
			}
		}
	}
}
