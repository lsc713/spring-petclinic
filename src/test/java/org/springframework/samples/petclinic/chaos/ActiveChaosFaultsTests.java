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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ActiveChaosFaultsTests {

	private final ChaosState state = new ChaosState();

	private final ActiveChaosFaults faults = new ActiveChaosFaults(this.state);

	@Test
	void disarmedPreservesProductionBehavior() {
		assertThat(this.faults.normalizeLastName(null)).isEqualTo("");
		assertThat(this.faults.normalizeLastName("Franklin")).isEqualTo("Franklin");
	}

	@Test
	void armedThrowsNpeOnNullSearchTerm() {
		this.state.arm(ActiveChaosFaults.OWNER_SEARCH_NPE);
		assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> this.faults.normalizeLastName(null));
	}

	@Test
	void armedStillReturnsNonNullTerms() {
		this.state.arm(ActiveChaosFaults.OWNER_SEARCH_NPE);
		assertThat(this.faults.normalizeLastName("Franklin")).isEqualTo("Franklin");
	}

}
