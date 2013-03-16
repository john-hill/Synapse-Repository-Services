package org.sagebionetworks.file;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.StackConfiguration;
import org.sagebionetworks.utils.DefaultHttpClientSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.xml.sax.SAXException;

import com.amazonaws.HttpMethod;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.MultipartUploadListing;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.amazonaws.util.BinaryUtils;
import com.google.gwt.editor.client.Editor.Ignore;

/**
 * Prototyping various upload options.
 * @author John
 *
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:aws-spb.xml" })
public class UploadPrototype {
	
	/**
	 * Note: 5 MB is currently the minimum size of a single part of S3 Multi-part upload.
	 */
	public static final int MINIMUM_BLOCK_SIZE_BYTES = ((int) Math.pow(2, 20))*5;
	
	@Autowired
	AWSCredentials awsCredentials;
	
	AmazonS3Client s3Client;
	
	@Before
	public void before(){
		s3Client = new AmazonS3Client(awsCredentials);
	}
	
	@Ignore
	@Test
	public void testPreSingedUrls() throws ParseException, IOException, SAXException{
		String key = "0/"+UUID.randomUUID().toString();
		// First create an empty object
		GeneratePresignedUrlRequest gpur = new GeneratePresignedUrlRequest(StackConfiguration.getS3Bucket(), key).withMethod(HttpMethod.PUT).withContentType("text/plain; charset=ISO-8859-1");
		URL url = s3Client.generatePresignedUrl(gpur);
		System.out.println(url.toString());
		
		// Now upload to it
		HttpPut httppost = new HttpPut(url.toString());
		httppost.setEntity(new StringEntity("This is the body"));
		HttpResponse response = DefaultHttpClientSingleton.getInstance().execute(httppost);
		String responseBody = (null != response.getEntity()) ? EntityUtils.toString(response.getEntity()) : null;
		System.out.println(responseBody);
	}
	
	@Ignore
	@Test
	public void testPresignedMultipart() throws ClientProtocolException, IOException{
		String bucket = StackConfiguration.getS3Bucket();
		String key = "0/"+UUID.randomUUID().toString()+"/multipart.text";
		InitiateMultipartUploadResult imur = s3Client.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, key));
		// Generate a presigned url for the part
		GeneratePresignedUrlRequest gpur = new GeneratePresignedUrlRequest(bucket, key).withMethod(HttpMethod.PUT).withContentType("text/plain; charset=ISO-8859-1");
		gpur.addRequestParameter("uploadId", imur.getUploadId());
		gpur.addRequestParameter("partNumber", "1");
		URL url = s3Client.generatePresignedUrl(gpur);
		System.out.println(url.toString());
		
		// Upload to the part.
		HttpPut httppost = new HttpPut(url.toString());
		httppost.setEntity(new StringEntity("This is the body"));
		HttpResponse response = DefaultHttpClientSingleton.getInstance().execute(httppost);
		System.out.println(response);
		String responseBody = (null != response.getEntity()) ? EntityUtils.toString(response.getEntity()) : null;
		Header[] etag = response.getHeaders("ETag");
		List<PartETag> etagList = new LinkedList<PartETag>();
		PartETag part = new PartETag(1, etag[0].getValue());
		etagList.add(part);
		System.out.println(responseBody);
		
		MultipartUploadListing listing = s3Client.listMultipartUploads(new ListMultipartUploadsRequest(bucket));

		// Complete the parts.
		CompleteMultipartUploadResult result = s3Client.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, key, imur.getUploadId(), etagList));
		System.out.println(result);
	}
	
	@Test
	public void testLargeFile() throws IOException{
		String bucket = StackConfiguration.getS3Bucket();
		String key = "0/"+UUID.randomUUID().toString()+"/multipart.text";
		System.out.println("Key: "+key);
		// Read in large File
		List<String> partKeys = new LinkedList<String>();
		File input = new File("C:/Users/Public/Pictures/Sample Pictures/PenguinsBig.jpg");
		FileInputStream in = new  FileInputStream(input);
		try{
			// Now read in 5 MB blocks
			byte[] buffer = new byte[MINIMUM_BLOCK_SIZE_BYTES];
			// Now fill the block
			int read = -1;
			int partNumber = 1;
			long contentSize = 0;
			// First we need to fill up the memory buffer.
			while ((read = fillBufferFromStream(buffer, in)) > 0) {
				ByteArrayEntity entity = new ByteArrayEntity(buffer, read);
				String partKey = key+"/"+partNumber;
				// For each block we want to create a pre-signed URL file.
				GeneratePresignedUrlRequest gpur = new GeneratePresignedUrlRequest(bucket, partKey).withMethod(HttpMethod.PUT);
				URL url = s3Client.generatePresignedUrl(gpur);
				
				// Use the URL to upload a part.
				HttpPut httppost = new HttpPut(url.toString());
				httppost.setEntity(entity);
				HttpResponse response = DefaultHttpClientSingleton.getInstance().execute(httppost);
				System.out.println(response);
				// Add this partkey
				partKeys.add(partKey);
				// Increment the part number
				partNumber++;
				contentSize += read;
			}
		}finally{
			in.close();
		}
		long start = System.currentTimeMillis();
		// Now use the part keys to create the final object
		InitiateMultipartUploadResult imur = s3Client.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, key));
		List<PartETag> etagList = new LinkedList<PartETag>();
		// Add each partkey
		for(int i=0; i<partKeys.size(); i++){
			String partKey = partKeys.get(i);
			CopyPartRequest cpr = new CopyPartRequest();
			cpr.setDestinationBucketName(bucket);
			cpr.setDestinationKey(key);
			cpr.setPartNumber(i+1);
			cpr.setSourceBucketName(bucket);
			cpr.setSourceKey(partKey);
			cpr.setUploadId(imur.getUploadId());
			// copy the part
			CopyPartResult result = s3Client.copyPart(cpr);
			PartETag pt = new PartETag(result.getPartNumber(), result.getETag());
			etagList.add(pt);
		}
		// Now finish the muli-part upload
		CompleteMultipartUploadResult result = s3Client.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, key, imur.getUploadId(), etagList));
		long elapse = System.currentTimeMillis()-start;
		System.out.println("Created multi-part in "+elapse+" ms. Key: "+result.getKey());
	}
	
	/**
	 * Helper to write bytes to S3.
	 * @author jmhill
	 *
	 */
	private static class ByteArrayEntity extends AbstractHttpEntity{
		byte[] content;
		long length;
		public ByteArrayEntity(byte[] content, long length){
			this.content = content;
			this.length = length;
		}
		@Override
		public boolean isRepeatable() {
			return true;
		}

		@Override
		public long getContentLength() {
			return length;
		}

		@Override
		public InputStream getContent() throws IOException,
				IllegalStateException {
	        return new ByteArrayInputStream(this.content, 0, (int)length);
		}

		@Override
		public void writeTo(OutputStream outstream) throws IOException {
			outstream.write(content, 0, (int)length);
	        outstream.flush();
		}

		@Override
		public boolean isStreaming() {
			return false;
		}
		
	}
	
	/**
	 * Fill the passed buffer from the passed input stream.
	 * @param buffer
	 * @param in
	 * @return the number of bytes written to the buffer.
	 * @throws IOException 
	 */
	public static int fillBufferFromStream(byte[] buffer, InputStream in) throws IOException{
		int totalRead = 0;
		int read;
		while((read = in.read(buffer, totalRead, buffer.length-totalRead)) > 0){
			totalRead += read;
		}
		return totalRead;
	}
}
