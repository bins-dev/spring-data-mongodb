/*
 * Copyright 2025 the original author or authors.
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
package org.springframework.data.mongodb.repository.aot;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.data.repository.aot.generate.QueryMetadata;

/**
 * An {@link MongoInteraction aggregation interaction}.
 *
 * @author Christoph Strobl
 * @since 5.0
 */
class AggregationInteraction extends MongoInteraction implements QueryMetadata {

	private final AotStringAggregation aggregation;

	AggregationInteraction(String[] raw) {
		this.aggregation = new AotStringAggregation(raw);
	}

	List<String> stages() {
		return Arrays.asList(aggregation.pipeline());
	}

	@Override
	InteractionType getExecutionType() {
		return InteractionType.AGGREGATION;
	}

	@Override
	public Map<String, Object> serialize() {
		return Map.of(pipelineSerializationKey(), stages());
	}

	protected String pipelineSerializationKey() {
		return "pipeline";
	}
}
