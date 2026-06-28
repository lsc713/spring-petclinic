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
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled probe for the lock-contention fault class. Standard latency metrics only say
 * the owner search is slow; this probe asks the JVM via {@link ThreadMXBean} how many
 * threads are {@link Thread.State#BLOCKED} (waiting to enter a monitor) and publishes
 * {@code petclinic.blocked.threads}. A high count with
 * {@code petclinic.deadlocked.threads} at 0 means hot-lock contention, not a deadlock —
 * the thread dump then localizes the monitor. Chaos profile only.
 */
@Component
@Profile("chaos")
public class ContentionProbe {

	private final ThreadMXBean threadMxBean;

	private final AtomicInteger blockedThreads = new AtomicInteger(0);

	@Autowired
	public ContentionProbe(MeterRegistry registry) {
		this(registry, ManagementFactory.getThreadMXBean());
	}

	ContentionProbe(MeterRegistry registry, ThreadMXBean threadMxBean) {
		this.threadMxBean = threadMxBean;
		Gauge.builder("petclinic.blocked.threads", this.blockedThreads, AtomicInteger::get)
			.description("Count of threads BLOCKED waiting to enter a monitor (lock contention)")
			.register(registry);
	}

	/**
	 * Count threads currently BLOCKED waiting to enter a monitor, update the gauge, and
	 * return the count.
	 * @return the number of BLOCKED threads (0 if none)
	 */
	public int countBlocked() {
		ThreadInfo[] infos = this.threadMxBean.getThreadInfo(this.threadMxBean.getAllThreadIds());
		int count = 0;
		for (ThreadInfo info : infos) {
			if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
				count++;
			}
		}
		this.blockedThreads.set(count);
		return count;
	}

	@Scheduled(fixedRateString = "${chaos.contention.interval-ms:2000}")
	void probe() {
		countBlocked();
	}

}
