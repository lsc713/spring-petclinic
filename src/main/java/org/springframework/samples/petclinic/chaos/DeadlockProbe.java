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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled probe for the deadlock fault class. Standard latency/error metrics never
 * reveal a Java-level deadlock; this probe asks the JVM directly via {@link ThreadMXBean}
 * and publishes {@code petclinic.deadlocked.threads}, the only metric that surfaces a
 * deadlock's existence. The thread dump then localizes which threads/monitors are stuck.
 * Chaos profile only.
 */
@Component
@Profile("chaos")
public class DeadlockProbe {

	private final ThreadMXBean threadMxBean;

	private final AtomicInteger deadlockedThreads = new AtomicInteger(0);

	public DeadlockProbe(MeterRegistry registry) {
		this(registry, ManagementFactory.getThreadMXBean());
	}

	DeadlockProbe(MeterRegistry registry, ThreadMXBean threadMxBean) {
		this.threadMxBean = threadMxBean;
		Gauge.builder("petclinic.deadlocked.threads", this.deadlockedThreads, AtomicInteger::get)
			.description("Count of JVM-detected deadlocked threads")
			.register(registry);
	}

	/**
	 * Ask the JVM for deadlocked threads, update the gauge, and return the count.
	 * @return the number of threads currently in a deadlock cycle (0 if none)
	 */
	public int detect() {
		long[] ids = this.threadMxBean.findDeadlockedThreads();
		int count = (ids == null) ? 0 : ids.length;
		this.deadlockedThreads.set(count);
		return count;
	}

	@Scheduled(fixedRateString = "${chaos.deadlock.interval-ms:5000}")
	void probe() {
		detect();
	}

}
