/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon;

import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.index.HashIndexFile;
import org.apache.paimon.index.IndexFileHandler;
import org.apache.paimon.manifest.IndexManifestFile;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.operation.FileStoreCommitImpl;
import org.apache.paimon.operation.FileStoreExpireImpl;
import org.apache.paimon.operation.PartitionExpire;
import org.apache.paimon.operation.SnapshotDeletion;
import org.apache.paimon.operation.TagDeletion;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.tag.TagAutoCreation;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.FileStorePathFactory;
import org.apache.paimon.utils.SegmentsCache;
import org.apache.paimon.utils.SnapshotManager;
import org.apache.paimon.utils.TagManager;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.Comparator;

/**
 * Base {@link FileStore} implementation.
 *
 * @param <T> type of record to read and write.
 */
public abstract class AbstractFileStore<T> implements FileStore<T> {

    protected final FileIO fileIO;
    protected final SchemaManager schemaManager;
    protected final long schemaId;
    protected final CoreOptions options;
    protected final RowType partitionType;

    @Nullable private final SegmentsCache<String> writeManifestCache;

    public AbstractFileStore(
            FileIO fileIO,
            SchemaManager schemaManager,
            long schemaId,
            CoreOptions options,
            RowType partitionType) {
        this.fileIO = fileIO;
        this.schemaManager = schemaManager;
        this.schemaId = schemaId;
        this.options = options;
        this.partitionType = partitionType;
        MemorySize writeManifestCache = options.writeManifestCache();
        this.writeManifestCache =
                writeManifestCache.getBytes() == 0
                        ? null
                        : new SegmentsCache<>(options.pageSize(), writeManifestCache);
    }

    @Override
    public FileStorePathFactory pathFactory() {
        return new FileStorePathFactory(
                options.path(),
                partitionType,
                options.partitionDefaultName(),
                options.fileFormat().getFormatIdentifier());
    }

    @Override
    public SnapshotManager snapshotManager() {
        return new SnapshotManager(fileIO, options.path());
    }

    @Override
    public ManifestFile.Factory manifestFileFactory() {
        return manifestFileFactory(false);
    }

    protected ManifestFile.Factory manifestFileFactory(boolean forWrite) {
        return new ManifestFile.Factory(
                fileIO,
                schemaManager,
                partitionType,
                options.manifestFormat(),
                pathFactory(),
                options.manifestTargetSize().getBytes(),
                forWrite ? writeManifestCache : null);
    }

    @Override
    public ManifestList.Factory manifestListFactory() {
        return manifestListFactory(false);
    }

    protected ManifestList.Factory manifestListFactory(boolean forWrite) {
        return new ManifestList.Factory(
                fileIO,
                options.manifestFormat(),
                pathFactory(),
                forWrite ? writeManifestCache : null);
    }

    protected IndexManifestFile.Factory indexManifestFileFactory() {
        return new IndexManifestFile.Factory(fileIO, options.manifestFormat(), pathFactory());
    }

    @Override
    public IndexFileHandler newIndexFileHandler() {
        return new IndexFileHandler(
                snapshotManager(),
                indexManifestFileFactory().create(),
                new HashIndexFile(fileIO, pathFactory().indexFileFactory()));
    }

    @Override
    public RowType partitionType() {
        return partitionType;
    }

    @Override
    public CoreOptions options() {
        return options;
    }

    @Override
    public boolean mergeSchema(RowType rowType, boolean allowExplicitCast) {
        return schemaManager.mergeSchema(rowType, allowExplicitCast);
    }

    @Override
    public FileStoreCommitImpl newCommit(String commitUser) {
        return new FileStoreCommitImpl(
                fileIO,
                schemaManager,
                commitUser,
                partitionType,
                pathFactory(),
                snapshotManager(),
                manifestFileFactory(),
                manifestListFactory(),
                indexManifestFileFactory(),
                newScan(),
                options.bucket(),
                options.manifestTargetSize(),
                options.manifestFullCompactionThresholdSize(),
                options.manifestMergeMinCount(),
                partitionType.getFieldCount() > 0 && options.dynamicPartitionOverwrite(),
                newKeyComparator());
    }

    @Override
    public FileStoreExpireImpl newExpire() {
        return new FileStoreExpireImpl(
                options.snapshotNumRetainMin(),
                options.snapshotNumRetainMax(),
                options.snapshotTimeRetain().toMillis(),
                snapshotManager(),
                newSnapshotDeletion(),
                newTagManager(),
                options.snapshotExpireLimit());
    }

    @Override
    public SnapshotDeletion newSnapshotDeletion() {
        return new SnapshotDeletion(
                fileIO,
                pathFactory(),
                manifestFileFactory().create(),
                manifestListFactory().create(),
                newIndexFileHandler());
    }

    @Override
    public TagManager newTagManager() {
        return new TagManager(fileIO, options.path());
    }

    @Override
    public TagDeletion newTagDeletion() {
        return new TagDeletion(
                fileIO,
                pathFactory(),
                manifestFileFactory().create(),
                manifestListFactory().create(),
                newIndexFileHandler());
    }

    public abstract Comparator<InternalRow> newKeyComparator();

    @Override
    @Nullable
    public PartitionExpire newPartitionExpire(String commitUser) {
        Duration partitionExpireTime = options.partitionExpireTime();
        if (partitionExpireTime == null || partitionType().getFieldCount() == 0) {
            return null;
        }

        return new PartitionExpire(
                partitionType(),
                partitionExpireTime,
                options.partitionExpireCheckInterval(),
                options.partitionTimestampPattern(),
                options.partitionTimestampFormatter(),
                newScan(),
                newCommit(commitUser));
    }

    @Override
    @Nullable
    public TagAutoCreation newTagCreationManager() {
        return TagAutoCreation.create(
                options, snapshotManager(), newTagManager(), newTagDeletion());
    }
}
