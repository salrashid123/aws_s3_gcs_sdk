package com.test;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Request;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.SignerFactory;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.StorageClass;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

public class TestApp {
	public static void main(String[] args) {
		TestApp tc = new TestApp();
	}

	private static final String bucketName = "your_bucket";
	private static final String credPath = "/path/to/svc_account.json";

	public TestApp() {
		try {

			ConsoleHandler consoleHandler = new ConsoleHandler();
			consoleHandler.setLevel(Level.INFO);

			Logger wl = Logger.getLogger("org.apache.http.wire");
			wl.setLevel(Level.OFF);
			wl.addHandler(consoleHandler);

			Logger sl = Logger.getLogger("com.amazonaws.services");
			sl.setLevel(Level.OFF);
			sl.addHandler(consoleHandler);

			Logger gl = Logger.getLogger("com.amazonaws.auth");
			gl.setLevel(Level.OFF);
			gl.addHandler(consoleHandler);

			Logger al = Logger.getLogger("com.amazonaws.request");
			al.setLevel(Level.OFF);
			al.addHandler(consoleHandler);

			Logger rl = Logger.getLogger("com.amazonaws");
			rl.setLevel(Level.OFF);
			rl.addHandler(consoleHandler);

			ServiceAccountCredentials sourceCredentials = ServiceAccountCredentials
					.fromStream(new FileInputStream(credPath));
			sourceCredentials = (ServiceAccountCredentials) sourceCredentials
					.createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

			String projectId = sourceCredentials.getProjectId();
			GCPSessionCredentials creds = new GCPSessionCredentials((GoogleCredentials) sourceCredentials);

			SignerFactory.registerSigner("com.test.CustomGCPSigner", com.test.CustomGCPSigner.class);
			ClientConfiguration clientConfig = new ClientConfiguration();
			clientConfig.setSignerOverride("com.test.CustomGCPSigner");

			AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withClientConfiguration(clientConfig)
					.withEndpointConfiguration(
							new AwsClientBuilder.EndpointConfiguration("https://storage.googleapis.com", "auto"))
					.withRequestHandlers(new RequestHandler2() {
						@Override
						public void beforeRequest(Request<?> request) {
							request.addHeader("x-goog-project-id", projectId);
						}
					}).withCredentials(new AWSStaticCredentialsProvider(creds)).build();

			List<Bucket> bkts = s3Client.listBuckets();
			for (Bucket btk : bkts)
				System.out.println(btk.getName());

			PutObjectResult putResult = s3Client.putObject(bucketName, "file.txt", "Lorem ipsum");
			System.out.println("Uploaded object getContentMd5: " + putResult.getContentMd5());
			System.out.println("Downlaoded object content: " + s3Client.getObjectAsString(bucketName, "file.txt"));

			/*
			ObjectListing objects = s3Client.listObjects(bucketName);
			System.out.println("Objects:");
			for (S3ObjectSummary object : objects.getObjectSummaries())
				System.out.println(object.toString());


			PutObjectRequest request = new PutObjectRequest(bucketName, "file.txt", new File("file.txt"));
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setContentType("text/plain");
			metadata.addUserMetadata("customdata", "helloworld");
			request.setStorageClass(StorageClass.Standard);
			request.setMetadata(metadata);
			s3Client.putObject(request);
			*/

			s3Client.shutdown();

		} catch (Exception ex) {
			System.out.println("Error:  " + ex);
		}
	}
}
