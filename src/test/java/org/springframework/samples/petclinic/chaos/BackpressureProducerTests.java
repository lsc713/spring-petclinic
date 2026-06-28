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
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BackpressureProducerTests {

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

	private final ChaosState state = new ChaosState();

	private final BackpressureProducer producer = new BackpressureProducer(this.template, this.state);

	@Test
	void armedReconcileSendsABurst() {
		this.producer.setTopic("t");
		this.producer.setBurst(10);
		this.state.arm(ActiveChaosFaults.QUEUE_BACKPRESSURE);

		this.producer.reconcile();

		verify(this.template, times(10)).send(eq("t"), anyString());
	}

	@Test
	void disarmedReconcileSendsNothing() {
		this.producer.setBurst(10);

		this.producer.reconcile(); // scenario never armed

		verify(this.template, never()).send(anyString(), anyString());
	}

}
