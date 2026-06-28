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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcPressureTests {

	// A tiny retention so the test JVM never actually thrashes.
	private GcPressure newPressure(ChaosState state) {
		GcPressure pressure = new GcPressure(state);
		pressure.setRetainMb(1);
		pressure.setChunkKb(8);
		return pressure;
	}

	@Test
	void armedReconcileSpawnsAllocatorThatExitsOnDisarm() throws InterruptedException {
		ChaosState state = new ChaosState();
		GcPressure pressure = newPressure(state);
		state.arm(ActiveChaosFaults.GC_THRASHING);

		pressure.reconcile();
		waitForAllocator(1);

		// disarm: the allocator observes the flag and returns
		state.disarm(ActiveChaosFaults.GC_THRASHING);
		waitForAllocator(0);
	}

	@Test
	void disarmedReconcileSpawnsNothing() throws InterruptedException {
		ChaosState state = new ChaosState();
		GcPressure pressure = newPressure(state);

		pressure.reconcile(); // scenario never armed

		Thread.sleep(60);
		assertThat(gcPressureThreadCount()).isZero();
	}

	@Test
	void rearmAfterDisarmRestartsAllocator() throws InterruptedException {
		ChaosState state = new ChaosState();
		GcPressure pressure = newPressure(state);

		state.arm(ActiveChaosFaults.GC_THRASHING);
		pressure.reconcile();
		waitForAllocator(1);

		state.disarm(ActiveChaosFaults.GC_THRASHING);
		waitForAllocator(0);

		// re-arm + reconcile must restart the allocator (liveness-gated restart)
		state.arm(ActiveChaosFaults.GC_THRASHING);
		pressure.reconcile();
		waitForAllocator(1);

		state.disarm(ActiveChaosFaults.GC_THRASHING);
		waitForAllocator(0);
	}

	private static void waitForAllocator(int target) throws InterruptedException {
		for (int i = 0; i < 100 && gcPressureThreadCount() != target; i++) {
			Thread.sleep(20);
		}
		assertThat(gcPressureThreadCount()).isEqualTo(target);
	}

	private static int gcPressureThreadCount() {
		return (int) Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter((t) -> t.getName().startsWith("chaos-gc-pressure"))
			.filter(Thread::isAlive)
			.count();
	}

}
