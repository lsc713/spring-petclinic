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
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Holds the armed/disarmed state of each chaos scenario. Inert unless a scenario is
 * explicitly armed via {@link ChaosController}.
 */
@Component
public class ChaosState {

	private final Map<String, Boolean> armed = new ConcurrentHashMap<>();

	public void arm(String scenario) {
		this.armed.put(scenario, Boolean.TRUE);
	}

	public void disarm(String scenario) {
		this.armed.put(scenario, Boolean.FALSE);
	}

	public boolean isArmed(String scenario) {
		return this.armed.getOrDefault(scenario, Boolean.FALSE);
	}

	public Map<String, Boolean> status() {
		return Map.copyOf(this.armed);
	}

}
