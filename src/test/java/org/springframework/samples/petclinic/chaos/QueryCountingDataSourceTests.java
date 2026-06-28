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

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class QueryCountingDataSourceTests {

	@BeforeEach
	void resetCounter() {
		QueryCountingDataSource.reset();
	}

	private static QueryCountingDataSource dataSourceReturning(Connection delegateConnection) throws Exception {
		DataSource delegate = mock(DataSource.class);
		given(delegate.getConnection()).willReturn(delegateConnection);
		return new QueryCountingDataSource(delegate);
	}

	@Test
	void countsPrepareAndCreateStatementCalls() throws Exception {
		QueryCountingDataSource ds = dataSourceReturning(mock(Connection.class));

		Connection connection = ds.getConnection();
		connection.prepareStatement("select 1");
		connection.prepareStatement("select 2");
		connection.createStatement();

		assertThat(QueryCountingDataSource.count()).isEqualTo(3);
	}

	@Test
	void resetZerosTheCounter() throws Exception {
		QueryCountingDataSource ds = dataSourceReturning(mock(Connection.class));
		ds.getConnection().createStatement();
		assertThat(QueryCountingDataSource.count()).isEqualTo(1);

		QueryCountingDataSource.reset();

		assertThat(QueryCountingDataSource.count()).isZero();
	}

	@Test
	void doesNotCountNonStatementMethods() throws Exception {
		QueryCountingDataSource ds = dataSourceReturning(mock(Connection.class));

		ds.getConnection().getAutoCommit();

		assertThat(QueryCountingDataSource.count()).isZero();
	}

}
