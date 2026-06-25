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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(OwnerController.class)
@ActiveProfiles("chaos")
@Import({ ActiveChaosFaults.class, ChaosState.class })
class OwnerLatencyChaosTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChaosState chaosState;

	@MockitoBean
	private OwnerRepository owners;

	@BeforeEach
	void reset() {
		this.chaosState.disarm(ActiveChaosFaults.OWNER_LIST_LATENCY);
	}

	private PageImpl<Owner> threeOwners() {
		Owner a = new Owner();
		a.setId(1);
		a.setLastName("A");
		Owner b = new Owner();
		b.setId(2);
		b.setLastName("B");
		Owner c = new Owner();
		c.setId(3);
		c.setLastName("C");
		return new PageImpl<>(List.of(a, b, c));
	}

	@Test
	void armedLatencyIssuesOneExtraQueryPerOwner() throws Exception {
		given(this.owners.findByLastNameStartingWith(anyString(), any(Pageable.class))).willReturn(threeOwners());
		this.chaosState.arm(ActiveChaosFaults.OWNER_LIST_LATENCY);

		this.mockMvc.perform(get("/owners?lastName="));

		// N+1: one findById per owner in the page
		verify(this.owners, times(3)).findById(anyInt());
	}

	@Test
	void disarmedIssuesNoExtraQueries() throws Exception {
		given(this.owners.findByLastNameStartingWith(anyString(), any(Pageable.class))).willReturn(threeOwners());

		this.mockMvc.perform(get("/owners?lastName="));

		verify(this.owners, never()).findById(anyInt());
	}

}
