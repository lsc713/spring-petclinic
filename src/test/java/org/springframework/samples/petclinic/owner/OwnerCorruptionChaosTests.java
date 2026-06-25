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
package org.springframework.samples.petclinic.owner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.samples.petclinic.chaos.ActiveChaosFaults;
import org.springframework.samples.petclinic.chaos.ChaosState;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerController.class)
@ActiveProfiles("chaos")
@Import({ ActiveChaosFaults.class, ChaosState.class })
class OwnerCorruptionChaosTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChaosState chaosState;

	@MockitoBean
	private OwnerRepository owners;

	@BeforeEach
	void reset() {
		this.chaosState.disarm(ActiveChaosFaults.OWNER_SEARCH_CORRUPTION);
	}

	private PageImpl<Owner> davis() {
		Owner betty = new Owner();
		betty.setId(2);
		betty.setFirstName("Betty");
		betty.setLastName("Davis");
		return new PageImpl<>(List.of(betty));
	}

	@Test
	void armedCorruptionQueriesSentinelTermAndReturns200() throws Exception {
		// real term would return Betty Davis; sentinel term returns empty
		given(this.owners.findByLastNameStartingWith(eq("Davis"), any(Pageable.class))).willReturn(davis());
		given(this.owners.findByLastNameStartingWith(eq("__chaos_nomatch__"), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of()));
		this.chaosState.arm(ActiveChaosFaults.OWNER_SEARCH_CORRUPTION);

		this.mockMvc.perform(get("/owners?lastName=Davis")).andExpect(status().isOk());

		// the corruption silently swapped the query term — no exception, no 5xx
		verify(this.owners).findByLastNameStartingWith(eq("__chaos_nomatch__"), any(Pageable.class));
	}

	@Test
	void disarmedUsesTheRealTerm() throws Exception {
		given(this.owners.findByLastNameStartingWith(eq("Davis"), any(Pageable.class))).willReturn(davis());

		this.mockMvc.perform(get("/owners?lastName=Davis"));

		verify(this.owners).findByLastNameStartingWith(eq("Davis"), any(Pageable.class));
	}

}
