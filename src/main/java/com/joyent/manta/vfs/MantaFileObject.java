package com.joyent.manta.vfs;

import com.joyent.manta.client.MantaClient;
import com.joyent.manta.client.MantaMetadata;
import com.joyent.manta.client.MantaObject;
import com.joyent.manta.client.MantaObjectResponse;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import com.joyent.manta.org.apache.commons.lang3.ObjectUtils;
import com.joyent.manta.org.apache.commons.lang3.StringUtils;
import com.joyent.manta.org.apache.commons.lang3.Validate;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileObject;
import org.apache.commons.vfs2.util.RandomAccessMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.Map;


import static com.joyent.manta.client.MantaClient.SEPARATOR;
import static java.util.stream.Collectors.toMap;

/**
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 */
public class MantaFileObject extends AbstractFileObject<MantaFileSystem> {
    private static Log LOG = LogFactory.getLog(MantaFileObject.class);

    private MantaObject lastResponse = null;

    public MantaFileObject(final AbstractFileName name, final MantaFileSystem fs) {
        super(name, fs);
    }

    @Override
    protected long doGetContentSize() throws Exception {
        final MantaObject response = this.lastResponse;

        if (lastResponse == null) {
            return -1L;
        }

        final Long length = response.getContentLength();

        return ObjectUtils.firstNonNull(length, -1L);
    }

    @Override
    protected InputStream doGetInputStream() throws Exception {
        final MantaClient client = getAbstractFileSystem().getClient();

        return client.getAsInputStream(path());
    }

    @Override
    protected OutputStream doGetOutputStream(boolean bAppend) throws Exception {
        if (bAppend) {
            throw new FileSystemException("vfs.provider/write-append-not-supported.error", getName());
        }

        final MantaClient client = getAbstractFileSystem().getClient();

        return client.putAsOutputStream(path());
    }

    @Override
    protected FileType doGetType() throws Exception {
        // If we are at the root
        if (isRoot()) {
            return FileType.FOLDER;
        }

        final MantaObject response = this.lastResponse;
        if (response == null) {
            return FileType.IMAGINARY;
        } else if (response.isDirectory()) {
            return FileType.FOLDER;
        } else {
            return FileType.FILE;
        }
    }

    @Override
    protected String[] doListChildren() throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
        /* Don't bother to query Manta directly if we are attempting to list
         * the root directory. Rather, we just simulate a single directory
         * listing containing the home directory. */
            if (isRoot()) {
                final String homeDir = fs.getMantaConfig().getMantaHomeDirectory();
                final String stripped = StringUtils.stripStart(homeDir, SEPARATOR);
                return new String[] { stripped };
            }

            final MantaClient client = fs.getClient();
            final String filePath = path();

            return client
                    .listObjects(filePath)
                    .map(mantaObject -> {
                        final String mantaPath = mantaObject.getPath();
                        final String child = StringUtils.removeStart(mantaPath, filePath);
                        return StringUtils.stripStart(child, SEPARATOR);
                    })
                    .toArray(String[]::new);
        }
    }

    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            /* Don't bother to query Manta directly if we are attempting to list
             * the root directory. Rather, we just simulate a single directory
             * listing containing the home directory. */
                if (isRoot()) {
                    final String homeDir = fs.getMantaConfig().getMantaHomeDirectory();
                    final MantaFileName fileName = new MantaFileName(homeDir, FileType.FOLDER);
                    return new FileObject[] { new MantaFileObject(fileName, fs) } ;
                }

                final MantaClient client = fs.getClient();
                final String filePath = path();

                return client
                        .listObjects(filePath)
                        .map(mantaObject -> {
                            final String mantaPath = mantaObject.getPath();
                            final FileType type = mantaObject.isDirectory() ? FileType.FOLDER : FileType.FILE;
                            final MantaFileName fileName = new MantaFileName(mantaPath, type);
                            final MantaFileObject object = new MantaFileObject(fileName, fs);
                            object.lastResponse = mantaObject;

                            return object;
                        })
                        .toArray(MantaFileObject[]::new);
            }
    }

    @Override
    protected Map<String, Object> doGetAttributes() throws Exception {
        return lastResponse.getMetadata()
                .entrySet()
                .stream()
                .collect(toMap(entry -> StringUtils.removeStart(entry.getKey(), "m-"), Map.Entry::getValue));
    }

    @Override
    protected void doSetAttribute(final String attrName, final Object objVal) throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();

            final String key = String.format("m-%s", attrName);
            final String val = objVal.toString();

            MantaMetadata metadata = new MantaMetadata();
            metadata.put(key, val);

            if (isAttached()) {
                lastResponse.getMetadata().put(key, val);
            }

            client.putMetadata(path(), metadata);
        }
    }

    @Override
    protected void doRemoveAttribute(final String attrName) throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();

            final String key = String.format("m-%s", attrName);

            MantaMetadata metadata = new MantaMetadata();
            metadata.delete(key);

            if (isAttached()) {
                lastResponse.getMetadata().remove(key);
            }

            client.putMetadata(path(), metadata);
        }
    }

    @Override
    public boolean isHidden() throws FileSystemException {
        return false;
    }

    @Override
    public boolean isExecutable() throws FileSystemException {
        return false;
    }

    @Override
    public boolean isWriteable() {
        if (isRoot()) {
            return false;
        }

        final String path = path();
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final ConfigContext config = fs.getMantaConfig();
            final String homeDir = config.getMantaHomeDirectory();
            final String publicDir = String.format("%s/public", homeDir);
            final String storDir = String.format("%s/stor", homeDir);

            if (FilenameUtils.equalsNormalized(path, homeDir)) {
                return false;
            }

            if (FilenameUtils.equalsNormalized(path, publicDir) ||
                    FilenameUtils.equalsNormalized(path, storDir)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean canRenameTo(final FileObject newfile) {
        Validate.notNull(newfile, "File object must be present");

        // we currently only support renaming files at this time
        try {
            return super.canRenameTo(newfile) && newfile.isFile();
        } catch (FileSystemException e) {
            String msg = String.format("Error checking rename status for file %s -> %s",
                    path(), path(newfile.getName()));

            LOG.warn(msg, e);

            return false;
        }
    }

    /**
     * Returns the receiver as a URI String for public display if the file is contained in
     * the /public directory. Otherwise, we return a signed URL that is valid for 1 hour.
     *
     * @return A URI String without a password, never {@code null}.
     */
    @Override
    public String getPublicURIString() {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final ConfigContext config = fs.getMantaConfig();
            final String path = path();

            if (path.startsWith(SEPARATOR + "public" + SEPARATOR)) {
                final StringBuilder publicUri = new StringBuilder();
                publicUri.append(config.getMantaURL());
                publicUri.append(path);
                return publicUri.toString();
            }

            final MantaClient client = getAbstractFileSystem().getClient();

            try {
                final URI signed = client.getAsSignedURI(path, "GET", Duration.ofHours(1L));
                return signed.toString();
            } catch (IOException e) {
                final String msg = String.format("Unable to create signed URL for path: %s", path);
                throw new UncheckedIOException(msg, e);
            }
        }
    }

    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();
            client.move(path(), path(newFile.getName()));
        }
    }

    @Override
    protected void doCreateFolder() throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();
            client.putDirectory(path());
        }
    }

    @Override
    protected void doDelete() throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();
            client.delete(path());
        }
    }

    @Override
    protected long doGetLastModifiedTime() throws Exception {
        final MantaObject response = this.lastResponse;
        final Date lastModTime = response.getLastModifiedTime();
        return lastModTime.getTime();
    }

    @Override
    protected void doAttach() throws Exception {
        if (isRoot()) {
            return;
        }

        try {
            if (this.lastResponse == null) {
                this.lastResponse = head();
            }
        } catch (MantaClientHttpResponseException e) {
            // Indicate that files don't exist when we hit a HTTP 404
            if (e.getStatusCode() == 404) {
                this.lastResponse = null;
                return;
            }

            throw e;
        }
    }

    @Override
    protected void doDetach() throws Exception {
        this.lastResponse = null;
    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(
            final RandomAccessMode mode) throws Exception {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();
            return new MantaRandomAccessContent(client.getSeekableByteChannel(path()));
        }
    }

    /**
     * Method that determines if the current file object represents the root
     * of the Manta file system (/).
     *
     * @return true when the path is /
     */
    public boolean isRoot() {
        return getName().getPath().equals(SEPARATOR);
    }

    public MantaObjectResponse head() throws IOException {
        final MantaFileSystem fs = getAbstractFileSystem();

        synchronized (fs) {
            final MantaClient client = fs.getClient();
            return client.head(path());
        }
    }

    protected String path() {
        return path(getName());
    }

    protected String path(final FileName vfsFileName) {
        return FilenameUtils.normalize(vfsFileName.getPath());
    }
}
