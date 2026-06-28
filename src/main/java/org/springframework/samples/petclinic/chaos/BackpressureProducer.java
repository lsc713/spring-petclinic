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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Floods a Kafka topic while the {@code queueBackpressure} scenario is armed. A scheduled
 * reconcile sends a burst of records each tick, so the producer outpaces the slow
 * {@link BackpressureConsumer} and consumer lag accumulates. Standard request/error
 * metrics stay silent (the producing path returns immediately); only consumer lag reveals
 * the backlog. Chaos profile only.
 */
@Component
@Profile("chaos")
public class BackpressureProducer {

	private final KafkaTemplate<String, String> kafkaTemplate;

	private final ChaosState state;

	@Value("${chaos.queue.topic:petclinic.backpressure}")
	private String topic = "petclinic.backpressure";

	@Value("${chaos.queue.burst:200}")
	private int burst = 200;

	public BackpressureProducer(KafkaTemplate<String, String> kafkaTemplate, ChaosState state) {
		this.kafkaTemplate = kafkaTemplate;
		this.state = state;
	}

	void setTopic(String topic) {
		this.topic = topic;
	}

	void setBurst(int burst) {
		this.burst = burst;
	}

	/**
	 * While {@code queueBackpressure} is armed, send a burst of records so the producer
	 * outpaces the slow consumer and Kafka consumer lag accumulates.
	 */
	@Scheduled(fixedRateString = "${chaos.queue.interval-ms:2000}")
	void reconcile() {
		if (this.state.isArmed(ActiveChaosFaults.QUEUE_BACKPRESSURE)) {
			for (int i = 0; i < this.burst; i++) {
				this.kafkaTemplate.send(this.topic, "backpressure-" + i);
			}
		}
	}

}
