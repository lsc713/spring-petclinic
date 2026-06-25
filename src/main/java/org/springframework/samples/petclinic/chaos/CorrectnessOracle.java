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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Synthetic correctness probe for the silent-corruption fault class. Runs a known-answer
 * owner search through the same chaos seam the controller uses and increments
 * {@code petclinic.correctness.violations} when the expected owner is missing — the only
 * signal that surfaces a 200-but-wrong result. Chaos profile only.
 */
@Component
@Profile("chaos")
public class CorrectnessOracle {

	/** A last name guaranteed to exist in the seed data. */
	static final String KNOWN_LAST_NAME = "Davis";

	private final ChaosFaults chaosFaults;

	private final OwnerRepository owners;

	private final Counter violations;

	public CorrectnessOracle(ChaosFaults chaosFaults, OwnerRepository owners, MeterRegistry registry) {
		this.chaosFaults = chaosFaults;
		this.owners = owners;
		this.violations = Counter.builder("petclinic.correctness.violations")
			.description("Known-answer correctness probe failures")
			.tag("check", "ownerSearch")
			.register(registry);
	}

	/**
	 * Run the known-answer owner-search check once, routing the term through the chaos
	 * seam exactly as the controller does. Records a violation if the known owner is
	 * absent from the result.
	 */
	public void runOwnerSearchCheck() {
		String term = this.chaosFaults.corruptSearchTerm(KNOWN_LAST_NAME);
		Page<Owner> results = this.owners.findByLastNameStartingWith(term, PageRequest.of(0, 20));
		boolean found = false;
		for (Owner each : results) {
			if (KNOWN_LAST_NAME.equals(each.getLastName())) {
				found = true;
				break;
			}
		}
		if (!found) {
			this.violations.increment();
		}
	}

	@Scheduled(fixedRateString = "${chaos.oracle.interval-ms:5000}")
	void probe() {
		runOwnerSearchCheck();
	}

}
