package org.springframework.samples.petclinic.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosStateTests {

	private final ChaosState state = new ChaosState();

	@Test
	void unknownScenarioIsDisarmedByDefault() {
		assertThat(this.state.isArmed("ownerSearchNpe")).isFalse();
	}

	@Test
	void armThenDisarmFlipsState() {
		this.state.arm("ownerSearchNpe");
		assertThat(this.state.isArmed("ownerSearchNpe")).isTrue();

		this.state.disarm("ownerSearchNpe");
		assertThat(this.state.isArmed("ownerSearchNpe")).isFalse();
	}

	@Test
	void statusReflectsTouchedScenariosOnly() {
		this.state.arm("ownerSearchNpe");
		assertThat(this.state.status()).containsEntry("ownerSearchNpe", true).hasSize(1);
	}

}
