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

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.samples.petclinic.chaos.ActiveChaosFaults;
import org.springframework.samples.petclinic.chaos.ChaosState;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OwnerController.class)
@ActiveProfiles("chaos")
@Import({ ActiveChaosFaults.class, ChaosState.class })
class OwnerDbDownChaosTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChaosState chaosState;

	@MockitoBean
	private OwnerRepository owners;

	@BeforeEach
	void resetChaosState() {
		this.chaosState.disarm(ActiveChaosFaults.DB_DOWN);
		Owner george = new Owner();
		george.setId(1);
		george.setFirstName("George");
		george.setLastName("Franklin");
		// findById is hit by the @ModelAttribute resolver before showOwner runs
		given(this.owners.findById(anyInt())).willReturn(Optional.of(george));
	}

	@Test
	void armedDbDownRaisesInfraTypedFailureNotNpe() {
		this.chaosState.arm(ActiveChaosFaults.DB_DOWN);

		// Servlet dispatch wraps the handler exception, so the DIRECT cause is the
		// infrastructure-typed DataAccessResourceFailureException — the A13 discriminator
		// (a 5xx with a stack trace that is NOT a localizable app NullPointerException).
		// Assert the direct cause, not the root cause: the root is the nested
		// ConnectException, so hasRootCauseInstanceOf(...) would never match here.
		assertThatThrownBy(() -> this.mockMvc.perform(get("/owners/1")))
			.hasCauseInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	void disarmedDbDownServesOwnerDetails() throws Exception {
		// disarmed chaos == production behavior: owner-details served (200), seam never
		// throws
		this.mockMvc.perform(get("/owners/1")).andExpect(status().isOk()).andExpect(view().name("owners/ownerDetails"));
	}

}
