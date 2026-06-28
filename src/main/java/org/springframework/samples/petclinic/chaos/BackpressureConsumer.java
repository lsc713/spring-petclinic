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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Deliberately-slow Kafka consumer for the queue-backpressure fault class. It processes
 * each record with a fixed delay, so when {@link BackpressureProducer} floods the topic
 * the consumer cannot keep up and Kafka consumer lag accumulates — the augmentation
 * signal that a synchronous request metric can never show. Chaos profile only.
 */
@Component
@Profile("chaos")
public class BackpressureConsumer {

	private long processDelayMs;

	public BackpressureConsumer(@Value("${chaos.queue.process-delay-ms:50}") long processDelayMs) {
		this.processDelayMs = processDelayMs;
	}

	void setProcessDelayMs(long processDelayMs) {
		this.processDelayMs = processDelayMs;
	}

	@KafkaListener(topics = "${chaos.queue.topic:petclinic.backpressure}",
			groupId = "${chaos.queue.group:petclinic-backpressure}")
	public void consume(String record) {
		sleepQuietly(this.processDelayMs);
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
