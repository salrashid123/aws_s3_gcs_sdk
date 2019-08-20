# Accessing Google Cloud Storage using AWS SDK and OAuth2

With [Google Cloud Storage](https://cloud.google.com/storage/) you have the option to access the service via two distinct APIs: [XML](https://cloud.google.com/storage/docs/xml-api/overview) or [JSON](https://cloud.google.com/storage/docs/json_api/) APIs.  Why two? Well, the XML endpoint supports the [boto framework](http://boto.cloudhackers.com/en/latest/) and offers compatibility with AWS S3 while the JSON Endpoint is compliant with the rest of Google Cloud API infrastructure and delivers some unique advantages (see [Performance And Cost Differences Between Apis](https://cloud.google.com/storage/docs/gsutil/addlhelp/CloudStorageAPIs)). Almost all GCS client libraries uses the JSON API while [gsutil](https://cloud.google.com/storage/docs/gsutil) can employ both. For most users, its recommended to use the JSON API that comes with our client library anyway.

Recall I said compatibility with S3..does that mean we can use AWS's S3 client library to interact with GCS? Well, yes for simple usecases but as with most things, there are some caveats. In this article, we will cover the overrides a colleague of mine and I employed to AWS's stock java and golang clients to allow it to interact with GCS using oAuth2 `access_tokens`. We did not modify the client core classes in anyway and simply used the authentication overrides already surfaced by the client.

> NOTE: the technique is alpha quality; I only tested the basic operations and documented some of issues I know about.


This article is co-authored by my collegue who contributed the golang version in a day! Thanks [@yfuruyama](https://medium.com/@yfuruyama).

### Using AWS Client SDK with GCS HMAC Credentials

Google already documented simple techniques to use AWS's client libraries against GCS as part of the simple migration story here:

- [Cloud Storage interoperability](https://cloud.google.com/storage/docs/interoperability)
- [Migrating from Amazon S3 to Cloud Storage](https://cloud.google.com/storage/docs/migrating)

The snippets in the second link details basic usage of S3 client libraries while using HMAC credentials common to both Cloud Providers (well, hmac was added to GCP for the S3 compatibility..). Whats the "problem" with HMAC? Well, its just static, long-term username and password based credentials (long term until you manually revoke to set). Sure it will work...you just have to provision the credentials on GCP either as a [user](https://cloud.google.com/storage/docs/migrating#keys) or [service account](https://cloud.google.com/storage/docs/authentication/managing-hmackeys).As mentioned, in either case, you need to manage the key and secret manually, e.g. inlining into code:


```java
String googleAccessKeyId = "GOOGTS7C7FUP3AIRVJTE2BCD";
Sring googleAccessKeySecret = "bGoa+V7g/yqDXvKRqq+JTFn4uQZbPiQJo4pf9RzJ";

BasicAWSCredentials googleCreds = new BasicAWSCredentials(googleAccessKeyId,
    googleAccessKeySecret);

AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
        .withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(
                "https://storage.googleapis.com", "auto"))
        .withCredentials(new AWSStaticCredentialsProvider(googleCreds))
        .build();
String bucketName = "your-bucket-name";
System.out.println(s3Client.getObjectAsString(bucketName, "file.txt"));
```

Whats wrong with HMAC keys?...well, its username/password, long-lived secrets! Oauth2 tokens are short-lived and can be derived in any number of forms for GCP based on the environment.


If you invoke the above snippet an turn up wire logging, you'll see the headers sent while fetching `file.txt` from bucket:

```
GET /file.txt HTTP/1.1
Host: your-bucket-name.storage.googleapis.com
x-amz-content-sha256: UNSIGNED-PAYLOAD
Authorization: AWS4-HMAC-SHA256 Credential=GOOGTS7C7FUP3AIRVJTE2BCD/20190817/auto/s3/aws4_request, SignedHeaders=amz-sdk-invocation-id;amz-sdk-retry;content-type;host;user-agent;x-amz-content-sha256;x-amz-date, Signature=8c837e8b54cfbac008559525b0d1cb18060787287029ad12450d92b42322320[\r][\n]"
X-Amz-Date: 20190817T061205Z
amz-sdk-invocation-id: 7aad332c-8d4d-1c9a-a966-2f66a8eb818d
amz-sdk-retry: 0/0/500[
Content-Type: application/octet-stream
Content-Length: 0
Connection: Keep-Alive
```

Two things to note:

- `Host:` header now is in the form `bucketname.storage.googleapis.com`. This allows you to define firewall or proxy rules that only allows traffic to named hosts and prevents others. For example, if your company setup a network perimeter using something like a [squid proxy](https://github.com/salrashid123/squid_proxy#forward), then users within that perimeter could be blocked from accessing arbitrary GCP buckets. (you can also use [VPC Service Controls](https://cloud.google.com/vpc-service-controls/docs/overview) but thats not relevant here). If you use the JSON API library set with GCS, all requests are emitted with the host `www.googleapis.com` with the bucket name within the path...this means without SSL interception in place a user can upload data to any bucket he/she has credentials for.

- `Authorization:` header provided is not just an oauth2 Bearer token as is the case with normal GCP API calls but a  [signed request](https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html) utilized by Amazon using the provided _HMAC_ key (note in our case the HMAC key is embedded in the Credential= parameter).

- `amz-*` specific headers are also included in the request. These are automatically added on by the client library. In many cases, these headers are just dropped by Google which means some information is lost. What client library should do is transform any client-library provided headers into Google formats (which could be a simple name translation as described in [Migrating from Amazon S3 to Cloud Storage Headers](https://cloud.google.com/storage/docs/migrating#custommeta)).


### Using AWS Client SDK with GCP Oauth2 Credentials

Up until now we've described how to use HMAC and AWS client libraries...but google uses oauth2 bearer tokens for authentication (well, for the most part, there are also [JWTAccessTokens](https://medium.com/google-cloud/faster-serviceaccount-authentication-for-google-cloud-platform-apis-f1355abc14b2) and [OIDC tokens](https://medium.com/google-cloud/authenticating-using-google-openid-connect-tokens-e7675051213b)).

Lets see if we can account for the differences in the API request above with overrides for Google using AWS's [S3Client](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html) here.


- `Host` Header

Theres nothing to do here, the client does that for you already with 
```java
new AwsClientBuilder.EndpointConfiguration("https://storage.googleapis.com", "auto")
```

settings. Note we didn't have to specify the bucket name in the URL- that gets added in directly as part of the request by the library. We've also used "auto" in the region since we don't have to specify anything like the region while dealing with GCS.

- `Authorization` Header

We need someway to intercept and override the `Authorization` header sent and instead of `HMAC` based key, acquire and substitute an `oauth2 bearer` token using whatever GCP provided credentials are in context. Fortunately, AWS's client library (in java and golang atleast), allows for such an override. In our case, we implement a custom `AWS4Signer` which just injects another override `AWSSessionCredentials`. The Credential override reads in a `GoogleCredentials` object (whichever you provide), coaxes out its `access_token` (and performs automatic refresh), then pretends that token is AWS's `AWSAccessKeyId` (i could've embedded it as `AWSSecretKey` or `AWSSessionToken` ...any of those are available when i really need to do the substitution in `CustomGCPSigner`).


```java
package com.test;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.amazonaws.SignableRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.AWSCredentials;

public class CustomGCPSigner extends AWS4Signer {
    @Override
    public void sign(SignableRequest<?> request, AWSCredentials credentials) {
        request.addHeader("Authorization", "Bearer " + credentials.getAWSAccessKeyId());
    }
}
```

```java
package com.test;

import java.io.IOException;

import com.amazonaws.auth.AWSSessionCredentials;
import com.google.auth.oauth2.GoogleCredentials;

public class GCPSessionCredentials implements AWSSessionCredentials {

    private final GoogleCredentials credentials;

    public GCPSessionCredentials(GoogleCredentials credentials) {
        this.credentials = credentials;
    }
    private String getGCPToken() {
        try {
            this.credentials.refreshIfExpired();
        } catch (IOException ioex) {
            return "";
        }
        return credentials.getAccessToken().getTokenValue();
    }
    public String getAWSAccessKeyId() {
        return getGCPToken();
    }

    public String getAWSSecretKey() {
        return "";
    }

    public String getSessionToken() {
        return "";
    }
}
```

At this point we have the GCP `access_token` pretending to be the `AWSAccessKeyId`. Our override of the Signer gets called and automatically has access to this credential and the request object it will send out. What we're doing now is reading in the `AWSAccessKeyId` and adding it into the `Authorization: Bearer <AWSAccessKeyId>` header value so when the request is sent, we send over our GCP token.

Ok, this is a hack since we're pretending `AWSAccessKeyId` is the something else but we are not altering AWS's code in anyway really...just overriding the standard library to use our implementations. How do you use this in a client?

First bootstrap GCP credentials and custom signer, then tell AWS to use that signer in the client configuration

```java
String credPath = "/path/to/svc_account.json";
ServiceAccountCredentials sourceCredentials = ServiceAccountCredentials
	.fromStream(new FileInputStream(credPath));
sourceCredentials = (ServiceAccountCredentials) sourceCredentials
	.createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));

String projectId = sourceCredentials.getProjectId();
GCPSessionCredentials creds = new GCPSessionCredentials((GoogleCredentials) sourceCredentials);

SignerFactory.registerSigner("com.test.CustomGCPSigner", com.test.CustomGCPSigner.class);
ClientConfiguration clientConfig = new ClientConfiguration();
clientConfig.setSignerOverride("com.test.CustomGCPSigner");

AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
    .withClientConfiguration(clientConfig)
	.withEndpointConfiguration(
		new AwsClientBuilder.EndpointConfiguration("https://storage.googleapis.com", "auto"))
					.withRequestHandlers(new RequestHandler2() {
						@Override
						public void beforeRequest(Request<?> request) {
							request.addHeader("x-goog-project-id", projectId);																		
						}				
					})
	.withCredentials(new AWSStaticCredentialsProvider(creds)).build();
```

Needless to say, you can inject any `Credential` type you choose:

* [ComputeEngineCredentials](https://googleapis.dev/java/google-auth-library/latest/com/google/auth/oauth2/ComputeEngineCredentials.html)
* [ServiceAccountCredentilas](https://googleapis.dev/java/google-auth-library/latest/com/google/auth/oauth2/ServiceAccountCredentials.html)
* [ImpersonatedCredentials](https://googleapis.dev/java/google-auth-library/latest/com/google/auth/oauth2/ImpersonatedCredentials.html)). 
Or acquire an `access_token` by any other means and apply that one time token to [OAuth2Credentials.create()]( 
https://googleapis.dev/java/google-auth-library/latest/com/google/auth/oauth2/OAuth2Credentials.html#create-com.google.auth.oauth2.AccessToken-)

- `amz-*` Values

This is tricky...we need to transform each header we see into the google formatted one. In `CustomGCPSigner.java`, this transform was done while overriding the `Signer` for authentication. First we created a map and then checked each inbound header from the AWS client library and ran a substitution. We also added in any custom metadata the client would be sending in as part of the object (eg `x-amz-meta-` -> `x-goog-meta-`)

Note, it seems atleast in java, I was _not_ able to remove the header keys provided (the AWS `SignableRequest` did not allow that)...so i just "added in" the google headers.

- `x-goog-project-id` Header

We also need to add on a special header while interacting with GCS for certain operations.  As the name suggests, [x-goog-project-id](https://cloud.google.com/storage/docs/xml-api/reference-headers#xgoogprojectid) denotes the projectID in context for the request.  Not all GCS operations use this header but it is need for the `ListBuckets` type operations

```java
.withRequestHandlers(new RequestHandler2() {
	@Override
	public void beforeRequest(Request<?> request) {
		request.addHeader("x-goog-project-id", projectId);																		
	}				
})
```

### Sample Upload

Now we've got the overrides in place, lets upload a file and set some standard custom metadata and headers

```java
s3Client.putObject(bucketName, "newfile.txt", "Uploaded String Object");
			 
PutObjectRequest request = new PutObjectRequest(bucketName, "file.txt", new
    File("file.txt")); ObjectMetadata metadata = new ObjectMetadata();
metadata.setContentType("text/plain");
metadata.addUserMetadata("customdata", "helloworld");
request.setStorageClass(StorageClass.Standard);
request.setMetadata(metadata); s3Client.putObject(request);			
```

You'll see in the wire trace the `Authorization` header, the bucket name in the host, the _duplicated_ `x-*` set of headers as well as the 'custom` metadata values

```
PUT /file.txt HTTP/1.1
Host: mineral-minutia-820.storage.googleapis.com
Authorization: Bearer ya29.c.ElxnB4XlDaJS5qCI2GZpmxpghVDcl8IuaQo-redacted
x-goog-project-id: mineral-minutia-820
x-amz-meta-customdata: helloworld
x-goog-meta-customdata: helloworld
User-Agent: aws-sdk-java/1.11.596 Linux/4.19.37-5+deb10u1rodete1-amd64 OpenJDK_64-Bit_Server_VM/11.0.3+1-Debian-1 java/11.0.3 vendor/Oracle_Corporation
amz-sdk-invocation-id: b35cf170-7573-f13e-a0dc-e7d5652852e6
amz-sdk-retry: 0/0/500
x-amz-storage-class: STANDARD
x-goog-storage-class: STANDARD
Content-MD5: +aVKy4H2jdwLpMXY+nS+qQ==
Content-Type: text/plain
Content-Length: 11
Connection: Keep-Alive
Expect: 100-continue
```

and to confirm:

```
$ gsutil stat gs://mineral-minutia-820/file.txt
gs://mineral-minutia-820/file.txt:
    Creation time:          Sat, 17 Aug 2019 15:54:43 GMT
    Update time:            Sat, 17 Aug 2019 15:54:43 GMT
    Storage class:          STANDARD
    Content-Length:         11
    Content-Type:           text/plain
    Metadata:               
        customdata:         helloworld
    Hash (crc32c):          AB7xdw==
    Hash (md5):             +aVKy4H2jdwLpMXY+nS+qQ==
    ETag:                   CLbz64CiiuQCEAE=
    Generation:             1566057283910070
    Metageneration:         1
```

### [The devil is in the detail](https://en.wikipedia.org/wiki/The_devil_is_in_the_detail)

There are several advanced aspects we did not cover but which may or maynot work:

- [Requestor Pays](https://cloud.google.com/storage/docs/requester-pays)

This mode is available in both AWS and GCP which allows the authenticated _client_ to incur the costs of the GCS operation.  I did not verify this but I suspect this should 'just work' since the credential is already bootstrapped.

- [Resumable Uploads](https://cloud.google.com/storage/docs/xml-api/resumable-upload)

I'm not familiar with S3's capabilities in this area and didn't test this.  GCS on the other hand allows for resumable uploads...you know, the usecase as described in [here](https://github.com/googleapis/gcs-resumable-upload): _"If somewhere during the operation, you lose your connection to the internet or your tough-guy brother slammed your laptop shut when he saw what you were uploading, the next time you try to upload to that file, it will resume automatically from where you left off."_.  

If AWS's library does not support this mode, you will have to use `gsutil` or GCS client libraries directly (which [handles this automatically](Bucket.html#create-java.lang.String-byte:A-com.google.cloud.storage.Bucket.BlobTargetOption...-) in JSON).  (if you want to use signed+url and resumable upload and really want to see the details, see the link in the references section).

- [Signed URL](https://cloud.google.com/storage/docs/access-control/signed-urls)

The technique outlined here is _not_ SignedURL but regular authentication.  You can sign normally using a service account credential file without using these overrides or for that matter, AWS client libraries

### Complete example

The complete source code described in this article can be found in in the following it Repos

* [java](https://github.com/salrashid123/aws_s3_gcs_sdk). 
* [golang in https://medium.com/@yfuruyama]'s repo [here](https://github.com/yfuruyama/gcs-aws-sdk-oauth2).


Note, we've only covered java and golang.  We made an attempt at overriding the python boto library set but I didn't understand boto overrides for the `RequestSigner` to make much progress.  [This is as far](https://gist.github.com/salrashid123/fec7339e245e118654948da3abb8b685) as I got in python.  If you find time or interested in contribuing, all commits are welcome!


### Conclusion

This article is primarily a way to show how to use oauth2 tokens with AWS's client library. It is _not_ officially supported and should be used with some caution...Its intended for existing AWS S3 users with simple usecases who may not need to retool their application just to write to GCS (i.,e you dont' _have to_ learn a new GCS library just yet).  For those users, the authentication override described here is superior to plain HMAC while still giving you time to migrate over to GCS's JSON API and library set.  Long term, I'd recommend moving to using the officially supported set.


### References

- [Google Cloud Storage SignedURL + Resumable upload with cURL](https://medium.com/google-cloud/google-cloud-storage-signedurl-resumable-upload-with-curl-74f99e41f0a2)
* [Performance And Cost Differences Between Apis](https://cloud.google.com/storage/docs/gsutil/addlhelp/CloudStorageAPIs#performance-and-cost-differences-between-apis) 
* [Migrating from Amazon S3 to Cloud Storage Headers](https://cloud.google.com/storage/docs/migrating#custommeta)
* [AWS S3Client](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html)
* [GCS BlobTargetOptions](https://googleapis.dev/java/google-cloud-clients/latest/com/google/cloud/storage/Bucket.html#create-java.lang.String-byte:A-com.google.cloud.storage.Bucket.BlobTargetOption...-)
* [GCS HMAC SignedURL](https://medium.com/google-cloud/gcs-hmac-signedurl-3166b995f237)
* [Faster ServiceAccount authentication for Google Cloud Platform APIs](https://medium.com/google-cloud/faster-serviceaccount-authentication-for-google-cloud-platform-apis-f1355abc14b2)