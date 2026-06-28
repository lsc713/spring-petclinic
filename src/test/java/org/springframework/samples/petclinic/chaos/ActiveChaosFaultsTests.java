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

import java.lang.Thread.State;
import java.net.ConnectException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveChaosFaultsTests {

	private final ChaosState state = new ChaosState();

	private final ActiveChaosFaults faults = new ActiveChaosFaults(this.state);

	@Test
	void disarmedPreservesProductionBehavior() {
		assertThat(this.faults.normalizeLastName(null)).isEqualTo("");
		assertThat(this.faults.normalizeLastName("Franklin")).isEqualTo("Franklin");
	}

	@Test
	void armedThrowsNpeOnNullSearchTerm() {
		this.state.arm(ActiveChaosFaults.OWNER_SEARCH_NPE);
		assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> this.faults.normalizeLastName(null));
	}

	@Test
	void armedStillReturnsNonNullTerms() {
		this.state.arm(ActiveChaosFaults.OWNER_SEARCH_NPE);
		assertThat(this.faults.normalizeLastName("Franklin")).isEqualTo("Franklin");
	}

	@Test
	void disarmedDbDownIsReachable() {
		// no scenario armed: the guard must return normally
		this.faults.assertDatabaseReachable();
	}

	@Test
	void armedDbDownThrowsInfraTypedFailure() {
		this.state.arm(ActiveChaosFaults.DB_DOWN);
		// infrastructure-typed (DataAccessResourceFailureException) with a
		// ConnectException cause — NOT a localizable app NullPointerException
		assertThatExceptionOfType(DataAccessResourceFailureException.class)
			.isThrownBy(() -> this.faults.assertDatabaseReachable())
			.withCauseInstanceOf(ConnectException.class);
	}

	@Test
	void armedThreadStarvationBlocksWorker() {
		this.faults.setThreadBlockMs(150);
		this.state.arm(ActiveChaosFaults.THREAD_STARVATION);
		long start = System.nanoTime();
		this.faults.maybeBlockWorker();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
	}

	@Test
	void disarmedThreadStarvationDoesNotBlock() {
		this.faults.setThreadBlockMs(5000);
		long start = System.nanoTime();
		this.faults.maybeBlockWorker();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		assertThat(elapsedMs).isLessThan(100);
	}

	@Test
	void armedDeadlockBlocksTwoNamedThreads() throws InterruptedException {
		this.state.arm(ActiveChaosFaults.DEADLOCK);
		this.faults.triggerDeadlock();

		// The two daemon threads must reach BLOCKED state on each other's monitor.
		int blocked = 0;
		for (int i = 0; i < 50 && blocked < 2; i++) {
			blocked = 0;
			for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
				Thread t = e.getKey();
				if (t.getName().startsWith("chaos-deadlock") && t.getState() == State.BLOCKED) {
					blocked++;
				}
			}
			if (blocked < 2) {
				Thread.sleep(100);
			}
		}
		assertThat(blocked).isGreaterThanOrEqualTo(2);
	}

	@Test
	void disarmedDeadlockSpawnsNoThreads() throws InterruptedException {
		long before = chaosDeadlockThreadCount();
		this.faults.triggerDeadlock();
		Thread.sleep(200);
		assertThat(chaosDeadlockThreadCount()).isEqualTo(before);
	}

	private static long chaosDeadlockThreadCount() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(t -> t.getName().startsWith("chaos-deadlock"))
			.count();
	}

	@Test
	void armedQueryPlanRegressionRoutesToRegressedQuery() {
		this.state.arm(ActiveChaosFaults.QUERY_PLAN_REGRESSION);
		assertThat(this.faults.useRegressedOwnerQuery()).isTrue();
	}

	@Test
	void disarmedQueryPlanRegressionUsesIndexedQuery() {
		assertThat(this.faults.useRegressedOwnerQuery()).isFalse();
	}

	@Test
	void shouldOomKillIsTrueOnlyWhenArmed() {
		ChaosState state = new ChaosState();
		ActiveChaosFaults faults = new ActiveChaosFaults(state);
		assertThat(faults.shouldOomKill()).isFalse();
		state.arm(ActiveChaosFaults.OOM_KILL);
		assertThat(faults.shouldOomKill()).isTrue();
	}

	@Test
	void armedConnectionPoolExhaustionLeaksUpToCap() throws SQLException {
		ChaosState state = new ChaosState();
		ActiveChaosFaults faults = new ActiveChaosFaults(state);
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenAnswer((invocation) -> mock(Connection.class));
		faults.setDataSource(dataSource);
		faults.setLeakCap(3);
		state.arm(ActiveChaosFaults.CONNECTION_POOL_EXHAUSTION);

		for (int i = 0; i < 5; i++) {
			faults.leakConnectionIfArmed();
		}

		// borrows stop at the cap (3), even though the hook is called 5 times
		verify(dataSource, times(3)).getConnection();
	}

	@Test
	void disarmedConnectionPoolExhaustionReleasesHeldConnections() throws SQLException {
		ChaosState state = new ChaosState();
		ActiveChaosFaults faults = new ActiveChaosFaults(state);
		DataSource dataSource = mock(DataSource.class);
		Connection first = mock(Connection.class);
		Connection second = mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(first, second);
		faults.setDataSource(dataSource);
		faults.setLeakCap(5);

		state.arm(ActiveChaosFaults.CONNECTION_POOL_EXHAUSTION);
		faults.leakConnectionIfArmed();
		faults.leakConnectionIfArmed();

		state.disarm(ActiveChaosFaults.CONNECTION_POOL_EXHAUSTION);
		faults.leakConnectionIfArmed(); // disarmed branch releases held connections

		verify(first).close();
		verify(second).close();
	}

	@Test
	void disarmedConnectionPoolExhaustionNeverBorrows() throws SQLException {
		ChaosState state = new ChaosState();
		ActiveChaosFaults faults = new ActiveChaosFaults(state);
		DataSource dataSource = mock(DataSource.class);
		faults.setDataSource(dataSource);

		faults.leakConnectionIfArmed(); // scenario never armed

		verify(dataSource, never()).getConnection();
	}

	@Test
	void armedConnectionPoolExhaustionWithoutDataSourceIsNoOp() {
		ChaosState state = new ChaosState();
		ActiveChaosFaults faults = new ActiveChaosFaults(state);
		// no setDataSource() call → dataSource is null
		state.arm(ActiveChaosFaults.CONNECTION_POOL_EXHAUSTION);
		faults.leakConnectionIfArmed(); // must not throw (null DataSource is a no-op)
	}

}
