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

package org.springframework.samples.petclinic.vet;

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
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VetController.class)
@ActiveProfiles("chaos")
@Import({ ActiveChaosFaults.class, ChaosState.class })
class VetListChaosTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChaosState chaosState;

	@MockitoBean
	private VetRepository vets;

	@BeforeEach
	void reset() {
		this.chaosState.disarm(ActiveChaosFaults.VET_LIST_ERROR);
		given(this.vets.findAll(any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
	}

	@Test
	void armedVetListReturnsServerError() throws Exception {
		this.chaosState.arm(ActiveChaosFaults.VET_LIST_ERROR);
		this.mockMvc.perform(get("/vets.html")).andExpect(status().is5xxServerError());
	}

	@Test
	void disarmedVetListIsOk() throws Exception {
		this.mockMvc.perform(get("/vets.html")).andExpect(status().isOk());
	}

}
