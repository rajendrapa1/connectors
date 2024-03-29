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

package org.apache.flink.connector.delta.sink;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.apache.flink.api.connector.sink.Committer;
import org.apache.flink.api.connector.sink.GlobalCommitter;
import org.apache.flink.api.connector.sink.Sink;
import org.apache.flink.api.connector.sink.SinkWriter;
import org.apache.flink.connector.delta.sink.committables.DeltaCommittable;
import org.apache.flink.connector.delta.sink.committables.DeltaGlobalCommittable;
import org.apache.flink.connector.delta.sink.committer.DeltaGlobalCommitter;
import org.apache.flink.connector.delta.sink.writer.DeltaWriter;
import org.apache.flink.connector.delta.sink.writer.DeltaWriterBucketState;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.formats.parquet.ParquetWriterFactory;
import org.apache.flink.formats.parquet.row.ParquetRowDataBuilder;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.hadoop.conf.Configuration;
import static org.apache.flink.util.Preconditions.checkNotNull;

import io.delta.standalone.DeltaLog;

/**
 * A unified sink that emits its input elements to {@link FileSystem} files within buckets using
 * Parquet format and commits those files to the {@link DeltaLog}. This sink achieves exactly-once
 * semantics for both {@code BATCH} and {@code STREAMING}.
 * <p>
 * Behaviour of this sink splits down upon two phases. The first phase takes place between
 * application's checkpoints when records are being flushed to files (or appended to writers'
 * buffers) where the behaviour is almost identical as in case of
 * {@link org.apache.flink.connector.file.sink.FileSink}.
 * <p>
 * Next during the checkpoint phase files are "closed" (renamed) by the independent instances of
 * {@link org.apache.flink.connector.delta.sink.committer.DeltaCommitter} that behave very similar
 * to {@link org.apache.flink.connector.file.sink.committer.FileCommitter}.
 * When all the parallel committers are done, then all the files are committed at once by
 * single-parallelism {@link org.apache.flink.connector.delta.sink.committer.DeltaGlobalCommitter}.
 * <p>
 * This {@link DeltaSink} sources many specific implementations from the
 * {@link org.apache.flink.connector.file.sink.FileSink} so for most of the low level behaviour one
 * may refer to the docs from this module. The most notable differences to the FileSinks are:
 * <ul>
 *  <li>tightly coupling DeltaSink to the Bulk-/ParquetFormat</li>
 *  <li>extending committable information with files metadata (name, size, rows, last update
 *      timestamp)</li>
 *  <li>providing DeltaLake-specific behaviour which is mostly contained in the
 *      {@link DeltaGlobalCommitter} implementing the commit to the {@link DeltaLog} at the final
 *      stage of each checkpoint.</li>
 * </ul>
 *
 * @param <IN> Type of the elements in the input of the sink that are also the elements to be
 *             written to its output
 * @implNote This sink sources many methods and solutions from
 * {@link org.apache.flink.connector.file.sink.FileSink} implementation simply by copying the
 * code since it was not possible to directly reuse those due to some access specifiers, use of
 * generics and need to provide some internal workarounds compared to the FileSink. To make it
 * explicit which methods are directly copied from FileSink we use `FileSink-specific methods`
 * comment marker inside class files to decouple DeltaLake's specific code from parts borrowed
 * from FileSink.
 */
public class DeltaSink<IN>
        implements Sink<IN, DeltaCommittable, DeltaWriterBucketState, DeltaGlobalCommittable> {

    private final DeltaSinkBuilder<IN> sinkBuilder;

    DeltaSink(DeltaSinkBuilder<IN> sinkBuilder) {
        this.sinkBuilder = checkNotNull(sinkBuilder);
    }

    @Override
    public SinkWriter<IN, DeltaCommittable, DeltaWriterBucketState> createWriter(
            InitContext context,
            List<DeltaWriterBucketState> states
    ) throws IOException {
        DeltaWriter<IN> writer = sinkBuilder.createWriter();
        return writer;
    }

    @Override
    public Optional<SimpleVersionedSerializer<DeltaWriterBucketState>> getWriterStateSerializer() {
        try {
            return Optional.of(sinkBuilder.getWriterStateSerializer());
        } catch (IOException e) {
            throw new FlinkRuntimeException("Could not create writer state serializer.", e);
        }
    }

    @Override
    public Optional<Committer<DeltaCommittable>> createCommitter() throws IOException {
        return Optional.of(sinkBuilder.createCommitter());
    }

    @Override
    public Optional<SimpleVersionedSerializer<DeltaCommittable>> getCommittableSerializer() {
        try {
            return Optional.of(sinkBuilder.getCommittableSerializer());
        } catch (IOException e) {
            throw new FlinkRuntimeException("Could not create committable serializer.", e);
        }
    }

    @Override
    public Optional<
            GlobalCommitter<DeltaCommittable,
                    DeltaGlobalCommittable>> createGlobalCommitter() {
        return Optional.of(sinkBuilder.createGlobalCommitter());
    }

    @Override
    public Optional<
            SimpleVersionedSerializer<DeltaGlobalCommittable>> getGlobalCommittableSerializer() {
        try {
            return Optional.of(sinkBuilder.getGlobalCommittableSerializer());
        } catch (IOException e) {
            throw new FlinkRuntimeException("Could not create committable serializer.", e);
        }
    }

    /**
     * Convenience method for creating {@link DeltaSink}
     * <p>
     * For configuring additional options (e.g. bucket assigners in case of partitioning tables)
     * see {@link DeltaSinkBuilder}.
     *
     * @param basePath root path of the DeltaLake's table
     * @param conf     Hadoop's conf object that will be used for creating instances of
     *                 {@link io.delta.standalone.DeltaLog} and will be also passed to the
     *                 {@link ParquetRowDataBuilder} to create {@link ParquetWriterFactory}
     * @return builder for the DeltaSink
     */
    public static DeltaSinkBuilder<RowData> forDeltaFormat(
            final Path basePath,
            final Configuration conf
    ) {
        return new DeltaSinkBuilder.DefaultDeltaFormatBuilder<>(
                basePath,
                conf
        );
    }
}
