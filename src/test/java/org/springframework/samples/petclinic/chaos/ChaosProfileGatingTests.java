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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosProfileGatingTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ChaosState.class, NoOpChaosFaults.class, ActiveChaosFaults.class, ChaosController.class);

	@Test
	void defaultProfileUsesNoOpAndHidesControlPlane() {
		this.runner.run((context) -> {
			assertThatBeanPresent(context, NoOpChaosFaults.class, true);
			assertThatBeanPresent(context, ActiveChaosFaults.class, false);
			assertThatBeanPresent(context, ChaosController.class, false);
		});
	}

	@Test
	void chaosProfileActivatesFaultsAndControlPlane() {
		this.runner.withPropertyValues("spring.profiles.active=chaos").run((context) -> {
			assertThatBeanPresent(context, NoOpChaosFaults.class, false);
			assertThatBeanPresent(context, ActiveChaosFaults.class, true);
			assertThatBeanPresent(context, ChaosController.class, true);
		});
	}

	@Test
	void chaosProfileWiresDeadlockProbe() {
		// DeadlockProbe has two constructors; without @Autowired on the injection
		// constructor Spring cannot select one and the context fails to start. This boots
		// a minimal context wiring it exactly as Spring does, guarding that regression.
		new ApplicationContextRunner().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.withUserConfiguration(DeadlockProbe.class)
			.withPropertyValues("spring.profiles.active=chaos")
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(DeadlockProbe.class));
	}

	@Test
	void chaosProfileWiresCpuBurner() {
		new ApplicationContextRunner().withUserConfiguration(ChaosState.class, CpuBurner.class)
			.withPropertyValues("spring.profiles.active=chaos")
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(CpuBurner.class));
	}

	@Test
	void defaultProfileExcludesCpuBurner() {
		new ApplicationContextRunner().withUserConfiguration(ChaosState.class, CpuBurner.class)
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(CpuBurner.class));
	}

	private static void assertThatBeanPresent(org.springframework.context.ApplicationContext context, Class<?> type,
			boolean present) {
		boolean actual = context.getBeanNamesForType(type).length > 0;
		if (actual != present) {
			throw new AssertionError(type.getSimpleName() + " present=" + actual + " but expected present=" + present);
		}
	}

}
