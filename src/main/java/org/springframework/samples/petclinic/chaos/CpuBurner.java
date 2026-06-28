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

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Burns CPU on demand for the CPU-throttling fault class. While the {@code cpuThrottle}
 * scenario is armed, a scheduled reconcile spawns a pool of busy-loop daemon threads that
 * demand far more CPU than the container's CPU limit allows, so the kernel CFS scheduler
 * throttles the container. The standard CPU-usage metric only shows usage pinned at the
 * limit (looks fine); the cause is visible solely in the cAdvisor CFS throttle metrics.
 * The threads self-terminate when the scenario is disarmed. Chaos profile only.
 */
@Component
@Profile("chaos")
public class CpuBurner {

	private final ChaosState state;

	private final List<Thread> burnThreads = new ArrayList<>();

	private static volatile double blackhole;

	@Value("${chaos.cpu.threads:4}")
	private int threads = 4;

	public CpuBurner(ChaosState state) {
		this.state = state;
	}

	void setThreads(int threads) {
		this.threads = threads;
	}

	/**
	 * Reconcile the burn workload with the armed state: while the scenario is armed,
	 * ensure a pool of burn threads is running (starting one if the previous threads have
	 * all exited); while disarmed, the threads stop themselves. Reconciling against
	 * actual thread liveness (rather than a one-shot flag) makes a disarm→re-arm cycle
	 * restart the burn correctly. A restart is gated on ALL prior threads having exited,
	 * so a re-arm while a few stragglers are still spinning leaves those survivors and
	 * skips the restart — harmless, since even one busy thread keeps the container over
	 * its CPU limit, so the fault stays active.
	 */
	@Scheduled(fixedRateString = "${chaos.cpu.interval-ms:2000}")
	void reconcile() {
		if (this.state.isArmed(ActiveChaosFaults.CPU_THROTTLE) && !anyBurnThreadAlive()) {
			startBurn();
		}
	}

	private boolean anyBurnThreadAlive() {
		for (Thread t : this.burnThreads) {
			if (t.isAlive()) {
				return true;
			}
		}
		return false;
	}

	private void startBurn() {
		this.burnThreads.clear();
		for (int i = 0; i < this.threads; i++) {
			Thread t = new Thread(this::burnUntilDisarmed, "chaos-cpu-burn-" + i);
			t.setDaemon(true);
			t.start();
			this.burnThreads.add(t);
		}
	}

	private void burnUntilDisarmed() {
		double sink = 0;
		while (this.state.isArmed(ActiveChaosFaults.CPU_THROTTLE)) {
			// Non-elidable arithmetic so the JIT cannot optimize the spin loop away.
			sink += Math.sqrt(sink * 1.0000001 + 1.0);
		}
		blackhole = sink;
	}

}
