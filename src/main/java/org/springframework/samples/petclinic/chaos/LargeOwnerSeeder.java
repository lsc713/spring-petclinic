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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds a large {@code owners} table so the query-plan regression (a leading-wildcard Seq
 * Scan) is genuinely slow and the EXPLAIN plan is meaningful. Idempotent: only inserts
 * when the table is below the target row count, using a single Postgres
 * {@code generate_series} statement. The bulk last names do not contain the known term
 * {@code Davis}, so a {@code LIKE '%Davis%'} search must scan all rows to find the one
 * real Davis. Postgres + chaos profile only.
 */
@Component
@Profile("chaos & postgres")
public class LargeOwnerSeeder implements ApplicationRunner {

	private static final int TARGET_ROWS = 100_000;

	private static final Log log = LogFactory.getLog(LargeOwnerSeeder.class);

	private final JdbcTemplate jdbcTemplate;

	public LargeOwnerSeeder(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		Integer count = this.jdbcTemplate.queryForObject("SELECT count(*) FROM owners", Integer.class);
		if (count != null && count >= TARGET_ROWS) {
			log.info("owners already has " + count + " rows; skipping bulk seed.");
			return;
		}
		int inserted = this.jdbcTemplate.update(
				"INSERT INTO owners (first_name, last_name, address, city, telephone) "
						+ "SELECT 'Bulk', 'Bulk' || g, 'addr', 'city', '0000000000' FROM generate_series(1, ?) g",
				TARGET_ROWS);
		log.info("Seeded " + inserted + " bulk owner rows for the query-plan regression bench.");
	}

}
