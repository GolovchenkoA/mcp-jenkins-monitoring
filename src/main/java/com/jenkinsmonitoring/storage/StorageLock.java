package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.config.AppProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps two instances from writing the same storage folder. The port alone does not protect the data: a
 * second instance started on another port would happily corrupt the files.
 */
@Component
public class StorageLock implements DisposableBean {

    private final FileChannel channel;
    private final FileLock lock;

    public StorageLock(AppProperties.Files files) throws IOException {
        Path folder = files.storagePath();
        Files.createDirectories(folder);
        Path lockFile = folder.resolve(".lock");
        this.channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            acquired = null;
        }
        if (acquired == null) {
            channel.close();
            throw new IllegalStateException("Another instance is already using the storage folder " + folder);
        }
        this.lock = acquired;
    }

    @Override
    public void destroy() throws IOException {
        lock.release();
        channel.close();
    }
}
