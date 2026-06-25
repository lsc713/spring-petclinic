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

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fault seam active under the {@code chaos} profile. Applies a fault only while its
 * scenario is armed; otherwise preserves production behavior.
 */
@Component
@Profile("chaos")
public class ActiveChaosFaults implements ChaosFaults {

	/** Scenario key: Class A synchronous NPE on owner search. */
	public static final String OWNER_SEARCH_NPE = "ownerSearchNpe";

	/** Scenario key: latency fault — N+1 query amplification on owner search. */
	public static final String OWNER_LIST_LATENCY = "ownerListLatency";

	private final ChaosState state;

	public ActiveChaosFaults(ChaosState state) {
		this.state = state;
	}

	@Override
	public String normalizeLastName(String lastName) {
		if (this.state.isArmed(OWNER_SEARCH_NPE)) {
			// Seeded defect (Class A): the null guard that production has is
			// absent here, so a search with no last name throws NPE.
			return lastName.trim();
		}
		return lastName == null ? "" : lastName;
	}

	@Override
	public boolean amplifyOwnerReads() {
		return this.state.isArmed(OWNER_LIST_LATENCY);
	}

}
