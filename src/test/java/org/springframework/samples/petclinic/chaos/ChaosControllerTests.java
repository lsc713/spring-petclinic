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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChaosController.class)
@ActiveProfiles("chaos")
@Import(ChaosState.class)
class ChaosControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void armReportsScenarioArmed() throws Exception {
		this.mockMvc.perform(post("/chaos/ownerSearchNpe/arm"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ownerSearchNpe").value(true));
	}

	@Test
	void disarmReportsScenarioDisarmed() throws Exception {
		this.mockMvc.perform(post("/chaos/ownerSearchNpe/arm")).andExpect(status().isOk());
		this.mockMvc.perform(post("/chaos/ownerSearchNpe/disarm"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ownerSearchNpe").value(false));
	}

	@Test
	void statusReturnsOk() throws Exception {
		this.mockMvc.perform(get("/chaos/status")).andExpect(status().isOk());
	}

}
