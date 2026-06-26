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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled EXPLAIN probe for the query-plan-pathology fault class. A latency metric only
 * says the owner search is slow; this probe runs {@code EXPLAIN} on the query the search
 * currently uses and surfaces the plan: the gauge {@code petclinic.query.seqscan} flags a
 * Seq Scan, and the full plan is logged (the augmentation that localizes the
 * missing-index cause). Postgres + chaos profile only.
 */
@Component
@Profile("chaos & postgres")
public class QueryPlanProbe {

	/** A last name guaranteed to exist in the seed data. */
	static final String KNOWN_LAST_NAME = "Davis";

	private static final Log log = LogFactory.getLog(QueryPlanProbe.class);

	private final ChaosFaults chaosFaults;

	private final JdbcTemplate jdbcTemplate;

	private final AtomicInteger seqScan = new AtomicInteger(0);

	public QueryPlanProbe(ChaosFaults chaosFaults, JdbcTemplate jdbcTemplate, MeterRegistry registry) {
		this.chaosFaults = chaosFaults;
		this.jdbcTemplate = jdbcTemplate;
		Gauge.builder("petclinic.query.seqscan", this.seqScan, AtomicInteger::get)
			.description("1 when the owner-search query plan is a Seq Scan")
			.tag("check", "ownerSearch")
			.register(registry);
	}

	/**
	 * Run EXPLAIN on the owner-search query in the form the search currently uses
	 * (regressed leading-wildcard when armed, else indexed prefix), update the gauge, and
	 * log the plan when it Seq-Scans.
	 * @return 1 when the plan is a Seq Scan, else 0
	 */
	public int checkOwnerSearchPlan() {
		String term = this.chaosFaults.useRegressedOwnerQuery() ? "%" + KNOWN_LAST_NAME + "%" : KNOWN_LAST_NAME + "%";
		List<String> lines = this.jdbcTemplate.queryForList("EXPLAIN SELECT id FROM owners WHERE last_name LIKE ?",
				String.class, term);
		String plan = String.join("\n", lines);
		boolean seq = containsSeqScan(plan);
		this.seqScan.set(seq ? 1 : 0);
		if (seq) {
			log.warn("Owner-search query plan regressed to a Seq Scan:\n" + plan);
		}
		return seq ? 1 : 0;
	}

	/**
	 * Whether a Postgres text EXPLAIN plan contains a Seq Scan over the owners table.
	 * @param plan the EXPLAIN plan text (may be null)
	 * @return true when the plan Seq-Scans owners
	 */
	static boolean containsSeqScan(String plan) {
		return plan != null && plan.contains("Seq Scan on owners");
	}

	@Scheduled(fixedRateString = "${chaos.queryplan.interval-ms:5000}")
	void probe() {
		checkOwnerSearchPlan();
	}

}
