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

import java.lang.management.ThreadMXBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class DeadlockProbeTests {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	@Test
	void detectPublishesZeroWhenNoDeadlock() {
		ThreadMXBean mx = mock(ThreadMXBean.class);
		given(mx.findDeadlockedThreads()).willReturn(null);
		DeadlockProbe probe = new DeadlockProbe(this.registry, mx);

		assertThat(probe.detect()).isZero();
		assertThat(this.registry.get("petclinic.deadlocked.threads").gauge().value()).isZero();
	}

	@Test
	void detectPublishesCountWhenDeadlocked() {
		ThreadMXBean mx = mock(ThreadMXBean.class);
		given(mx.findDeadlockedThreads()).willReturn(new long[] { 11L, 22L });
		DeadlockProbe probe = new DeadlockProbe(this.registry, mx);

		assertThat(probe.detect()).isEqualTo(2);
		assertThat(this.registry.get("petclinic.deadlocked.threads").gauge().value()).isEqualTo(2.0);
	}

}
