/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import org.neo4j.graphalgo.compat.GraphDatabaseApiProxy;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.internal.kernel.api.Cursor;
import org.neo4j.internal.kernel.api.Scan;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;

abstract class AbstractCursorBasedScanner<Reference, EntityCursor extends Cursor, Store extends RecordStore<? extends AbstractBaseRecord>> implements StoreScanner<Reference> {

    private final class ScanCursor implements StoreScanner.ScanCursor<Reference> {

        private final Scan<EntityCursor> scan;

        private EntityCursor cursor;

        private Reference cursorReference;

        ScanCursor(
            EntityCursor cursor,
            Reference reference,
            Scan<EntityCursor> entityCursorScan
        ) {
            this.cursor = cursor;
            this.cursorReference = reference;
            this.scan = entityCursorScan;
        }

        @Override
        public int bulkSize() {
            return prefetchSize * recordsPerPage;
        }

        @Override
        public boolean bulkNext(RecordConsumer<Reference> consumer) {
            boolean hasNextBatch = scan.reserveBatch(cursor, bulkSize());

            if (!hasNextBatch) {
                return false;
            }

            while (cursor.next()) {
                consumer.offer(cursorReference);
            }

            return true;
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public void close() {
            if (cursor != null) {
                cursor.close();
                cursor = null;
                cursorReference = null;

                final StoreScanner.ScanCursor<Reference> localCursor = cursors.get();
                // sanity check, should always be called from the same thread
                if (localCursor == this) {
                    cursors.remove();
                }
            }
        }
    }

    // fetch this many pages at once
    private final int prefetchSize;
    // size in bytes of a single record - advance the offset by this much
    private final int recordSize;
    // how many records are there in a single page
    private final int recordsPerPage;

    private final GraphDatabaseApiProxy.Transactions transactions;
    // global cursor pool to return this one to
    private final ThreadLocal<StoreScanner.ScanCursor<Reference>> cursors;

    private final Scan<EntityCursor> entityCursorScan;

    private final Store store;

    AbstractCursorBasedScanner(int prefetchSize, GraphDatabaseService api) {
        var neoStores = GraphDatabaseApiProxy.neoStores(api);
        var store = store(neoStores);
        int recordSize = store.getRecordSize();
        int recordsPerPage = store.getRecordsPerPage();

        // Ideally, this tx would be given by the calling procedure.
        this.transactions = GraphDatabaseApiProxy.newKernelTransaction(api);

        this.prefetchSize = prefetchSize;
        this.entityCursorScan = entityCursorScan(transactions.ktx());
        this.cursors = new ThreadLocal<>();
        this.recordSize = recordSize;
        this.recordsPerPage = recordsPerPage;
        this.store = store;
    }

    @Override
    public void close() {
        transactions.close();
    }

    @Override
    public final StoreScanner.ScanCursor<Reference> getCursor(KernelTransaction transaction) {
        StoreScanner.ScanCursor<Reference> scanCursor = this.cursors.get();

        if (scanCursor == null) {
            EntityCursor entityCursor = entityCursor(transaction);
            Reference reference = cursorReference(entityCursor);
            scanCursor = new ScanCursor(entityCursor, reference, entityCursorScan);
            this.cursors.set(scanCursor);
        }

        return scanCursor;
    }

    @Override
    public final long storeSize() {
        long recordsInUse = 1L + store.getHighestPossibleIdInUse();
        long idsInPages = ((recordsInUse + (recordsPerPage - 1L)) / recordsPerPage) * recordsPerPage;
        return idsInPages * (long) recordSize;
    }

    abstract Store store(NeoStores neoStores);

    abstract EntityCursor entityCursor(KernelTransaction transaction);

    abstract Scan<EntityCursor> entityCursorScan(KernelTransaction transaction);

    abstract Reference cursorReference(EntityCursor cursor);
}
