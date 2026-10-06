package com.systemwebstudio.logic.workflow

/** The in-memory queue (unit tests, explicit dev override) keeps the same promises as the broker-backed one. */
class InMemoryWorkflowQueueContractTests : WorkflowQueueContract() {
    override fun newQueue(): WorkflowQueue = InMemoryWorkflowQueue(maxDeliveries = 4)
    override fun killConsumer(queue: WorkflowQueue) { (queue as InMemoryWorkflowQueue).requeueInFlight() }
}
