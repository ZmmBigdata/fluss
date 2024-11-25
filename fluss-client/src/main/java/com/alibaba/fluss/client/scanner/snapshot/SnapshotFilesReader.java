/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.client.scanner.snapshot;

import com.alibaba.fluss.client.scanner.ScanRecord;
import com.alibaba.fluss.metadata.KvFormat;
import com.alibaba.fluss.metadata.Schema;
import com.alibaba.fluss.rocksdb.RocksDBHandle;
import com.alibaba.fluss.rocksdb.RocksIteratorWrapper;
import com.alibaba.fluss.row.InternalRow;
import com.alibaba.fluss.row.ProjectedRow;
import com.alibaba.fluss.row.decode.RowDecoder;
import com.alibaba.fluss.row.encode.ValueDecoder;
import com.alibaba.fluss.types.DataType;
import com.alibaba.fluss.utils.CloseableRegistry;
import com.alibaba.fluss.utils.IOUtils;

import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * A reader to read kv snapshot files to {@link ScanRecord}s. It will return the {@link ScanRecord}s
 * as a iterator.
 */
@NotThreadSafe
class SnapshotFilesReader implements Iterator<ScanRecord>, AutoCloseable {

    private final ValueDecoder valueDecoder;
    @Nullable private final int[] projectedFields;
    private RocksIteratorWrapper rocksIteratorWrapper;

    private Snapshot snapshot;
    private RocksDBHandle rocksDBHandle;
    private boolean isClose = false;

    private final CloseableRegistry closeableRegistry;

    SnapshotFilesReader(
            KvFormat kvFormat,
            Path rocksDbPath,
            Schema tableSchema,
            @Nullable int[] projectedFields)
            throws IOException {
        this.valueDecoder =
                new ValueDecoder(
                        RowDecoder.create(
                                kvFormat,
                                tableSchema.toRowType().getChildren().toArray(new DataType[0])));
        this.projectedFields = projectedFields;
        closeableRegistry = new CloseableRegistry();
        try {
            initRocksDB(rocksDbPath);
            initRocksIterator();
        } catch (Throwable t) {
            releaseSnapshot();
            // If anything goes wrong, clean up our stuff. If things went smoothly the
            // merging iterator is now responsible for closing the resources
            IOUtils.closeQuietly(closeableRegistry);
            throw new IOException("Error creating RocksDB snapshot reader.", t);
        }
    }

    private void initRocksDB(Path rocksDbPath) throws Exception {
        // create rocksdb
        DBOptions dbOptions = new DBOptions();
        closeableRegistry.registerCloseable(dbOptions::close);
        ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
        closeableRegistry.registerCloseable(columnFamilyOptions::close);

        rocksDBHandle =
                new RocksDBHandle(rocksDbPath.toFile(), dbOptions, columnFamilyOptions, true);
        closeableRegistry.registerCloseable(rocksDBHandle::close);
    }

    private void initRocksIterator() throws IOException {
        // open a db
        rocksDBHandle.openDB();
        // get the snapshot
        RocksDB db = rocksDBHandle.getDb();
        snapshot = db.getSnapshot();
        closeableRegistry.registerCloseable(snapshot::close);

        // use the snapshot to read rocksdb
        ReadOptions readOptions = new ReadOptions();
        closeableRegistry.registerCloseable(readOptions::close);
        readOptions.setSnapshot(snapshot);

        // get the iterator
        RocksIterator rocksIterator = db.newIterator(db.getDefaultColumnFamily(), readOptions);
        rocksIteratorWrapper = new RocksIteratorWrapper(rocksIterator);
        closeableRegistry.registerCloseable(rocksIteratorWrapper);

        // seek to first
        rocksIteratorWrapper.seekToFirst();
    }

    public void close() throws IOException {
        if (isClose) {
            return;
        }

        releaseSnapshot();
        closeableRegistry.close();
        isClose = true;
    }

    private void releaseSnapshot() {
        if (snapshot != null && rocksDBHandle != null) {
            rocksDBHandle.getDb().releaseSnapshot(snapshot);
            snapshot = null;
        }
    }

    @Override
    public boolean hasNext() {
        return !isClose && rocksIteratorWrapper.isValid();
    }

    @Override
    public ScanRecord next() {
        byte[] value = rocksIteratorWrapper.value();
        rocksIteratorWrapper.next();

        InternalRow originRow = valueDecoder.decodeValue(value).row;
        if (projectedFields != null) {
            ProjectedRow row = ProjectedRow.from(projectedFields);
            row.replaceRow(originRow);
            return new ScanRecord(row);
        } else {
            return new ScanRecord(originRow);
        }
    }
}