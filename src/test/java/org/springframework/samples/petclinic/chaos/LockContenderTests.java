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

class LockContenderTests {

	@Test
	void disarmedContendReturnsImmediately() {
		ChaosState state = new ChaosState();
		LockContender contender = new LockContender(state);
		contender.setHoldMs(5000);

		long start = System.nanoTime();
		contender.contendIfArmed();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMs).isLessThan(100);
	}

	@Test
	void armedConcurrentCallersSerializeOnTheLock() throws InterruptedException {
		ChaosState state = new ChaosState();
		LockContender contender = new LockContender(state);
		contender.setHoldMs(150);
		state.arm(ActiveChaosFaults.LOCK_CONTENTION);

		// Two threads contend: the second cannot enter the monitor until the first
		// releases,
		// so the wall-clock is ~2x the hold. If the lock did NOT serialize them they
		// would
		// overlap and finish in ~1x the hold.
		Thread t1 = new Thread(contender::contendIfArmed);
		Thread t2 = new Thread(contender::contendIfArmed);
		long start = System.nanoTime();
		t1.start();
		t2.start();
		t1.join();
		t2.join();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMs).isGreaterThanOrEqualTo(250);
	}

}
