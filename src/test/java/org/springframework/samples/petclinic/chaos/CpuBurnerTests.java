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

class CpuBurnerTests {

	@Test
	void armedReconcileSpawnsBurnThreadsThatExitOnDisarm() throws InterruptedException {
		ChaosState state = new ChaosState();
		CpuBurner burner = new CpuBurner(state);
		burner.setThreads(2);
		state.arm(ActiveChaosFaults.CPU_THROTTLE);

		burner.reconcile();

		int count = 0;
		for (int i = 0; i < 100 && count < 2; i++) {
			count = cpuBurnThreadCount();
			if (count < 2) {
				Thread.sleep(20);
			}
		}
		assertThat(count).isGreaterThanOrEqualTo(2);

		// disarm: each burn thread observes the flag and returns
		state.disarm(ActiveChaosFaults.CPU_THROTTLE);
		for (int i = 0; i < 100 && cpuBurnThreadCount() > 0; i++) {
			Thread.sleep(20);
		}
		assertThat(cpuBurnThreadCount()).isZero();
	}

	@Test
	void disarmedReconcileSpawnsNothing() throws InterruptedException {
		ChaosState state = new ChaosState();
		CpuBurner burner = new CpuBurner(state);
		burner.setThreads(2);

		burner.reconcile(); // scenario never armed

		Thread.sleep(60);
		assertThat(cpuBurnThreadCount()).isZero();
	}

	private static int cpuBurnThreadCount() {
		return (int) Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter((t) -> t.getName().startsWith("chaos-cpu-burn"))
			.filter(Thread::isAlive)
			.count();
	}

}
