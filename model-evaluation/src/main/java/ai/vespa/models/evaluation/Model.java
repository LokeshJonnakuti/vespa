// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.models.evaluation;

import com.yahoo.api.annotations.Beta;
import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.evaluation.ContextIndex;
import com.yahoo.searchlib.rankingexpression.evaluation.ExpressionOptimizer;
import com.yahoo.stream.CustomCollectors;
import com.yahoo.tensor.TensorType;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A named collection of functions
 *
 * @author bratseth
 */
@Beta
public class Model implements AutoCloseable {

    /** The prefix generated by model-integration/../IntermediateOperation */
    private final static String INTERMEDIATE_OPERATION_FUNCTION_PREFIX = "imported_ml_function_";

    private final String name;

    /** Free functions */
    private final List<ExpressionFunction> functions;

    /** The subset of the free functions which are public (additional non-public methods are generated during import) */
    private final List<ExpressionFunction> publicFunctions;

    /** Instances of each usage of the above function, where variables (if any) are replaced by their bindings */
    private final Map<FunctionReference, ExpressionFunction> referencedFunctions;

    /** Context prototypes, indexed by function name (as all invocations of the same function share the same context prototype) */
    private final Map<String, LazyArrayContext> contextPrototypes;

    private final ExpressionOptimizer expressionOptimizer = new ExpressionOptimizer();

    private final List<Runnable> closeActions;

    /** Programmatically create a model containing functions without constant of function references only */
    public Model(String name, Collection<ExpressionFunction> functions) {
        this(name,
             functions.stream().collect(Collectors.toMap(f -> FunctionReference.fromName(f.getName()), f -> f)),
             Map.of(),
             Map.of(),
             List.of(),
             List.of());
    }

    Model(String name,
          Map<FunctionReference, ExpressionFunction> functions,
          Map<FunctionReference, ExpressionFunction> referencedFunctions,
          Map<String, TensorType> declaredTypes,
          List<Constant> constants,
          List<OnnxModel> onnxModels) {
        this.name = name;

        // Build context and add missing function arguments (missing because it is legal to omit scalar type arguments)
        Map<String, LazyArrayContext> contextBuilder = new LinkedHashMap<>();
        for (Map.Entry<FunctionReference, ExpressionFunction> function : functions.entrySet()) {
            try {
                LazyArrayContext context = new LazyArrayContext(function.getValue(), referencedFunctions, constants, onnxModels, this);
                contextBuilder.put(function.getValue().getName(), context);
                if (function.getValue().returnType().isEmpty()) {
                    functions.put(function.getKey(), function.getValue().withReturnType(TensorType.empty));
                }

                for (Map.Entry<String, OnnxModel> entry : context.onnxModels().entrySet()) {
                    OnnxModel onnxModel = entry.getValue();
                    for(Map.Entry<String, TensorType> input : onnxModel.inputs().entrySet()) {
                        functions.put(function.getKey(), function.getValue().withArgument(input.getKey(), input.getValue()));
                    }
                }

                for (String argument : context.arguments()) {
                    if (function.getValue().getName().startsWith(INTERMEDIATE_OPERATION_FUNCTION_PREFIX)) {
                        // Internal (generated) functions do not have type info - add arguments
                        if (!function.getValue().arguments().contains(argument))
                            functions.put(function.getKey(), function.getValue().withArgument(argument));
                    }
                    else {
                        // External functions have type info (when not scalar) - add argument types
                        if (function.getValue().getArgumentType(argument) == null) {
                            TensorType type = declaredTypes.getOrDefault(argument, TensorType.empty);
                            functions.put(function.getKey(), function.getValue().withArgument(argument, type));
                        }
                    }
                }
            }
            catch (RuntimeException e) {
                throw new IllegalArgumentException("Could not prepare an evaluation context for " + function, e);
            }
        }
        this.contextPrototypes = Map.copyOf(contextBuilder);
        this.functions = List.copyOf(functions.values());
        this.publicFunctions = functions.values().stream()
                .filter(f -> !f.getName().startsWith(INTERMEDIATE_OPERATION_FUNCTION_PREFIX)).toList();

        // Optimize functions
        this.referencedFunctions = Map.copyOf(referencedFunctions.entrySet().stream()
                .collect(CustomCollectors.toLinkedMap(f -> f.getKey(), f -> optimize(f.getValue(), contextPrototypes.get(f.getKey().functionName())))));
        this.closeActions = onnxModels.stream().map(o -> (Runnable)o::close).toList();
    }

    /** Returns an optimized version of the given function */
    private ExpressionFunction optimize(ExpressionFunction function, ContextIndex context) {
        // Note: Optimization is in-place but we do not depend on that outside this method
        expressionOptimizer.optimize(function.getBody(), context);
        return function;
    }

    public String name() { return name; }

    /**
     * Returns an immutable list of the free, public functions of this.
     * The functions returned always specifies types of all arguments and the return value
     */
    public List<ExpressionFunction> functions() {
        return publicFunctions;
    }

    /** Returns the given function, or throws a IllegalArgumentException if it does not exist */
    private LazyArrayContext requireContextPrototype(String name) {
        LazyArrayContext context = contextPrototypes.get(name);
        if (context == null) // Implies function is not present
            throw new IllegalArgumentException("No function named '" + name + "' in " + this + ". Available functions: " +
                                               functions.stream().map(ExpressionFunction::getName).collect(Collectors.joining(", ")));
        return context;
    }

    /** Returns the function with the given name, or null if none */ // TODO: Parameter overloading?
    ExpressionFunction function(String name) {
        for (ExpressionFunction function : functions)
            if (function.getName().equals(name))
                return function;
        return null;
    }

    /** Returns an immutable map of the referenced function instances of this */
    Map<FunctionReference, ExpressionFunction> referencedFunctions() { return Map.copyOf(referencedFunctions); }

    /** Returns the given referred function, or throws a IllegalArgumentException if it does not exist */
    ExpressionFunction requireReferencedFunction(FunctionReference reference) {
        ExpressionFunction function = referencedFunctions.get(reference);
        if (function == null)
            throw new IllegalArgumentException("No " + reference + " in " + this + ". References: " +
                                               referencedFunctions.keySet().stream()
                                                                           .map(FunctionReference::serialForm)
                                                                           .collect(Collectors.joining(", ")));
        return function;
    }

    /**
     * Returns an evaluator which can be used to evaluate the given function in a single thread once.
     *
     * Usage:
     * <code>Tensor result = model.evaluatorOf("myFunction").bind("foo", value).bind("bar", value).evaluate()</code>
     *
     * @param names the names identifying the function - this can be from 0 to 2, specifying function or "signature"
     *              name, and "output", respectively. Names which are unnecessary to determine the desired function
     *              uniquely (e.g if there is just one function or output) can be omitted.
     *              A two-component name can alternatively be specified as a single argument with components separated
     *              by dot.
     * @throws IllegalArgumentException if the function is not present, or not uniquely identified by the names given
     */
    public FunctionEvaluator evaluatorOf(String ... names) {  // TODO: Parameter overloading?
        if (names.length == 0) {
            if (functions.size() > 1)
                throwUndeterminedFunction("More than one function is available in " + this + ", but no name is given");
            return evaluatorOf(functions.get(0));
        }
        else if (names.length == 1) {
            String name = names[0];
            ExpressionFunction function = function(name);
            if (function != null) return evaluatorOf(function);

            // Check if the name is a signature
            List<ExpressionFunction> functionsStartingByName =
                    functions.stream().filter(f -> f.getName().startsWith(name + ".")).toList();
            if (functionsStartingByName.size() == 1)
                return evaluatorOf(functionsStartingByName.get(0));
            if (functionsStartingByName.size() > 1)
                throwUndeterminedFunction("Multiple functions start by '" + name + "' in " + this);

            // Check if the name is unambiguous as an output
            List<ExpressionFunction> functionsEndingByName =
                    functions.stream().filter(f -> f.getName().endsWith("." + name)).toList();
            if (functionsEndingByName.size() == 1)
                return evaluatorOf(functionsEndingByName.get(0));
            if (functionsEndingByName.size() > 1)
                throwUndeterminedFunction("Multiple functions called '" + name + "' in " + this);

            // To handle TensorFlow conversion to ONNX
            if (name.startsWith("serving_default")) {
                return evaluatorOf("default" + name.substring("serving_default".length()));
            }

            // To handle backward compatibility with ONNX conversion to native Vespa ranking expressions
            if (name.startsWith("default.")) {
                return evaluatorOf(name.substring("default.".length()));
            }

            throwUndeterminedFunction("No function '" + name + "' in " + this);
        }
        else if (names.length == 2) {
            return evaluatorOf(names[0] + "." + names[1]);
        }
        throw new IllegalArgumentException("No more than 2 names can be given when choosing a function, got " +
                                           Arrays.toString(names));
    }

    /** Returns a single-use evaluator of a function */
    private FunctionEvaluator evaluatorOf(ExpressionFunction function) {
        return new FunctionEvaluator(function, requireContextPrototype(function.getName()).copy());
    }

    private void throwUndeterminedFunction(String message) {
        throw new IllegalArgumentException(message + ". Available functions: " +
                                           functions.stream().map(ExpressionFunction::getName).collect(Collectors.joining(", ")));
    }

    @Override
    public String toString() { return "model '" + name + "'"; }

    @Override public void close() { closeActions.forEach(Runnable::run); }
}
