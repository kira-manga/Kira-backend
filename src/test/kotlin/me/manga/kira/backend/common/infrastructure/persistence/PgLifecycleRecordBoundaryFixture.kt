package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** All native starts/schedules follow ownership registration. Cleanup steps are independent, not short-circuited by earlier failures. */
internal class PgLifecycleRecordBoundaryFixture(val mode: PgLifecycleRecordBoundaryMode) : AutoCloseable {
    val timer = PgLifecycleRecordBoundaryTimer()
    private val peer = if (mode.requests == 0) null else PgLifecycleRecordBoundaryPeer(mode.requests)
    private val retainedScope = AtomicReference<PgLifecycleTestScope?>()
    private val retainedProducer = AtomicReference<PgLifecycleRecordBoundaryProducer?>()
    private val retainedAdapter = AtomicReference<PgLifecycleRecordBoundaryAdapter?>()
    private val receiptModel = AtomicReference<PgLifecycleRecordBoundaryReceiptModel?>()
    private val retainedWitness = AtomicReference<PgLifecycleRecordBoundaryQueryWitness?>()
    val scope: PgLifecycleTestScope get() = requireNotNull(retainedScope.get())

    fun start() {
        timer.acquire()
        peer?.start()
        check(retainedScope.compareAndSet(null, PgLifecycleTestScope(pgProbeEndpoint(peer?.port ?: 1))))
        val witness = PgLifecycleRecordBoundaryQueryWitness(scope.binding(mode.deletion), Thread.currentThread())
        check(retainedWitness.compareAndSet(null, witness))
        witness.install(scope, mode.deletion) // Both G aliases, before every root actor start; never a live-lock replacement.
        scope.start()
        if (mode.deletion) scope.prepareDeletion()
        println("PG_LIFECYCLE_RECORD_BOUNDARY_BEGIN mode=${mode.name} requests=${mode.requests} source=G2_PRIVATE_PROPOSAL")
    }

    fun request(): PgLifecycleRecordBoundaryRecord = PgLifecycleRecordBoundaryRecord.capture(scope, mode.deletion, scope.request(mode.deletion))

    fun observeDisconnect(ordinal: Int) = requireNotNull(peer).observeRetirement(ordinal)

    fun ownProducer(): PgLifecycleRecordBoundaryProducer {
        val producer = PgLifecycleRecordBoundaryProducer(timer.timer())
        check(retainedProducer.compareAndSet(null, producer))
        producer.start()
        return producer
    }

    fun ownAdapter(): PgLifecycleRecordBoundaryAdapter {
        val adapter = PgLifecycleRecordBoundaryAdapter(scope.root, timer)
        check(retainedAdapter.compareAndSet(null, adapter))
        return adapter // Caller starts only after this exact owner is retained.
    }

    fun ownReceiptModel(record: PgLifecycleRecordBoundaryRecord, access: PgLifecycleRecordBoundaryAccess): PgLifecycleRecordBoundaryReceiptModel {
        val model = PgLifecycleRecordBoundaryReceiptModel(record, access, timer.hold)
        check(receiptModel.compareAndSet(null, model))
        return model // Neither constructor nor ownership registration changes a receipt.
    }

    fun verify() {
        peer?.verify()
        check(!scope.owner.snapshot().weakEvidenceUsed)
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        fun attempt(label: String, action: () -> Unit) {
            val result = runCatching(action)
            result.exceptionOrNull()?.let { failures.add(it) }
            println("PG_LIFECYCLE_RECORD_BOUNDARY_CLEANUP_STEP action=$label success=${result.isSuccess}")
        }
        attempt("model_restore") { receiptModel.get()?.restore() }
        attempt("query_disarm") { retainedWitness.get()?.close() }
        attempt("timer_gates") { timer.releaseGates() }
        attempt("producer_gates") { retainedProducer.get()?.releaseGates() }
        attempt("adapter_gates") { retainedAdapter.get()?.releaseGates() }
        attempt("peer_gates") { peer?.releaseGates() }
        attempt("producer_join") { retainedProducer.get()?.close() }
        attempt("adapter_join") { retainedAdapter.get()?.close() }
        attempt("root_request") { retainedScope.get()?.owner?.requestShutdown() }
        attempt("peer_close") { peer?.close() }
        attempt("foreign_ref") { timer.releaseReference() }
        attempt("root_observe") { retainedScope.get()?.close() }
        attempt("root_actors") {
            retainedScope.get()?.actors()?.let { actors ->
                pgLifecycleTlsJoin(actors.map { it.thread })
                check(actors.all { it.termination().ended() && !it.thread.isAlive })
                println("PG_LIFECYCLE_RECORD_BOUNDARY_ROOT_ACTORS count=${actors.size} all_terminated=true")
            }
        }
        attempt("timer_join") { timer.finish() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            throw first
        }
    }
}
