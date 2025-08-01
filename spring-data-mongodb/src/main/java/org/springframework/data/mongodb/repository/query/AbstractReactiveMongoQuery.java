/*
 * Copyright 2016-2025 the original author or authors.
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
package org.springframework.data.mongodb.repository.query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.expression.ReactiveValueEvaluationContextProvider;
import org.springframework.data.expression.ValueEvaluationContext;
import org.springframework.data.expression.ValueEvaluationContextProvider;
import org.springframework.data.expression.ValueExpression;
import org.springframework.data.mapping.model.EntityInstantiators;
import org.springframework.data.mapping.model.ValueExpressionEvaluator;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.ReactiveFindOperation.FindWithProjection;
import org.springframework.data.mongodb.core.ReactiveFindOperation.FindWithQuery;
import org.springframework.data.mongodb.core.ReactiveFindOperation.TerminatingFind;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.ReactiveUpdateOperation.ReactiveUpdate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.mongodb.repository.query.ReactiveMongoQueryExecution.DeleteExecution;
import org.springframework.data.mongodb.repository.query.ReactiveMongoQueryExecution.GeoNearExecution;
import org.springframework.data.mongodb.repository.query.ReactiveMongoQueryExecution.ResultProcessingConverter;
import org.springframework.data.mongodb.repository.query.ReactiveMongoQueryExecution.ResultProcessingExecution;
import org.springframework.data.mongodb.repository.query.ReactiveMongoQueryExecution.UpdateExecution;
import org.springframework.data.mongodb.util.json.ParameterBindingContext;
import org.springframework.data.mongodb.util.json.ParameterBindingDocumentCodec;
import org.springframework.data.repository.query.ParameterAccessor;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ValueExpressionDelegate;
import org.springframework.data.spel.ExpressionDependencies;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import com.mongodb.MongoClientSettings;

/**
 * Base class for reactive {@link RepositoryQuery} implementations for MongoDB.
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 * @author Jorge Rodríguez
 * @since 2.0
 */
public abstract class AbstractReactiveMongoQuery implements RepositoryQuery {

	private final ReactiveMongoQueryMethod method;
	private final ReactiveMongoOperations operations;
	private final EntityInstantiators instantiators;
	private final FindWithProjection<?> findOperationWithProjection;
	private final ReactiveUpdate<?> updateOps;
	private final ValueExpressionDelegate valueExpressionDelegate;
	private final ReactiveValueEvaluationContextProvider valueEvaluationContextProvider;

	/**
	 * Creates a new {@link AbstractReactiveMongoQuery} from the given {@link MongoQueryMethod} and
	 * {@link MongoOperations}.
	 *
	 * @param method must not be {@literal null}.
	 * @param operations must not be {@literal null}.
	 * @param delegate must not be {@literal null}.
	 * @since 4.4.0
	 */
	public AbstractReactiveMongoQuery(ReactiveMongoQueryMethod method, ReactiveMongoOperations operations,
			ValueExpressionDelegate delegate) {

		Assert.notNull(method, "MongoQueryMethod must not be null");
		Assert.notNull(operations, "ReactiveMongoOperations must not be null");
		Assert.notNull(delegate, "ValueExpressionDelegate must not be null");

		this.method = method;
		this.operations = operations;
		this.instantiators = new EntityInstantiators();
		this.valueExpressionDelegate = delegate;

		MongoEntityMetadata<?> metadata = method.getEntityInformation();
		Class<?> type = metadata.getCollectionEntity().getType();

		this.findOperationWithProjection = operations.query(type);
		this.updateOps = operations.update(type);
		ValueEvaluationContextProvider valueContextProvider = valueExpressionDelegate
				.createValueContextProvider(method.getParameters());
		Assert.isInstanceOf(ReactiveValueEvaluationContextProvider.class, valueContextProvider,
				"ValueEvaluationContextProvider must be reactive");
		this.valueEvaluationContextProvider = (ReactiveValueEvaluationContextProvider) valueContextProvider;
	}

	@Override
	public MongoQueryMethod getQueryMethod() {
		return method;
	}

	@Override
	public Publisher<Object> execute(Object[] parameters) {

		return method.hasReactiveWrapperParameter() ? executeDeferred(parameters)
				: execute(new MongoParametersParameterAccessor(method.getParameters(), parameters));
	}

	@SuppressWarnings("unchecked")
	private Publisher<Object> executeDeferred(Object[] parameters) {

		ReactiveMongoParameterAccessor parameterAccessor = new ReactiveMongoParameterAccessor(method.getParameters(),
				parameters);

		return parameterAccessor.resolveParameters().flatMapMany(this::execute);
	}

	private Publisher<Object> execute(MongoParameterAccessor parameterAccessor) {

		ConvertingParameterAccessor accessor = new ConvertingParameterAccessor(operations.getConverter(),
				parameterAccessor);

		ResultProcessor processor = method.getResultProcessor().withDynamicProjection(accessor);
		Class<?> typeToRead = processor.getReturnedType().getTypeToRead();

		return doExecute(method, processor, accessor, typeToRead);
	}

	/**
	 * Execute the {@link RepositoryQuery} of the given method with the parameters provided by the
	 * {@link ConvertingParameterAccessor accessor}
	 *
	 * @param method the {@link ReactiveMongoQueryMethod} invoked. Never {@literal null}.
	 * @param processor {@link ResultProcessor} for post procession. Never {@literal null}.
	 * @param accessor for providing invocation arguments. Never {@literal null}.
	 * @param typeToRead the desired component target type. Can be {@literal null}.
	 */
	protected Publisher<Object> doExecute(ReactiveMongoQueryMethod method, ResultProcessor processor,
			ConvertingParameterAccessor accessor, @Nullable Class<?> typeToRead) {

		return createQuery(accessor).flatMapMany(it -> {

			Query query = it;
			applyQueryMetaAttributesWhenPresent(query);
			query = applyAnnotatedDefaultSortIfPresent(query);
			query = applyAnnotatedCollationIfPresent(query, accessor);
			query = applyHintIfPresent(query);
			query = applyAnnotatedReadPreferenceIfPresent(query);

			FindWithQuery<?> find = typeToRead == null //
					? findOperationWithProjection //
					: findOperationWithProjection.as(typeToRead);

			String collection = method.getEntityInformation().getCollectionName();

			ReactiveMongoQueryExecution execution = getExecution(accessor,
					getResultProcessing(processor), find);
			return execution.execute(query, processor.getReturnedType().getDomainType(), collection);
		});
	}

	ResultProcessingConverter getResultProcessing(ResultProcessor processor) {
		return new ResultProcessingConverter(processor, operations, instantiators);
	}

	/**
	 * Returns the execution instance to use.
	 *
	 * @param accessor must not be {@literal null}.
	 * @param resultProcessing must not be {@literal null}.
	 * @return
	 */
	private ReactiveMongoQueryExecution getExecution(MongoParameterAccessor accessor,
			Converter<Object, Object> resultProcessing, FindWithQuery<?> operation) {
		return new ResultProcessingExecution(getExecutionToWrap(accessor, operation), resultProcessing);
	}

	@SuppressWarnings("NullAway")
	private ReactiveMongoQueryExecution getExecutionToWrap(MongoParameterAccessor accessor, FindWithQuery<?> operation) {

		if (isDeleteQuery()) {
			return new DeleteExecution(operations, method);
		} else if (method.isModifyingQuery()) {

			if (isLimiting()) {
				throw new IllegalStateException(
						String.format("Update method must not be limiting; Offending method: %s", method));
			}

			return new UpdateExecution(updateOps, method, accessor, createUpdate(accessor));
		} else if (method.isGeoNearQuery()) {
			return new GeoNearExecution(operations, accessor, method.getReturnType());
		} else if (isTailable(method)) {
			return (q, t, c) -> operation.matching(q.with(accessor.getPageable())).tail();
		} else if (method.isCollectionQuery()) {
			return (q, t, c) -> operation.matching(q.with(accessor.getPageable())).all();
		} else if (method.isScrollQuery()) {
			return (q, t, c) -> operation.matching(q.with(accessor.getPageable()).with(accessor.getSort()))
					.scroll(accessor.getScrollPosition());
		} else if (isCountQuery()) {
			return (q, t, c) -> operation.matching(q).count();
		} else if (isExistsQuery()) {
			return (q, t, c) -> operation.matching(q).exists();
		} else {
			return (q, t, c) -> {

				TerminatingFind<?> find = operation.matching(q);

				if (isCountQuery()) {
					return find.count();
				}

				return isLimiting() ? find.first() : find.one();
			};
		}
	}

	private boolean isTailable(MongoQueryMethod method) {
		return method.getTailableAnnotation() != null;
	}

	Query applyQueryMetaAttributesWhenPresent(Query query) {

		if (method.hasQueryMetaAttributes()) {
			query.setMeta(method.getQueryMetaAttributes());
		}

		return query;
	}

	/**
	 * Add a default sort derived from {@link org.springframework.data.mongodb.repository.Query#sort()} to the given
	 * {@link Query} if present.
	 *
	 * @param query the {@link Query} to potentially apply the sort to.
	 * @return the query with potential default sort applied.
	 * @since 2.1
	 */
	Query applyAnnotatedDefaultSortIfPresent(Query query) {

		if (!method.hasAnnotatedSort()) {
			return query;
		}

		return QueryUtils.decorateSort(query, Document.parse(method.getAnnotatedSort()));
	}

	/**
	 * If present apply a {@link org.springframework.data.mongodb.core.query.Collation} derived from the
	 * {@link org.springframework.data.repository.query.QueryMethod} the given {@link Query}.
	 *
	 * @param query must not be {@literal null}.
	 * @param accessor the {@link ParameterAccessor} used to obtain parameter placeholder replacement values.
	 * @return
	 * @since 2.2
	 */
	Query applyAnnotatedCollationIfPresent(Query query, ConvertingParameterAccessor accessor) {

		return QueryUtils.applyCollation(query, method.hasAnnotatedCollation() ? method.getAnnotatedCollation() : null,
				accessor, getValueExpressionEvaluator(accessor));
	}

	/**
	 * If present apply the hint from the {@link org.springframework.data.mongodb.repository.Hint} annotation.
	 *
	 * @param query must not be {@literal null}.
	 * @return never {@literal null}.
	 * @since 4.1
	 */
	Query applyHintIfPresent(Query query) {

		if (!method.hasAnnotatedHint()) {
			return query;
		}

		return query.withHint(method.getAnnotatedHint());
	}

	/**
	 * If present apply the {@link com.mongodb.ReadPreference} from the
	 * {@link org.springframework.data.mongodb.repository.ReadPreference} annotation.
	 *
	 * @param query must not be {@literal null}.
	 * @return never {@literal null}.
	 * @since 4.2
	 */
	private Query applyAnnotatedReadPreferenceIfPresent(Query query) {

		if (!method.hasAnnotatedReadPreference()) {
			return query;
		}

		return query.withReadPreference(com.mongodb.ReadPreference.valueOf(method.getAnnotatedReadPreference()));
	}

	/**
	 * Creates a {@link Query} instance using the given {@link ConvertingParameterAccessor}. Will delegate to
	 * {@link #createQuery(ConvertingParameterAccessor)} by default but allows customization of the count query to be
	 * triggered.
	 *
	 * @param accessor must not be {@literal null}.
	 * @return
	 */
	protected Mono<Query> createCountQuery(ConvertingParameterAccessor accessor) {
		return createQuery(accessor).map(this::applyQueryMetaAttributesWhenPresent);
	}

	/**
	 * Retrieves the {@link UpdateDefinition update} from the given
	 * {@link org.springframework.data.mongodb.repository.query.MongoParameterAccessor#getUpdate() accessor} or creates
	 * one via by parsing the annotated statement extracted from {@link Update}.
	 *
	 * @param accessor never {@literal null}.
	 * @return the computed {@link UpdateDefinition}.
	 * @throws IllegalStateException if no update could be found.
	 * @since 3.4
	 */
	@SuppressWarnings("NullAway")
	protected Mono<UpdateDefinition> createUpdate(MongoParameterAccessor accessor) {

		if (accessor.getUpdate() != null) {
			return Mono.just(accessor.getUpdate());
		}

		if (method.hasAnnotatedUpdate()) {
			Update updateSource = method.getUpdateSource();
			if (StringUtils.hasText(updateSource.update())) {

				String updateJson = updateSource.update();
				return getParameterBindingCodec() //
						.flatMap(codec -> expressionEvaluator(updateJson, accessor, codec) //
								.map(evaluator -> decode(evaluator, updateJson, accessor, codec))) //
						.map(BasicUpdate::fromDocument);
			}
			if (!ObjectUtils.isEmpty(updateSource.pipeline())) {
				return parseAggregationPipeline(updateSource.pipeline(), accessor).map(AggregationUpdate::from);
			}
		}

		throw new IllegalStateException(String.format("No Update provided for method %s.", method));
	}

	/**
	 * Parse the given aggregation pipeline stages applying values to placeholders to compute the actual list of
	 * {@link AggregationOperation operations}.
	 *
	 * @param pipeline must not be {@literal null}.
	 * @param accessor must not be {@literal null}.
	 * @return the parsed aggregation pipeline.
	 * @since 3.4
	 */
	protected Mono<List<AggregationOperation>> parseAggregationPipeline(String[] pipeline,
			MongoParameterAccessor accessor) {

		return getCodecRegistry().map(ParameterBindingDocumentCodec::new).flatMap(codec -> {

			List<Mono<AggregationOperation>> stages = new ArrayList<>(pipeline.length);
			for (String source : pipeline) {
				stages.add(computePipelineStage(source, accessor, codec));
			}
			return Flux.concat(stages).collectList();
		});
	}

	private Mono<AggregationOperation> computePipelineStage(String source, MongoParameterAccessor accessor,
			ParameterBindingDocumentCodec codec) {

		return expressionEvaluator(source, accessor, codec).map(evaluator -> new StringAggregationOperation(source,
				AbstractReactiveMongoQuery.this.getQueryMethod().getDomainClass(),
				bsonString -> AbstractReactiveMongoQuery.this.decode(evaluator, bsonString, accessor, codec)));
	}

	private Mono<Tuple2<ValueExpressionEvaluator, ParameterBindingDocumentCodec>> expressionEvaluator(String source,
			MongoParameterAccessor accessor, ParameterBindingDocumentCodec codec) {

		ExpressionDependencies dependencies = codec.captureExpressionDependencies(source, accessor::getBindableValue,
				valueExpressionDelegate.getValueExpressionParser());
		return getValueExpressionEvaluatorLater(dependencies, accessor).zipWith(Mono.just(codec));
	}

	private Document decode(Tuple2<ValueExpressionEvaluator, ParameterBindingDocumentCodec> expressionEvaluator,
			String source, MongoParameterAccessor accessor, ParameterBindingDocumentCodec codec) {

		ParameterBindingContext bindingContext = new ParameterBindingContext(accessor::getBindableValue,
				expressionEvaluator.getT1());
		return codec.decode(source, bindingContext);
	}

	/**
	 * Obtain the {@link ParameterBindingDocumentCodec} used for parsing JSON expressions.
	 *
	 * @return never {@literal null}.
	 * @since 3.4
	 */
	protected Mono<ParameterBindingDocumentCodec> getParameterBindingCodec() {
		return getCodecRegistry().map(ParameterBindingDocumentCodec::new);
	}

	/**
	 * Obtain a {@link ValueExpressionEvaluator} suitable to evaluate expressions.
	 *
	 * @param accessor must not be {@literal null}.
	 * @since 4.3
	 */
	ValueExpressionEvaluator getValueExpressionEvaluator(MongoParameterAccessor accessor) {

		return new ValueExpressionEvaluator() {

			@Override
			public <T> @Nullable T evaluate(String expressionString) {
				ValueExpression expression = valueExpressionDelegate.parse(expressionString);
				ValueEvaluationContext evaluationContext = valueEvaluationContextProvider
						.getEvaluationContext(accessor.getValues(), expression.getExpressionDependencies());
				return (T) expression.evaluate(evaluationContext);
			}
		};
	}

	/**
	 * Obtain a {@link Mono publisher} emitting the {@link ValueExpressionEvaluator} suitable to evaluate expressions
	 * backed by the given dependencies.
	 *
	 * @param dependencies must not be {@literal null}.
	 * @param accessor must not be {@literal null}.
	 * @return a {@link Mono} emitting the {@link ValueExpressionEvaluator} when ready.
	 * @since 4.3
	 */
	protected Mono<ValueExpressionEvaluator> getValueExpressionEvaluatorLater(ExpressionDependencies dependencies,
			MongoParameterAccessor accessor) {

		return valueEvaluationContextProvider.getEvaluationContextLater(accessor.getValues(), dependencies)
				.map(evaluationContext -> new ValueExpressionDelegateValueExpressionEvaluator(valueExpressionDelegate,
						valueExpression -> evaluationContext));
	}

	/**
	 * @return a {@link Mono} emitting the {@link CodecRegistry} when ready.
	 * @since 2.4
	 */
	protected Mono<CodecRegistry> getCodecRegistry() {

		return Mono.from(operations.execute(db -> Mono.just(db.getCodecRegistry())))
				.defaultIfEmpty(MongoClientSettings.getDefaultCodecRegistry());
	}

	/**
	 * Creates a {@link Query} instance using the given {@link ParameterAccessor}
	 *
	 * @param accessor must not be {@literal null}.
	 * @return
	 */
	protected abstract Mono<Query> createQuery(ConvertingParameterAccessor accessor);

	/**
	 * Returns whether the query should get a count projection applied.
	 *
	 * @return
	 */
	protected abstract boolean isCountQuery();

	/**
	 * Returns whether the query should get an exists projection applied.
	 *
	 * @return
	 * @since 2.0.9
	 */
	protected abstract boolean isExistsQuery();

	/**
	 * Return weather the query should delete matching documents.
	 *
	 * @return
	 * @since 1.5
	 */
	protected abstract boolean isDeleteQuery();

	/**
	 * Return whether the query has an explicit limit set.
	 *
	 * @return
	 * @since 2.0.4
	 */
	protected abstract boolean isLimiting();
}
