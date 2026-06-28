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
 * Drives the JVM into a GC death spiral for the GC-thrashing fault class. While the
 * {@code gcThrashing} scenario is armed, a scheduled reconcile starts an allocator thread
 * that retains {@code retain-mb} of a (bounded) heap and then churns short-lived garbage
 * on top, so GC runs constantly but reclaims little. Request latency spikes, but it is
 * not the DB, a downstream call, a deadlock (threads are RUNNABLE), or a CPU-limit cap;
 * only the JVM GC-pause metrics reveal the cause. The thread self-terminates and releases
 * the retained set when the scenario is disarmed. Chaos profile only.
 */
@Component
@Profile("chaos")
public class GcPressure {

	private final ChaosState state;

	private final List<Thread> allocatorThreads = new ArrayList<>();

	@Value("${chaos.gc.retain-mb:150}")
	private int retainMb = 150;

	@Value("${chaos.gc.chunk-kb:64}")
	private int chunkKb = 64;

	public GcPressure(ChaosState state) {
		this.state = state;
	}

	void setRetainMb(int retainMb) {
		this.retainMb = retainMb;
	}

	void setChunkKb(int chunkKb) {
		this.chunkKb = chunkKb;
	}

	/**
	 * While the scenario is armed, ensure an allocator thread is running (started when
	 * the previous one has exited); while disarmed, it stops itself. Reconciling against
	 * actual thread liveness (not a one-shot flag) makes a disarm→re-arm cycle restart
	 * correctly.
	 */
	@Scheduled(fixedRateString = "${chaos.gc.interval-ms:2000}")
	void reconcile() {
		if (this.state.isArmed(ActiveChaosFaults.GC_THRASHING) && !anyAllocatorAlive()) {
			startAllocator();
		}
	}

	private boolean anyAllocatorAlive() {
		for (Thread t : this.allocatorThreads) {
			if (t.isAlive()) {
				return true;
			}
		}
		return false;
	}

	private void startAllocator() {
		this.allocatorThreads.clear();
		Thread t = new Thread(this::retainAndChurnUntilDisarmed, "chaos-gc-pressure");
		t.setDaemon(true);
		t.start();
		this.allocatorThreads.add(t);
	}

	private void retainAndChurnUntilDisarmed() {
		int chunkBytes = this.chunkKb * 1024;
		long retainBytes = (long) this.retainMb * 1024 * 1024;
		List<byte[]> retained = new ArrayList<>();
		// Phase 1: retain ~retainMb of long-lived chunks to pressure the bounded heap.
		long held = 0;
		while (held < retainBytes && this.state.isArmed(ActiveChaosFaults.GC_THRASHING)) {
			byte[] chunk = new byte[chunkBytes];
			chunk[0] = 1;
			retained.add(chunk);
			held += chunkBytes;
		}
		// Phase 2: churn short-lived garbage so GC runs constantly against the full heap.
		while (this.state.isArmed(ActiveChaosFaults.GC_THRASHING)) {
			byte[] garbage = new byte[chunkBytes];
			garbage[0] = 1; // touch so the JIT cannot elide the allocation
		}
		retained.clear(); // disarmed: release so the heap recovers
	}

}
