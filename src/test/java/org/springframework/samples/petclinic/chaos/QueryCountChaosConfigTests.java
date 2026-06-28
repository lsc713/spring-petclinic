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

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QueryCountChaosConfigTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
		.withBean("appDataSource", DataSource.class, () -> mock(DataSource.class))
		.withUserConfiguration(QueryCountChaosConfig.class);

	@Test
	void chaosProfileWrapsDataSourceAndLoadsConfig() {
		this.runner.withPropertyValues("spring.profiles.active=chaos").run((context) -> {
			assertThat(context).hasNotFailed().hasSingleBean(QueryCountChaosConfig.class);
			assertThat(context.getBean(DataSource.class)).isInstanceOf(QueryCountingDataSource.class);
		});
	}

	@Test
	void defaultProfileLeavesDataSourceUnwrappedAndExcludesConfig() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(QueryCountChaosConfig.class);
			assertThat(context.getBean(DataSource.class)).isNotInstanceOf(QueryCountingDataSource.class);
		});
	}

}
