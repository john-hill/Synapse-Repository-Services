package org.sagebionetworks.repo.manager.agent.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.json.JSONObject;
import org.sagebionetworks.repo.manager.agent.specialist.JSONEntityResultConverter;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * Base class that exposes {@link JSONEntityTool}-annotated methods as Spring AI {@link ToolCallback}s.
 * <p>
 * For each annotated method the base:
 * <ul>
 * <li>generates the tool's native {@code inputSchema} — the self-contained JSON Schema Bedrock hands
 * the model — from the method's request parameter (see {@link JSONEntityToolSchemaGenerator}), so the
 * request structure lives in the tool contract rather than in injected prompt prose;</li>
 * <li>on invocation, deserializes the model's JSON argument into the request POJO via the Synapse
 * {@code concreteType}-aware path ({@link JDOSecondaryPropertyUtils#createObjectFromJSON}), which
 * understands the discriminated {@code oneOf} unions that Spring AI's plain Jackson mapper does not;</li>
 * <li>serializes the returned {@link org.sagebionetworks.repo.manager.agent.specialist.ToolResponse}
 * via {@link JSONEntityResultConverter}.</li>
 * </ul>
 * <p>
 * A request parameter may instead be declared as a raw {@code String}, in which case the model's JSON
 * is passed through untouched (the tool consumes the raw payload) while the model still sees a generated
 * schema, sourced from {@link JSONEntityToolParam#schemaType()}. This is used where a typed round-trip
 * would lose information the raw JSON must preserve (e.g. the undefined-vs-null distinction on a grid
 * update value).
 * <p>
 * When a request argument cannot be parsed, {@code call(...)} does not throw: it returns a plain error
 * string, which Spring AI feeds back to the model as the tool result so the model can self-correct on
 * its next turn.
 */
public abstract class JSONEntityToolBase {

	private final List<ToolCallback> callbacks;

	public JSONEntityToolBase() {
		callbacks = new ArrayList<>();

		for (Method method : this.getClass().getMethods()) {
			if (!Modifier.isStatic(method.getModifiers())) {

				JSONEntityTool toolAnno = method.getDeclaredAnnotation(JSONEntityTool.class);
				if (toolAnno != null) {

					String name = toolAnno.name().isBlank() ? method.getName() : toolAnno.name();

					// Wrap each callback so every invocation of a base-derived tool is logged.
					callbacks.add(new LoggingToolCallback(new Builder().setToolObject(this)
							.setToolDefinition(DefaultToolDefinition.builder().name(name)
									.description(toolAnno.description()).inputSchema(generateInputSchema(method)).build())
							.setToolMetadata(ToolMetadata.builder().returnDirect(toolAnno.returnDirect()).build())
							.setToolMethod(method).build()));
				}
			}
		}
		if (callbacks.isEmpty()) {
			throw new IllegalStateException("No tools found in this class");
		}
	}

	public List<ToolCallback> getToolCallbacks() {
		return callbacks;
	}

	/**
	 * The seed iterators for every polymorphic interface reachable from a tool's request type, one per
	 * interface, from {@code <Interface>InstanceFactory.singleton().getKeySetIterator()}. These enumerate
	 * an interface's concrete implementers, which the schema generator cannot otherwise discover.
	 * <p>
	 * Subclasses whose request type contains {@code oneOf} interface unions must override this; the
	 * default is empty (no polymorphic properties).
	 */
	protected Collection<Iterator<String>> getPolymorphicImplementerSeeds() {
		return List.of();
	}

	/**
	 * Build the {@code inputSchema} for a tool method from its request parameter — the schema type is
	 * either {@link JSONEntityToolParam#schemaType()} when set, or the parameter's own declared type.
	 */
	private String generateInputSchema(Method method) {
		for (Parameter parameter : method.getParameters()) {
			JSONEntityToolParam paramAnno = parameter.getDeclaredAnnotation(JSONEntityToolParam.class);
			if (paramAnno != null) {
				Class<? extends JSONEntity> schemaType = paramAnno.schemaType();
				if (JSONEntity.class.equals(schemaType)) {
					// The parameter is itself the request POJO; use its declared type as the schema source.
					schemaType = parameter.getType().asSubclass(JSONEntity.class);
				}
				return JSONEntityToolSchemaGenerator.generateSchema(schemaType.getName(),
						getPolymorphicImplementerSeeds(), paramAnno.description());
			}
		}
		throw new IllegalStateException(
				"Tool method must declare a @JSONEntityToolParam request parameter: " + method.getName());
	}

	private class Builder {

		private ToolDefinition toolDefinition;
		private ToolMetadata toolMetadata;
		private Method toolMethod;
		private Object toolObject;

		public ToolCallback build() {
			return new ToolCallback() {

				@Override
				public ToolMetadata getToolMetadata() {
					return toolMetadata;
				}

				@Override
				public ToolDefinition getToolDefinition() {
					return toolDefinition;
				}

				@Override
				public String call(String toolInput) {
					return call(toolInput, null);
				}

				@Override
				public String call(String toolInput, @Nullable ToolContext toolContext) {
					Object[] arguments;
					try {
						arguments = marshalArguments(toolInput, toolContext);
					} catch (IllegalArgumentException e) {
						// A malformed argument is fed back to the model as the tool result so it can retry;
						// it is not an exceptional condition for the agent loop.
						return "The argument provided to '" + toolDefinition.name()
								+ "' was not valid JSON for its input schema: " + e.getMessage()
								+ ". Resubmit the call with a corrected argument.";
					}
					Object result = callMethod(arguments);
					return new JSONEntityResultConverter().convert(result, toolMethod.getGenericReturnType());
				}
			};
		}

		/**
		 * Bind the model's JSON argument and the tool context onto the tool method's parameters:
		 * <ul>
		 * <li>a typed {@link JSONEntity} parameter is deserialized via the {@code concreteType}-aware path;</li>
		 * <li>a {@link JSONObject} parameter receives the parsed-but-untyped payload &mdash; well-formedness
		 * is validated here (so the base can return corrective feedback), yet the raw tree is preserved so
		 * an omitted key stays distinct from an explicit JSON null;</li>
		 * <li>a raw {@link String} parameter receives the untouched payload with no parsing;</li>
		 * <li>a {@link ToolContext} parameter receives the context.</li>
		 * </ul>
		 *
		 * @throws IllegalArgumentException if a typed or {@link JSONObject} request parameter cannot be
		 *                                  parsed from the input.
		 */
		private Object[] marshalArguments(String toolInput, @Nullable ToolContext toolContext) {
			Parameter[] parameters = toolMethod.getParameters();
			Object[] arguments = new Object[parameters.length];
			for (int i = 0; i < parameters.length; i++) {
				Class<?> parameterType = parameters[i].getType();
				if (ToolContext.class.equals(parameterType)) {
					arguments[i] = toolContext;
				} else if (JSONEntity.class.isAssignableFrom(parameterType)) {
					arguments[i] = deserialize(parameterType.asSubclass(JSONEntity.class), toolInput);
				} else if (JSONObject.class.equals(parameterType)) {
					arguments[i] = parseRawObject(toolInput);
				} else {
					// A raw carrier (String): the tool consumes the untouched payload with no parsing.
					arguments[i] = toolInput;
				}
			}
			return arguments;
		}

		private JSONEntity deserialize(Class<? extends JSONEntity> type, String toolInput) {
			try {
				return JDOSecondaryPropertyUtils.createObjectFromJSON(type, toolInput);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(
						e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
			}
		}

		private JSONObject parseRawObject(String toolInput) {
			try {
				return new JSONObject(toolInput);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(
						e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
			}
		}

		@SuppressWarnings("null")
		@Nullable
		private Object callMethod(Object[] methodArguments) {
			if (isObjectNotPublic() || isMethodNotPublic()) {
				this.toolMethod.setAccessible(true);
			}

			Object result;
			try {
				result = this.toolMethod.invoke(this.toolObject, methodArguments);
			} catch (IllegalAccessException ex) {
				throw new IllegalStateException("Could not access method: " + ex.getMessage(), ex);
			} catch (InvocationTargetException ex) {
				throw new ToolExecutionException(this.toolDefinition, ex.getCause());
			}
			return result;
		}

		public Builder setToolDefinition(ToolDefinition toolDefinition) {
			this.toolDefinition = toolDefinition;
			return this;
		}

		public Builder setToolMetadata(ToolMetadata toolMetadata) {
			this.toolMetadata = toolMetadata;
			return this;
		}

		public Builder setToolMethod(Method toolMethod) {
			this.toolMethod = toolMethod;
			return this;
		}

		public Builder setToolObject(Object toolObject) {
			this.toolObject = toolObject;
			return this;
		}

		private boolean isObjectNotPublic() {
			return this.toolObject != null && !Modifier.isPublic(this.toolObject.getClass().getModifiers());
		}

		private boolean isMethodNotPublic() {
			return !Modifier.isPublic(this.toolMethod.getModifiers());
		}
	}

}
