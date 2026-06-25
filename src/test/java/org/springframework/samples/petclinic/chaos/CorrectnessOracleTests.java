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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CorrectnessOracleTests {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final ChaosFaults faults = mock(ChaosFaults.class);

	private final OwnerRepository owners = mock(OwnerRepository.class);

	private final CorrectnessOracle oracle = new CorrectnessOracle(this.faults, this.owners, this.registry);

	private PageImpl<Owner> davisPage() {
		Owner betty = new Owner();
		betty.setId(2);
		betty.setFirstName("Betty");
		betty.setLastName("Davis");
		return new PageImpl<>(List.of(betty));
	}

	private double violations() {
		return this.registry.get("petclinic.correctness.violations").tag("check", "ownerSearch").counter().count();
	}

	@Test
	void noViolationWhenKnownOwnerIsPresent() {
		given(this.faults.corruptSearchTerm("Davis")).willReturn("Davis");
		given(this.owners.findByLastNameStartingWith(eq("Davis"), any(Pageable.class))).willReturn(davisPage());

		this.oracle.runOwnerSearchCheck();

		assertThat(violations()).isZero();
	}

	@Test
	void violationRecordedWhenCorruptionHidesTheKnownOwner() {
		given(this.faults.corruptSearchTerm("Davis")).willReturn("__chaos_nomatch__");
		given(this.owners.findByLastNameStartingWith(eq("__chaos_nomatch__"), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of()));

		this.oracle.runOwnerSearchCheck();

		assertThat(violations()).isEqualTo(1.0);
	}

}
