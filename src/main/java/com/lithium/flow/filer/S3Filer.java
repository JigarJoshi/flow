/*
 * Copyright 2015 Lithium Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lithium.flow.filer;

import static com.google.common.base.Preconditions.checkNotNull;

import com.lithium.flow.access.Access;
import com.lithium.flow.access.Prompt;
import com.lithium.flow.config.Config;
import com.lithium.flow.io.DataIo;
import com.lithium.flow.util.Lazy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.google.common.collect.Lists;

/**
 * @author Matt Ayres
 */
public class S3Filer implements Filer {
	private final AmazonS3 s3;
	private final URI uri;
	private final String bucket;
	private final long partSize;
	private final File tempDir;
	private final ExecutorService service;

	public S3Filer(@Nonnull Config config, @Nonnull Access access) {
		checkNotNull(config);
		checkNotNull(access);
		uri = URI.create(config.getString("url"));
		bucket = uri.getHost();
		partSize = config.getInt("s3.partSize", 5 * 1024 * 1024);
		tempDir = new File(config.getString("s3.tempDir", System.getProperty("java.io.tmpdir")));
		service = Executors.newFixedThreadPool(config.getInt("s3.threads", 1));

		String key = config.getString("aws.key", null);
		if (key != null) {
			String secret = config.getString("aws.secret", null);
			if (secret == null) {
				secret = access.getPrompt().prompt(key + ".secret", key + ".secret: ", Prompt.Type.MASKED, false);
			}
			s3 = new AmazonS3Client(new BasicAWSCredentials(key, secret));
		} else {
			s3 = new AmazonS3Client();
		}
	}

	@Override
	@Nonnull
	public URI getUri() throws IOException {
		return uri;
	}

	@Override
	@Nonnull
	public List<Record> listRecords(@Nonnull String path) throws IOException {
		ObjectListing listing = s3.listObjects(
				new ListObjectsRequest().withBucketName(bucket).withPrefix(path.substring(1)));

		List<Record> records = Lists.newArrayList();
		for (S3ObjectSummary summary : listing.getObjectSummaries()) {
			File file = new File(summary.getKey());
			String parent = file.getParent();
			String name = file.getName();
			long time = summary.getLastModified().getTime();
			long size = summary.getSize();
			boolean directory = name.endsWith("/");
			records.add(new Record(uri, "/" + parent, name, time, size, directory));
		}
		return records;
	}

	@Override
	@Nonnull
	public Record getRecord(@Nonnull String path) throws IOException {
		List<Record> records = listRecords(path);
		return records.size() == 0 ? Record.noFile(uri, path) : records.get(0);
	}

	@Override
	@Nonnull
	public InputStream readFile(@Nonnull String path) throws IOException {
		return s3.getObject(bucket, path.substring(1)).getObjectContent();
	}

	@Override
	@Nonnull
	public OutputStream writeFile(@Nonnull String path) throws IOException {
		String key = path.substring(1);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		List<Future<PartETag>> futureTags = new ArrayList<>();
		Lazy<String> uploadId = new Lazy<>(
				() -> s3.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, key)).getUploadId());

		return new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				baos.write(b);
				flip(partSize);
			}

			@Override
			public void write(byte[] b) throws IOException {
				baos.write(b);
				flip(partSize);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				baos.write(b, off, len);
				flip(partSize);
			}

			@Override
			public void close() throws IOException {
				if (futureTags.size() == 0) {
					InputStream in = new ByteArrayInputStream(baos.toByteArray());
					ObjectMetadata metadata = new ObjectMetadata();
					metadata.setContentLength(baos.size());
					s3.putObject(bucket, key, in, metadata);
				} else {
					flip(1);

					List<PartETag> tags = Lists.newArrayList();
					for (Future<PartETag> futureTag : futureTags) {
						try {
							tags.add(futureTag.get());
						} catch (Exception e) {
							s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId.get()));
							throw new IOException("failed to upload: " + path, e);
						}
					}

					s3.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, key, uploadId.get(), tags));
				}
			}

			private void flip(long minSize) throws IOException {
				if (baos.size() < minSize) {
					return;
				}

				File file = new File(tempDir, UUID.randomUUID().toString());
				file.deleteOnExit();

				OutputStream out = new FileOutputStream(file);
				out.write(baos.toByteArray());
				out.close();

				baos.reset();

				UploadPartRequest uploadRequest = new UploadPartRequest()
						.withUploadId(uploadId.get())
						.withBucketName(bucket)
						.withKey(key)
						.withPartNumber(futureTags.size() + 1)
						.withPartSize(file.length())
						.withFile(file);

				futureTags.add(service.submit(() -> {
					try {
						return s3.uploadPart(uploadRequest).getPartETag();
					} finally {
						file.delete();
					}
				}));
			}
		};
	}

	@Override
	@Nonnull
	public OutputStream appendFile(@Nonnull String path) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	@Nonnull
	public DataIo openFile(@Nonnull String path, boolean write) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setFileTime(@Nonnull String path, long time) throws IOException {
		S3Object object = s3.getObject(bucket, path.substring(1));
		ObjectMetadata metadata = object.getObjectMetadata();
		metadata.setLastModified(new Date(time));
		object.setObjectMetadata(metadata);
	}

	@Override
	public void deleteFile(@Nonnull String path) throws IOException {
		s3.deleteObject(bucket, path.substring(1));
	}

	@Override
	public void renameFile(@Nonnull String oldPath, @Nonnull String newPath) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void createDirs(@Nonnull String path) throws IOException {
		InputStream in = new ByteArrayInputStream(new byte[0]);
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(0);
		s3.putObject(bucket, path.substring(1) + "/", in, metadata);
	}

	@Override
	public void close() throws IOException {
	}
}
