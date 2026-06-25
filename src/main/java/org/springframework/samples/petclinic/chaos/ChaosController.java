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

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime arm/disarm control plane for chaos scenarios. Registered only under the
 * {@code chaos} profile, so it is absent (404) in normal runs.
 */
@RestController
@RequestMapping("/chaos")
@Profile("chaos")
class ChaosController {

	private final ChaosState state;

	ChaosController(ChaosState state) {
		this.state = state;
	}

	@PostMapping("/{scenario}/arm")
	Map<String, Boolean> arm(@PathVariable String scenario) {
		this.state.arm(scenario);
		return this.state.status();
	}

	@PostMapping("/{scenario}/disarm")
	Map<String, Boolean> disarm(@PathVariable String scenario) {
		this.state.disarm(scenario);
		return this.state.status();
	}

	@GetMapping("/status")
	Map<String, Boolean> status() {
		return this.state.status();
	}

}
