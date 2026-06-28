/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.chaos;

import java.net.ConnectException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fault seam active under the {@code chaos} profile. Applies a fault only while its
 * scenario is armed; otherwise preserves production behavior.
 */
@Component
@Profile("chaos")
public class ActiveChaosFaults implements ChaosFaults {

	/** Scenario key: Class A synchronous NPE on owner search. */
	public static final String OWNER_SEARCH_NPE = "ownerSearchNpe";

	/** Scenario key: latency fault — N+1 query amplification on owner search. */
	public static final String OWNER_LIST_LATENCY = "ownerListLatency";

	/** Scenario key: error-ratio fault — opaque 5xx on the vet list. */
	public static final String VET_LIST_ERROR = "vetListError";

	/** Scenario key: silent data corruption — owner search returns wrong results. */
	public static final String OWNER_SEARCH_CORRUPTION = "ownerSearchCorruption";

	/**
	 * Scenario key: database-connectivity fault — owner-details read fails with a 5xx
	 * whose root cause is DB connectivity (misdiagnosis trap: route to infra, not app).
	 */
	public static final String DB_DOWN = "dbDown";

	/**
	 * Scenario key: thread-pool saturation — armed owner searches park their worker
	 * thread.
	 */
	public static final String THREAD_STARVATION = "threadStarvation";

	/** Scenario key: deadlock — armed owner searches spawn two threads that deadlock. */
	public static final String DEADLOCK = "deadlock";

	/**
	 * Scenario key: downstream-latency — armed owner-details calls a slow downstream
	 * dependency.
	 */
	public static final String DOWNSTREAM_LATENCY = "downstreamLatency";

	/**
	 * Scenario key: query-plan pathology — owner search uses a leading-wildcard Seq Scan.
	 */
	public static final String QUERY_PLAN_REGRESSION = "queryPlanRegression";

	/** Scenario key: OOM-kill — armed pod exhausts memory and is killed by the kernel. */
	public static final String OOM_KILL = "oomKill";

	/**
	 * Scenario key: connection-pool exhaustion — armed owner searches leak HikariCP
	 * connections until the pool is drained and queries time out.
	 */
	public static final String CONNECTION_POOL_EXHAUSTION = "connectionPoolExhaustion";

	/**
	 * Scenario key: CPU throttling — armed pods burn CPU past the container CPU limit so
	 * the kernel CFS scheduler throttles the container.
	 */
	public static final String CPU_THROTTLE = "cpuThrottle";

	/**
	 * Scenario key: queue backpressure — an armed producer floods a Kafka topic faster
	 * than the slow consumer drains it, so consumer lag accumulates.
	 */
	public static final String QUEUE_BACKPRESSURE = "queueBackpressure";

	/**
	 * Scenario key: GC thrashing — an armed allocator retains most of a bounded heap and
	 * churns on top, so the JVM spends most of its time in stop-the-world GC.
	 */
	public static final String GC_THRASHING = "gcThrashing";

	/**
	 * Scenario key: lock contention — armed owner searches serialize on one hot monitor,
	 * so concurrent load piles threads up BLOCKED (no cycle, so it is not a deadlock).
	 */
	public static final String LOCK_CONTENTION = "lockContention";

	/** Sentinel term that matches no owner (used by the corruption fault). */
	public static final String NO_MATCH_SENTINEL = "__chaos_nomatch__";

	private static final Object LOCK_A = new Object();

	private static final Object LOCK_B = new Object();

	private final AtomicBoolean deadlockTriggered = new AtomicBoolean(false);

	private final ChaosState state;

	@Value("${chaos.thread-block-ms:10000}")
	private long threadBlockMs = 10000;

	void setThreadBlockMs(long threadBlockMs) {
		this.threadBlockMs = threadBlockMs;
	}

	@Value("${chaos.pool.leak-cap:5}")
	private int leakCap = 5;

	private DataSource dataSource;

	private final List<Connection> leaked = Collections.synchronizedList(new ArrayList<>());

	private final AtomicInteger leakedReservations = new AtomicInteger(0);

	@Autowired(required = false)
	void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	void setLeakCap(int leakCap) {
		this.leakCap = leakCap;
	}

	public ActiveChaosFaults(ChaosState state) {
		this.state = state;
	}

	@Override
	public String normalizeLastName(String lastName) {
		if (this.state.isArmed(OWNER_SEARCH_NPE)) {
			// Seeded defect (Class A): the null guard that production has is
			// absent here, so a search with no last name throws NPE.
			return lastName.trim();
		}
		return lastName == null ? "" : lastName;
	}

	@Override
	public boolean amplifyOwnerReads() {
		return this.state.isArmed(OWNER_LIST_LATENCY);
	}

	@Override
	public void maybeFailVetList() {
		if (this.state.isArmed(VET_LIST_ERROR)) {
			// Seeded error-ratio fault: an opaque framework 5xx with no
			// underlying app defect to localize (routes to evidence-only).
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "vet directory upstream unavailable");
		}
	}

	@Override
	public String corruptSearchTerm(String lastName) {
		if (this.state.isArmed(OWNER_SEARCH_CORRUPTION)) {
			// Seeded silent corruption: query a term that matches nothing, so a
			// valid search returns wrong (empty) results with HTTP 200 and no error.
			return NO_MATCH_SENTINEL;
		}
		return lastName;
	}

	@Override
	public void assertDatabaseReachable() {
		if (this.state.isArmed(DB_DOWN)) {
			// Seeded infrastructure fault: the database connection is refused. The
			// resulting 5xx carries a stack trace, but its root cause is a
			// ConnectException (network/DB), NOT a localizable application defect —
			// the correct triage routes to infra, not an app code PR.
			throw new DataAccessResourceFailureException("could not open JDBC connection",
					new ConnectException("Connection refused"));
		}
	}

	@Override
	public void maybeBlockWorker() {
		if (this.state.isArmed(THREAD_STARVATION)) {
			// Seeded saturation: hold the worker thread so concurrent load drains a small
			// pool. The cause is only localizable from a thread dump (N threads parked
			// here).
			sleepQuietly(this.threadBlockMs);
		}
	}

	@Override
	public void triggerDeadlock() {
		if (this.state.isArmed(DEADLOCK) && this.deadlockTriggered.compareAndSet(false, true)) {
			// Seeded deadlock: two daemon threads lock LOCK_A/LOCK_B in opposite order
			// with
			// a gap between, so each holds one monitor and blocks on the other forever.
			Thread first = new Thread(() -> {
				synchronized (LOCK_A) {
					sleepQuietly(200);
					synchronized (LOCK_B) {
						// unreachable while the second thread holds LOCK_B
					}
				}
			}, "chaos-deadlock-1");
			Thread second = new Thread(() -> {
				synchronized (LOCK_B) {
					sleepQuietly(200);
					synchronized (LOCK_A) {
						// unreachable while the first thread holds LOCK_A
					}
				}
			}, "chaos-deadlock-2");
			first.setDaemon(true);
			second.setDaemon(true);
			first.start();
			second.start();
		}
	}

	@Override
	public boolean useRegressedOwnerQuery() {
		return this.state.isArmed(QUERY_PLAN_REGRESSION);
	}

	@Override
	public boolean shouldOomKill() {
		return this.state.isArmed(OOM_KILL);
	}

	@Override
	public void leakConnectionIfArmed() {
		if (this.state.isArmed(CONNECTION_POOL_EXHAUSTION)) {
			if (this.dataSource == null) {
				return;
			}
			// Reserve a slot atomically so at most leakCap connections are ever borrowed,
			// even under concurrent searches; this stops the borrow below from piling up
			// extra blocking getConnection() calls past the cap. Assumes arm/disarm is
			// quiescent: a disarm racing an in-flight borrow may briefly orphan one held
			// connection, but it stays tracked in `leaked` and is closed on the next
			// release — bounded and self-healing, never an accounting escape.
			if (this.leakedReservations.incrementAndGet() > this.leakCap) {
				this.leakedReservations.decrementAndGet();
				return;
			}
			try {
				// Seeded connection leak: borrow a pooled connection and never return it.
				// After leakCap borrows the HikariCP pool is drained, so a search's own
				// query blocks for connection-timeout and fails — the cause is the app
				// exhausting its own pool, localized by hikaricp_connections_*.
				this.leaked.add(this.dataSource.getConnection());
			}
			catch (SQLException ex) {
				// pool already drained — release the reservation, nothing was leaked
				this.leakedReservations.decrementAndGet();
			}
		}
		else {
			releaseLeaked();
		}
	}

	private void releaseLeaked() {
		if (this.leaked.isEmpty()) {
			return;
		}
		synchronized (this.leaked) {
			for (Connection connection : this.leaked) {
				try {
					connection.close();
				}
				catch (SQLException ex) {
					// best-effort release
				}
			}
			this.leaked.clear();
			this.leakedReservations.set(0);
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
