package org.sagebionetworks.repo.manager.agent.tool;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sagebionetworks.openapi.model.OpenApiJsonSchema;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.schema.ObjectSchema;
import org.sagebionetworks.translator.ObjectSchemaUtils;

/**
 * Generates a self-contained JSON Schema for an agent tool's request type — the {@code inputSchema}
 * Bedrock hands the model. The schema is produced from the request POJOs via {@link ObjectSchemaUtils},
 * so it resolves {@code $ref}s and, for the grid {@code Filter}/{@code SelectItem}/{@code SetValue}
 * interface unions, emits a {@code oneOf} + {@code concreteType} discriminator with the single-value
 * {@code concreteType} enum on each implementer — exactly the polymorphism the model must reproduce.
 * <p>
 * {@link ObjectSchemaUtils} was written to populate an OpenAPI spec's {@code components.schemas}
 * section, so on its own it is not a valid standalone document: every {@code $ref} targets
 * {@code #/components/schemas/<FQN>}, which does not resolve outside the full spec. This generator
 * repackages that output into a standard {@code $defs} envelope:
 * <ul>
 * <li>the root request schema is inlined at the top level ({@code type}/{@code properties}/{@code required});</li>
 * <li>every transitively referenced type is moved under a sibling {@code $defs} object, keyed by FQN;</li>
 * <li>every {@code $ref} is rewritten {@code #/components/schemas/<FQN>} &rarr; {@code #/$defs/<FQN>};</li>
 * <li>only types actually reachable by {@code $ref} from the root appear in {@code $defs}, so inlined
 * {@code concreteType}/domain enums do not leave orphan duplicates.</li>
 * </ul>
 * These transforms are mechanical and model-agnostic (hoist root, move classes under {@code $defs},
 * rewrite the ref prefix); {@code $defs}, {@code #/$defs/...}, and {@code oneOf} are core JSON Schema
 * (2019-09/2020-12) understood by Bedrock/Claude.
 * <p>
 * {@link ObjectSchemaUtils#getConcreteClasses} follows properties and each concrete class's declared
 * interfaces, but it does NOT discover an interface's implementers. Callers therefore seed the
 * implementer class names from the generated {@code *InstanceFactory} registries (see
 * {@link #generateSchema(String, Collection)}), which enumerate every concrete implementer of an
 * interface without reflection.
 */
public class JSONEntityToolSchemaGenerator {

	private static final String COMPONENTS_PREFIX = "#/components/schemas/";
	private static final String DEFS_PREFIX = "#/$defs/";
	private static final String KEY_REF = "$ref";
	private static final String KEY_DEFS = "$defs";
	private static final String KEY_DESCRIPTION = "description";

	/**
	 * Generate the self-contained {@code $defs}-envelope schema for a request type.
	 *
	 * @param rootClassName        the fully-qualified name of the request root type (e.g.
	 *                             {@code QueryRequest}).
	 * @param implementerIterators one iterator per polymorphic interface reachable from the root,
	 *                             from {@code <Interface>InstanceFactory.singleton().getKeySetIterator()};
	 *                             may be empty when the request has no interface unions.
	 * @return a pretty-printed, self-contained JSON Schema document.
	 */
	public static String generateSchema(String rootClassName, Collection<Iterator<String>> implementerIterators) {
		return generateSchema(rootClassName, implementerIterators, null);
	}

	/**
	 * Generate the self-contained {@code $defs}-envelope schema for a request type.
	 * <p>
	 * Because the request POJO is itself the tool's argument (there is no synthetic parameter-name
	 * wrapper object as in Spring AI's default {@code @ToolParam} schema), the tool-parameter
	 * description is applied to the root schema node — the equivalent home the model reads as
	 * argument guidance.
	 *
	 * @param rootClassName        the fully-qualified name of the request root type (e.g.
	 *                             {@code QueryRequest}).
	 * @param implementerIterators one iterator per polymorphic interface reachable from the root,
	 *                             from {@code <Interface>InstanceFactory.singleton().getKeySetIterator()};
	 *                             may be empty when the request has no interface unions.
	 * @param description          the tool-parameter description to place on the root schema; ignored
	 *                             when {@code null} or blank.
	 * @return a pretty-printed, self-contained JSON Schema document.
	 */
	public static String generateSchema(String rootClassName, Collection<Iterator<String>> implementerIterators,
			String description) {
		Set<String> classNames = collectClassNames(rootClassName, implementerIterators);

		ObjectSchemaUtils utils = new ObjectSchemaUtils();
		Map<String, ObjectSchema> classNameToObjectSchema = utils.getConcreteClasses(classNames.iterator());
		Map<String, OpenApiJsonSchema> classNameToJsonSchema = utils.getClassNameToJsonSchema(classNameToObjectSchema);

		// Serialize each generated schema through the canonical Synapse JSONEntity path so nested
		// structures (oneOf, discriminator, properties) render exactly as the model will consume them.
		Map<String, JSONObject> classNameToRawSchema = new TreeMap<>();
		for (Map.Entry<String, OpenApiJsonSchema> entry : classNameToJsonSchema.entrySet()) {
			classNameToRawSchema.put(entry.getKey(),
					new JSONObject(JDOSecondaryPropertyUtils.createJSONFromObject(entry.getValue())));
		}

		JSONObject root = classNameToRawSchema.get(rootClassName);
		if (root == null) {
			throw new IllegalStateException("No schema was generated for the root type: " + rootClassName);
		}

		JSONObject envelope = buildEnvelope(rootClassName, root, classNameToRawSchema);
		if (description != null && !description.isBlank()) {
			envelope.put(KEY_DESCRIPTION, description);
		}
		return envelope.toString(2);
	}

	/**
	 * Assemble the set of fully-qualified class names to generate schema for: the request root plus
	 * every concrete implementer named by the supplied {@code *InstanceFactory} key-set iterators.
	 */
	static Set<String> collectClassNames(String rootClassName, Collection<Iterator<String>> implementerIterators) {
		Set<String> classNames = new LinkedHashSet<>();
		classNames.add(rootClassName);
		for (Iterator<String> iterator : implementerIterators) {
			while (iterator.hasNext()) {
				classNames.add(iterator.next());
			}
		}
		return classNames;
	}

	/**
	 * Build the {@code $defs} envelope: the root schema inlined at the top level, with every type
	 * reachable by {@code $ref} from the root gathered under {@code $defs} and all refs rewritten to
	 * the {@code #/$defs/} prefix.
	 */
	private static JSONObject buildEnvelope(String rootClassName, JSONObject root,
			Map<String, JSONObject> classNameToRawSchema) {
		// The root is inlined at the top level; reachability determines which of the remaining types
		// are genuine dependencies (versus enums the translator already inlined at each use site).
		Set<String> reachable = findReachableRefs(rootClassName, classNameToRawSchema);

		JSONObject envelope = new JSONObject(root.toString());
		rewriteRefs(envelope);

		JSONObject defs = new JSONObject();
		for (String className : new TreeMap<>(classNameToRawSchema).keySet()) {
			if (reachable.contains(className)) {
				JSONObject def = new JSONObject(classNameToRawSchema.get(className).toString());
				rewriteRefs(def);
				defs.put(className, def);
			}
		}
		if (defs.length() > 0) {
			envelope.put(KEY_DEFS, defs);
		}
		return envelope;
	}

	/**
	 * Compute the transitive closure of types reachable from the root by following {@code $ref}
	 * values. A self-referential root is included so its own {@code #/$defs/<root>} ref still resolves.
	 */
	private static Set<String> findReachableRefs(String rootClassName, Map<String, JSONObject> classNameToRawSchema) {
		Set<String> reachable = new LinkedHashSet<>();
		Deque<String> frontier = new ArrayDeque<>();

		collectRefTargets(classNameToRawSchema.get(rootClassName)).forEach(target -> {
			if (reachable.add(target)) {
				frontier.add(target);
			}
		});

		while (!frontier.isEmpty()) {
			JSONObject schema = classNameToRawSchema.get(frontier.poll());
			if (schema == null) {
				continue;
			}
			for (String target : collectRefTargets(schema)) {
				if (reachable.add(target)) {
					frontier.add(target);
				}
			}
		}
		return reachable;
	}

	/**
	 * Collect the FQN target of every {@code $ref} anywhere within a schema node, stripping the
	 * OpenAPI components prefix.
	 */
	private static Set<String> collectRefTargets(Object node) {
		Set<String> targets = new LinkedHashSet<>();
		visitRefs(node, ref -> {
			if (ref.startsWith(COMPONENTS_PREFIX)) {
				targets.add(ref.substring(COMPONENTS_PREFIX.length()));
			} else if (ref.startsWith(DEFS_PREFIX)) {
				targets.add(ref.substring(DEFS_PREFIX.length()));
			}
		});
		return targets;
	}

	/**
	 * Rewrite every {@code $ref} in place from the OpenAPI components prefix to the {@code $defs} prefix.
	 */
	private static void rewriteRefs(Object node) {
		if (node instanceof JSONObject object) {
			for (String key : object.keySet()) {
				if (KEY_REF.equals(key)) {
					String ref = object.getString(key);
					if (ref.startsWith(COMPONENTS_PREFIX)) {
						object.put(key, DEFS_PREFIX + ref.substring(COMPONENTS_PREFIX.length()));
					}
				} else {
					rewriteRefs(object.get(key));
				}
			}
		} else if (node instanceof JSONArray array) {
			for (int i = 0; i < array.length(); i++) {
				rewriteRefs(array.get(i));
			}
		}
	}

	/**
	 * Recursively visit every {@code $ref} string value within a schema node.
	 */
	private static void visitRefs(Object node, java.util.function.Consumer<String> consumer) {
		if (node instanceof JSONObject object) {
			for (String key : object.keySet()) {
				if (KEY_REF.equals(key)) {
					consumer.accept(object.getString(key));
				} else {
					visitRefs(object.get(key), consumer);
				}
			}
		} else if (node instanceof JSONArray array) {
			for (int i = 0; i < array.length(); i++) {
				visitRefs(array.get(i), consumer);
			}
		}
	}

	private JSONEntityToolSchemaGenerator() {
	}
}
