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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheStampedeTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private double inflightPeak() {
		return this.registry.get("petclinic.cache.recompute.inflight").gauge().value();
	}

	private double recomputeTotal() {
		return this.registry.get("petclinic.cache.recompute.total").counter().count();
	}

	@Test
	void armedConcurrentCallsHerdOnTheSameRecompute() throws Exception {
		ChaosState state = new ChaosState();
		state.arm(ActiveChaosFaults.CACHE_STAMPEDE);
		CacheStampede stampede = new CacheStampede(state, this.registry);
		stampede.setRecomputeMs(400);
		stampede.setTtlMs(2000);

		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					start.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
				stampede.getOrLoadIfArmed();
			});
		}
		start.countDown();

		double peak = 0;
		for (int i = 0; i < 25; i++) {
			peak = Math.max(peak, inflightPeak());
			Thread.sleep(20);
		}
		pool.shutdown();
		assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		// no single-flight guard, so the 4 callers recompute the same value at once
		assertThat(peak).isGreaterThanOrEqualTo(2.0);
		assertThat(recomputeTotal()).isGreaterThanOrEqualTo(2.0);
		assertThat(inflightPeak()).isZero();
	}

	@Test
	void armedSecondCallWithinTtlHitsCacheAndDoesNotRecompute() {
		ChaosState state = new ChaosState();
		state.arm(ActiveChaosFaults.CACHE_STAMPEDE);
		CacheStampede stampede = new CacheStampede(state, this.registry);
		stampede.setRecomputeMs(0);
		stampede.setTtlMs(60000);

		stampede.getOrLoadIfArmed();
		stampede.getOrLoadIfArmed();

		// first call recomputed and cached; second is a hit within the TTL
		assertThat(recomputeTotal()).isEqualTo(1.0);
	}

	@Test
	void disarmedDoesNotRecompute() {
		ChaosState state = new ChaosState();
		CacheStampede stampede = new CacheStampede(state, this.registry);
		stampede.setRecomputeMs(0);

		stampede.getOrLoadIfArmed();

		assertThat(recomputeTotal()).isZero();
		assertThat(inflightPeak()).isZero();
	}

}
