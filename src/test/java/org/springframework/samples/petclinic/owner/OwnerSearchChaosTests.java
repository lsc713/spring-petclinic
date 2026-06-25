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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(OwnerController.class)
@ActiveProfiles("chaos")
@Import({ ActiveChaosFaults.class, ChaosState.class })
class OwnerSearchChaosTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChaosState chaosState;

	@MockitoBean
	private OwnerRepository owners;

	@BeforeEach
	void resetChaosState() {
		this.chaosState.disarm(ActiveChaosFaults.OWNER_SEARCH_NPE);
	}

	@Test
	void armedOwnerSearchThrowsNpe() throws Exception {
		given(this.owners.findByLastNameStartingWith(anyString(), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of()));
		this.chaosState.arm(ActiveChaosFaults.OWNER_SEARCH_NPE);

		// Servlet dispatch wraps the handler NPE; assert the dispatch raised it.
		try {
			this.mockMvc.perform(get("/owners?page=1"));
		}
		catch (Exception expected) {
			return; // NPE propagated through the dispatcher — Class A defect surfaced
		}
		throw new AssertionError("expected the armed owner search to throw");
	}

	@Test
	void disarmedOwnerSearchBehavesNormally() throws Exception {
		Owner george = new Owner();
		george.setId(1);
		george.setFirstName("George");
		george.setLastName("Franklin");
		given(this.owners.findByLastNameStartingWith(anyString(), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(george)));

		this.mockMvc.perform(get("/owners?page=1"));
		// no exception: disarmed chaos == production behavior
	}

}
