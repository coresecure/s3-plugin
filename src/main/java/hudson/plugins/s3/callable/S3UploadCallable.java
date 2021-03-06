package hudson.plugins.s3.callable;

import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.plugins.s3.Destination;
import hudson.plugins.s3.FingerprintRecord;
import hudson.plugins.s3.MD5;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class S3UploadCallable extends S3Callable {
    private static final long serialVersionUID = 1L;
    private final String bucketName;
    private final Destination dest;
    private final String storageClass;
    private final Map<String, String> userMetadata;
    private final boolean produced;
    private final boolean useServerSideEncryption;
    private final boolean gzipFiles;
    private final String destFilename;


    public S3UploadCallable(boolean produced, String destFilename, String accessKey, Secret secretKey, boolean useRole, String bucketName,
                            Destination dest, Map<String, String> userMetadata, String storageClass, String selregion,
                            boolean useServerSideEncryption, boolean gzipFiles, ProxyConfiguration proxy) {
        super(accessKey, secretKey, useRole, selregion, proxy);
        this.bucketName = bucketName;
        this.dest = dest;
        this.storageClass = storageClass;
        this.userMetadata = userMetadata;
        this.produced = produced;
        this.destFilename = destFilename;
        this.useServerSideEncryption = useServerSideEncryption;
        this.gzipFiles = gzipFiles;
    }

    /**
     * Upload from slave directly
     */
    @Override
    public FingerprintRecord invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
        return invoke(new FilePath(file));
    }

    /**
     * Stream from slave to master, then upload from master
     */
    public FingerprintRecord invoke(FilePath file) throws IOException, InterruptedException {
        final TransferManager transferManager = getTransferManager();

        final ObjectMetadata metadata = buildMetadata(file);
        File localFile = null;

        try (InputStream inputStream = file.read()) {
            final Upload upload;
            if (gzipFiles) {
                localFile = File.createTempFile("s3plugin", ".bin");

                try (OutputStream outputStream = new FileOutputStream(localFile)) {
                    try (OutputStream gzipStream = new GZIPOutputStream(outputStream, true)) {
                        IOUtils.copy(inputStream, gzipStream);
                        gzipStream.flush();
                    }
                }

                try (InputStream gzipedStream = new FileInputStream(localFile)) {
                    metadata.setContentEncoding("gzip");
                    metadata.setContentLength(localFile.length());
                    upload = transferManager.upload(dest.bucketName, dest.objectName, gzipedStream, metadata);
                }
            } else {
                upload = transferManager.upload(dest.bucketName, dest.objectName, inputStream, metadata);
            }
            upload.waitForCompletion();
        }

        final String md5;
        if (gzipFiles) {
            md5 = MD5.generateFromFile(localFile);
        } else {
            md5 = MD5.generateFromFile(file);
        }

        if (localFile != null) {
            localFile.delete();
        }

        return generateFingerprint(produced, bucketName, destFilename, md5);
    }

    private ObjectMetadata buildMetadata(FilePath filePath) throws IOException, InterruptedException {
        final ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(Mimetypes.getInstance().getMimetype(filePath.getName()));
        metadata.setContentLength(filePath.length());
        metadata.setLastModified(new Date(filePath.lastModified()));
        if (storageClass != null && !storageClass.isEmpty()) {
            metadata.setHeader("x-amz-storage-class", storageClass);
        }
        if (useServerSideEncryption) {
            metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        }

        for (Map.Entry<String, String> entry : userMetadata.entrySet()) {
            final String key = entry.getKey().toLowerCase();
            switch (key) {
                case "cache-control":
                    metadata.setCacheControl(entry.getValue());
                    break;
                case "expires":
                    try {
                        final Date expires = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z").parse(entry.getValue());
                        metadata.setHttpExpiresDate(expires);
                    } catch (ParseException e) {
                        metadata.addUserMetadata(entry.getKey(), entry.getValue());
                    }
                    break;
                case "content-encoding":
                    metadata.setContentEncoding(entry.getValue());
                    break;
                default:
                    metadata.addUserMetadata(entry.getKey(), entry.getValue());
                    break;
            }
        }
        return metadata;
    }
}
