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

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ContentionProbeTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	@Test
	void countsBlockedThreadsAndPublishesGauge() {
		ThreadMXBean mx = mock(ThreadMXBean.class);
		ThreadInfo blocked1 = threadInfo(Thread.State.BLOCKED);
		ThreadInfo blocked2 = threadInfo(Thread.State.BLOCKED);
		ThreadInfo runnable = threadInfo(Thread.State.RUNNABLE);
		given(mx.getAllThreadIds()).willReturn(new long[] { 1L, 2L, 3L });
		given(mx.getThreadInfo(any(long[].class))).willReturn(new ThreadInfo[] { blocked1, blocked2, runnable });
		ContentionProbe probe = new ContentionProbe(this.registry, mx);

		assertThat(probe.countBlocked()).isEqualTo(2);
		assertThat(this.registry.get("petclinic.blocked.threads").gauge().value()).isEqualTo(2.0);
	}

	@Test
	void publishesZeroWhenNoneBlocked() {
		ThreadMXBean mx = mock(ThreadMXBean.class);
		ThreadInfo runnable = threadInfo(Thread.State.RUNNABLE);
		given(mx.getAllThreadIds()).willReturn(new long[] { 1L });
		given(mx.getThreadInfo(any(long[].class))).willReturn(new ThreadInfo[] { runnable });
		ContentionProbe probe = new ContentionProbe(this.registry, mx);

		assertThat(probe.countBlocked()).isZero();
		assertThat(this.registry.get("petclinic.blocked.threads").gauge().value()).isZero();
	}

	private static ThreadInfo threadInfo(Thread.State state) {
		ThreadInfo info = mock(ThreadInfo.class);
		given(info.getThreadState()).willReturn(state);
		return info;
	}

}
