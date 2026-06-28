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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Hot-lock contention fault. While the {@code lockContention} scenario is armed, every
 * owner search enters one shared monitor and holds it for a fixed time, so under
 * concurrent load the requests serialize and the waiting threads pile up in
 * {@link Thread.State#BLOCKED}. This is NOT a deadlock — there is no cycle, so
 * {@code findDeadlockedThreads()} reports nothing; the bottleneck is the single hot lock.
 * Chaos profile only.
 */
@Component
@Profile("chaos")
public class LockContender {

	private static final Object HOT_LOCK = new Object();

	private final ChaosState state;

	@Value("${chaos.contention.hold-ms:200}")
	private long holdMs = 200;

	public LockContender(ChaosState state) {
		this.state = state;
	}

	void setHoldMs(long holdMs) {
		this.holdMs = holdMs;
	}

	/**
	 * When {@code lockContention} is armed, enter the shared monitor and hold it for
	 * {@code holdMs}; otherwise return immediately. Concurrent armed callers serialize
	 * here.
	 */
	public void contendIfArmed() {
		if (this.state.isArmed(ActiveChaosFaults.LOCK_CONTENTION)) {
			synchronized (HOT_LOCK) {
				sleepQuietly(this.holdMs);
			}
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
