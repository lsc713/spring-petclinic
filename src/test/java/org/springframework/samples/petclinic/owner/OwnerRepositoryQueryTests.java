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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OwnerRepositoryQueryTests {

	@Autowired
	private OwnerRepository owners;

	@Test
	void findByLastNameContainingMatchesSubstring() {
		// seed data has exactly one owner whose last name is "Davis"
		Page<Owner> result = this.owners.findByLastNameContaining("avi", PageRequest.of(0, 20));
		assertThat(result.getContent()).extracting(Owner::getLastName).contains("Davis");
	}

	@Test
	void findByLastNameContainingMissReturnsEmpty() {
		Page<Owner> result = this.owners.findByLastNameContaining("zzznomatch", PageRequest.of(0, 20));
		assertThat(result.getContent()).isEmpty();
	}

}
