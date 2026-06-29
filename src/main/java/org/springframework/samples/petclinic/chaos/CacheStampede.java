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

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cache-stampede fault and probe (chaos profile only). Holds a self-contained
 * single-entry cache with a short TTL and NO single-flight guard. While
 * {@code cacheStampede} is armed, concurrent callers that arrive in the miss window
 * (after the entry expires) all enter the expensive recompute at once — a herd.
 * {@code petclinic.cache.recompute.inflight} rises to the concurrency level, which names
 * the stampede where a cache-miss count cannot: it shows how many threads recompute the
 * SAME value concurrently because the cache is not protecting the backend. The real
 * {@code vets} cache is untouched.
 */
@Component
@Profile("chaos")
public class CacheStampede {

	private final ChaosState state;

	private final AtomicInteger inflight = new AtomicInteger(0);

	private final Counter recomputeTotal;

	@Value("${chaos.cache.ttl-ms:1000}")
	private long ttlMs = 1000;

	@Value("${chaos.cache.recompute-ms:300}")
	private long recomputeMs = 300;

	private volatile Object value;

	private volatile long expiresAtMillis;

	public CacheStampede(ChaosState state, MeterRegistry registry) {
		this.state = state;
		Gauge.builder("petclinic.cache.recompute.inflight", this.inflight, AtomicInteger::get)
			.description("Threads concurrently recomputing the same cached value (stampede herd)")
			.register(registry);
		this.recomputeTotal = Counter.builder("petclinic.cache.recompute.total")
			.description("Cumulative cache-value recomputes (high under stampede)")
			.register(registry);
	}

	void setTtlMs(long ttlMs) {
		this.ttlMs = ttlMs;
	}

	void setRecomputeMs(long recomputeMs) {
		this.recomputeMs = recomputeMs;
	}

	/**
	 * When armed, serve the single-entry cache; on a miss, recompute WITHOUT a
	 * single-flight guard so concurrent callers in the miss window all recompute at once
	 * (the stampede).
	 */
	public void getOrLoadIfArmed() {
		if (!this.state.isArmed(ActiveChaosFaults.CACHE_STAMPEDE)) {
			return;
		}
		if (this.value != null && System.currentTimeMillis() < this.expiresAtMillis) {
			// cache hit — the entry is still valid
			return;
		}
		// MISS, no single-flight guard: every caller in the miss window enters here
		// together,
		// so inflight rises to the concurrency level — the herd hammering the backend.
		this.inflight.incrementAndGet();
		try {
			this.recomputeTotal.increment();
			sleepQuietly(this.recomputeMs);
			this.value = new Object();
			this.expiresAtMillis = System.currentTimeMillis() + this.ttlMs;
		}
		finally {
			this.inflight.decrementAndGet();
		}
	}

	private static void sleepQuietly(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
