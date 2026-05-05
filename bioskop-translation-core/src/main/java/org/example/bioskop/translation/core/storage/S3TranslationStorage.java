package org.example.bioskop.translation.core.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3TranslationStorage implements TranslationStorage {
    private final S3Client s3Client;
    private final String defaultBucket;

    public S3TranslationStorage(S3Client s3Client, String defaultBucket) {
        this.s3Client = s3Client;
        this.defaultBucket = defaultBucket;
    }

    @Override
    public String readText(String uri) {
        S3Location location = parseLocation(uri);
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.key())
                    .build())
                .asString(StandardCharsets.UTF_8);
        } catch (S3Exception e) {
            throw new TranslationStorageException("Failed to read text from " + uri, e);
        }
    }

    @Override
    public void writeText(String uri, String content) {
        S3Location location = parseLocation(uri);
        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.key())
                    .contentType("text/plain; charset=utf-8")
                    .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8)
            );
        } catch (S3Exception e) {
            throw new TranslationStorageException("Failed to write text to " + uri, e);
        }
    }

    @Override
    public boolean exists(String uri) {
        S3Location location = parseLocation(uri);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.key())
                .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new TranslationStorageException("Failed to check text existence at " + uri, e);
        }
    }

    private S3Location parseLocation(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
        if (uri.startsWith("s3://")) {
            URI parsed = URI.create(uri);
            String key = parsed.getPath();
            while (key.startsWith("/")) {
                key = key.substring(1);
            }
            return new S3Location(parsed.getHost(), key);
        }
        if (defaultBucket == null || defaultBucket.isBlank()) {
            throw new IllegalArgumentException("default bucket is required for non-s3 uri: " + uri);
        }
        String key = uri;
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return new S3Location(defaultBucket, key);
    }

    private record S3Location(String bucket, String key) {
    }
}
