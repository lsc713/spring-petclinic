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

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanProbeTests {

	@Test
	void seqScanPlanIsDetected() {
		String plan = "Seq Scan on owners  (cost=0.00..1834.00 rows=1 width=4)\n  Filter: ((last_name)::text ~~ '%Davis%'::text)";
		assertThat(QueryPlanProbe.containsSeqScan(plan)).isTrue();
	}

	@Test
	void indexScanPlanIsNotSeqScan() {
		String plan = "Index Scan using owners_last_name on owners  (cost=0.28..8.29 rows=1 width=4)\n  Index Cond: ((last_name)::text ~~ 'Davis%'::text)";
		assertThat(QueryPlanProbe.containsSeqScan(plan)).isFalse();
	}

	@Test
	void nullPlanIsNotSeqScan() {
		assertThat(QueryPlanProbe.containsSeqScan(null)).isFalse();
	}

}
