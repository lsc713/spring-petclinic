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

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default fault seam — a no-op preserving production behavior. Registered whenever the
 * {@code chaos} profile is NOT active.
 */
@Component
@Profile("!chaos")
public class NoOpChaosFaults implements ChaosFaults {

	@Override
	public String normalizeLastName(String lastName) {
		return lastName == null ? "" : lastName;
	}

	@Override
	public boolean amplifyOwnerReads() {
		return false;
	}

	@Override
	public void maybeFailVetList() {
	}

	@Override
	public String corruptSearchTerm(String lastName) {
		return lastName;
	}

}
