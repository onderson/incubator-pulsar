/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.offload.impl;

import com.amazonaws.AmazonClientException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.api.LastConfirmedAndEntry;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.LedgerMetadata;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.client.impl.LedgerEntriesImpl;
import org.apache.bookkeeper.client.impl.LedgerEntryImpl;
import org.apache.pulsar.broker.offload.BackedInputStream;
import org.apache.pulsar.broker.offload.OffloadIndexBlock;
import org.apache.pulsar.broker.offload.OffloadIndexBlockBuilder;
import org.apache.pulsar.broker.offload.OffloadIndexEntry;
import org.apache.pulsar.broker.offload.impl.BlobStoreManagedLedgerOffloader.VersionCheck;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobStoreBackedReadHandleImpl implements ReadHandle {
    private static final Logger log = LoggerFactory.getLogger(BlobStoreBackedReadHandleImpl.class);

    private final long ledgerId;
    private final OffloadIndexBlock index;
    private final BackedInputStream inputStream;
    private final DataInputStream dataStream;
    private final ExecutorService executor;

    private BlobStoreBackedReadHandleImpl(long ledgerId, OffloadIndexBlock index,
                                          BackedInputStream inputStream,
                                          ExecutorService executor) {
        this.ledgerId = ledgerId;
        this.index = index;
        this.inputStream = inputStream;
        this.dataStream = new DataInputStream(inputStream);
        this.executor = executor;
    }

    @Override
    public long getId() {
        return ledgerId;
    }

    @Override
    public LedgerMetadata getLedgerMetadata() {
        return index.getLedgerMetadata();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        executor.submit(() -> {
                try {
                    index.close();
                    inputStream.close();
                    promise.complete(null);
                } catch (IOException t) {
                    promise.completeExceptionally(t);
                }
            });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readAsync(long firstEntry, long lastEntry) {
        log.debug("Ledger {}: reading {} - {}", getId(), firstEntry, lastEntry);
        CompletableFuture<LedgerEntries> promise = new CompletableFuture<>();
        executor.submit(() -> {
                if (firstEntry > lastEntry
                    || firstEntry < 0
                    || lastEntry > getLastAddConfirmed()) {
                    promise.completeExceptionally(new BKException.BKIncorrectParameterException());
                    return;
                }
                long entriesToRead = (lastEntry - firstEntry) + 1;
                List<LedgerEntry> entries = new ArrayList<LedgerEntry>();
                long nextExpectedId = firstEntry;
                try {
                    OffloadIndexEntry entry = index.getIndexEntryForEntry(firstEntry);
                    inputStream.seek(entry.getDataOffset());

                    while (entriesToRead > 0) {
                        int length = dataStream.readInt();
                        if (length < 0) { // hit padding or new block
                            inputStream.seekForward(index.getIndexEntryForEntry(nextExpectedId).getDataOffset());
                            length = dataStream.readInt();
                        }
                        long entryId = dataStream.readLong();

                        if (entryId == nextExpectedId) {
                            ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(length, length);
                            entries.add(LedgerEntryImpl.create(ledgerId, entryId, length, buf));
                            int toWrite = length;
                            while (toWrite > 0) {
                                toWrite -= buf.writeBytes(dataStream, toWrite);
                            }
                            entriesToRead--;
                            nextExpectedId++;
                        } else if (entryId > lastEntry) {
                            log.info("Expected to read {}, but read {}, which is greater than last entry {}",
                                     nextExpectedId, entryId, lastEntry);
                            throw new BKException.BKUnexpectedConditionException();
                        } else {
                            inputStream.skip(length);
                        }
                    }

                    promise.complete(LedgerEntriesImpl.create(entries));
                } catch (Throwable t) {
                    promise.completeExceptionally(t);
                    entries.forEach(LedgerEntry::close);
                }
            });
        return promise;
    }

    @Override
    public CompletableFuture<LedgerEntries> readUnconfirmedAsync(long firstEntry, long lastEntry) {
        return readAsync(firstEntry, lastEntry);
    }

    @Override
    public CompletableFuture<Long> readLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public CompletableFuture<Long> tryReadLastAddConfirmedAsync() {
        return CompletableFuture.completedFuture(getLastAddConfirmed());
    }

    @Override
    public long getLastAddConfirmed() {
        return getLedgerMetadata().getLastEntryId();
    }

    @Override
    public long getLength() {
        return getLedgerMetadata().getLength();
    }

    @Override
    public boolean isClosed() {
        return getLedgerMetadata().isClosed();
    }

    @Override
    public CompletableFuture<LastConfirmedAndEntry> readLastAddConfirmedAndEntryAsync(long entryId,
                                                                                      long timeOutInMillis,
                                                                                      boolean parallel) {
        CompletableFuture<LastConfirmedAndEntry> promise = new CompletableFuture<>();
        promise.completeExceptionally(new UnsupportedOperationException());
        return promise;
    }

    public static ReadHandle open(ScheduledExecutorService executor,
                                  BlobStore blobStore, String bucket, String key, String indexKey,
                                  VersionCheck versionCheck,
                                  long ledgerId, int readBufferSize)
            throws AmazonClientException, IOException {
        Blob blob = blobStore.getBlob(bucket, indexKey);
        versionCheck.check(indexKey, blob);
        OffloadIndexBlockBuilder indexBuilder = OffloadIndexBlockBuilder.create();
        OffloadIndexBlock index = indexBuilder.fromStream(blob.getPayload().openStream());

        BackedInputStream inputStream = new BlobStoreBackedInputStreamImpl(blobStore, bucket, key,
            versionCheck,
            index.getDataObjectLength(),
            readBufferSize);
        return new BlobStoreBackedReadHandleImpl(ledgerId, index, inputStream, executor);
    }
}
