package io.cucumber.core.runner;

import io.cucumber.core.backend.DataTableTypeDefinition;
import io.cucumber.core.backend.DefaultDataTableCellTransformerDefinition;
import io.cucumber.core.backend.DefaultDataTableEntryTransformerDefinition;
import io.cucumber.core.backend.DefaultParameterTransformerDefinition;
import io.cucumber.core.backend.DocStringTypeDefinition;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.JavaMethodReference;
import io.cucumber.core.backend.ParameterTypeDefinition;
import io.cucumber.core.backend.ScenarioScoped;
import io.cucumber.core.backend.StackTraceElementReference;
import io.cucumber.core.backend.StaticHookDefinition;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.StepExpression;
import io.cucumber.core.stepexpression.StepExpressionFactory;
import io.cucumber.core.stepexpression.StepTypeRegistry;
import io.cucumber.cucumberexpressions.CucumberExpression;
import io.cucumber.cucumberexpressions.DuplicateTypeNameException;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ParameterByTypeTransformer;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.RegularExpression;
import io.cucumber.datatable.DuplicateTypeException;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableEntryByTypeTransformer;
import io.cucumber.docstring.CucumberDocStringException;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Hook;
import io.cucumber.messages.types.JavaMethod;
import io.cucumber.messages.types.JavaStackTraceElement;
import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.SourceReference;
import io.cucumber.messages.types.StepDefinitionPattern;
import io.cucumber.messages.types.StepDefinitionPatternType;
import io.cucumber.plugin.event.StepDefinedEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class CachingGlue implements Glue {

    private static final Comparator<CoreHookDefinition> HOOK_ORDER_ASCENDING = Comparator
            .comparingInt(CoreHookDefinition::getOrder)
            .thenComparing(ScenarioScoped.class::isInstance);

    private static final Comparator<StaticHookDefinition> STATIC_HOOK_ORDER_ASCENDING = Comparator
            .comparingInt(StaticHookDefinition::getOrder);

    private final List<ParameterTypeDefinition> parameterTypeDefinitions = new ArrayList<>();
    private final List<DataTableTypeDefinition> dataTableTypeDefinitions = new ArrayList<>();
    private final List<DefaultParameterTransformerDefinition> defaultParameterTransformers = new ArrayList<>();
    private final List<CoreDefaultDataTableEntryTransformerDefinition> defaultDataTableEntryTransformers = new ArrayList<>();
    private final List<DefaultDataTableCellTransformerDefinition> defaultDataTableCellTransformers = new ArrayList<>();
    private final List<DocStringTypeDefinition> docStringTypeDefinitions = new ArrayList<>();

    private final List<StaticHookDefinition> beforeAllHooks = new ArrayList<>();
    private final List<CoreHookDefinition> beforeHooks = new ArrayList<>();
    private final List<CoreHookDefinition> beforeStepHooks = new ArrayList<>();
    private final List<StepDefinition> stepDefinitions = new ArrayList<>();
    private final List<CoreHookDefinition> afterStepHooks = new ArrayList<>();
    private final List<CoreHookDefinition> afterHooks = new ArrayList<>();
    private final List<StaticHookDefinition> afterAllHooks = new ArrayList<>();

    /*
     * Storing the pattern that matches the step text allows us to cache the
     * rather slow regex comparisons in `stepDefinitionMatches`. This cache does
     * not need to be cleaned. The matching pattern be will used to look up a
     * pickle specific step definition from `stepDefinitionsByPattern`.
     */
    private final Map<String, String> stepPatternByStepText = new HashMap<>();
    private final Map<String, CoreStepDefinition> stepDefinitionsByPattern = new TreeMap<>();

    private final EventBus bus;
    private StepTypeRegistry stepTypeRegistry;
    private Locale locale = null;

    CachingGlue(EventBus bus) {
        this.bus = bus;
    }

    @Override
    public void addBeforeAllHook(StaticHookDefinition beforeAllHook) {
        beforeAllHooks.add(beforeAllHook);
        beforeAllHooks.sort(STATIC_HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addAfterAllHook(StaticHookDefinition afterAllHook) {
        afterAllHooks.add(afterAllHook);
        afterAllHooks.sort(STATIC_HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addStepDefinition(StepDefinition stepDefinition) {
        stepDefinitions.add(stepDefinition);
    }

    @Override
    public void addBeforeHook(HookDefinition hookDefinition) {
        beforeHooks.add(CoreHookDefinition.create(hookDefinition));
        beforeHooks.sort(HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addAfterHook(HookDefinition hookDefinition) {
        afterHooks.add(CoreHookDefinition.create(hookDefinition));
        afterHooks.sort(HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addBeforeStepHook(HookDefinition hookDefinition) {
        beforeStepHooks.add(CoreHookDefinition.create(hookDefinition));
        beforeStepHooks.sort(HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addAfterStepHook(HookDefinition hookDefinition) {
        afterStepHooks.add(CoreHookDefinition.create(hookDefinition));
        afterStepHooks.sort(HOOK_ORDER_ASCENDING);
    }

    @Override
    public void addParameterType(ParameterTypeDefinition parameterType) {
        parameterTypeDefinitions.add(parameterType);
    }

    @Override
    public void addDataTableType(DataTableTypeDefinition dataTableType) {
        dataTableTypeDefinitions.add(dataTableType);
    }

    @Override
    public void addDefaultParameterTransformer(DefaultParameterTransformerDefinition defaultParameterTransformer) {
        defaultParameterTransformers.add(defaultParameterTransformer);
    }

    @Override
    public void addDefaultDataTableEntryTransformer(
            DefaultDataTableEntryTransformerDefinition defaultDataTableEntryTransformer
    ) {
        defaultDataTableEntryTransformers
                .add(CoreDefaultDataTableEntryTransformerDefinition.create(defaultDataTableEntryTransformer));
    }

    @Override
    public void addDefaultDataTableCellTransformer(
            DefaultDataTableCellTransformerDefinition defaultDataTableCellTransformer
    ) {
        defaultDataTableCellTransformers.add(defaultDataTableCellTransformer);
    }

    @Override
    public void addDocStringType(DocStringTypeDefinition docStringType) {
        docStringTypeDefinitions.add(docStringType);
    }

    List<StaticHookDefinition> getBeforeAllHooks() {
        return new ArrayList<>(beforeAllHooks);
    }

    Collection<CoreHookDefinition> getBeforeHooks() {
        return new ArrayList<>(beforeHooks);
    }

    Collection<CoreHookDefinition> getBeforeStepHooks() {
        return new ArrayList<>(beforeStepHooks);
    }

    Collection<CoreHookDefinition> getAfterHooks() {
        List<CoreHookDefinition> hooks = new ArrayList<>(afterHooks);
        Collections.reverse(hooks);
        return hooks;
    }

    Collection<CoreHookDefinition> getAfterStepHooks() {
        List<CoreHookDefinition> hooks = new ArrayList<>(afterStepHooks);
        Collections.reverse(hooks);
        return hooks;
    }

    List<StaticHookDefinition> getAfterAllHooks() {
        ArrayList<StaticHookDefinition> hooks = new ArrayList<>(afterAllHooks);
        Collections.reverse(hooks);
        return hooks;
    }

    Collection<ParameterTypeDefinition> getParameterTypeDefinitions() {
        return parameterTypeDefinitions;
    }

    Collection<DataTableTypeDefinition> getDataTableTypeDefinitions() {
        return dataTableTypeDefinitions;
    }

    Collection<StepDefinition> getStepDefinitions() {
        return stepDefinitions;
    }

    Map<String, String> getStepPatternByStepText() {
        return stepPatternByStepText;
    }

    Map<String, CoreStepDefinition> getStepDefinitionsByPattern() {
        return stepDefinitionsByPattern;
    }

    Collection<DefaultParameterTransformerDefinition> getDefaultParameterTransformers() {
        return defaultParameterTransformers;
    }

    Collection<CoreDefaultDataTableEntryTransformerDefinition> getDefaultDataTableEntryTransformers() {
        return defaultDataTableEntryTransformers;
    }

    Collection<DefaultDataTableCellTransformerDefinition> getDefaultDataTableCellTransformers() {
        return defaultDataTableCellTransformers;
    }

    List<DocStringTypeDefinition> getDocStringTypeDefinitions() {
        return docStringTypeDefinitions;
    }

    StepTypeRegistry getStepTypeRegistry() {
        return stepTypeRegistry;
    }
    int parameterTypeDefinitionsHashCode = 0;
    int stepDefinitionsHashCode = 0;
    int dataTableTypeDefinitionsHashCode = 0;
    int docStringTypeDefinitionsHashCode = 0;

    StepExpressionFactory stepExpressionFactory = null;
    void prepareGlue(Locale locale) throws DuplicateStepDefinitionException {
        int parameterTypeDefinitionsHashCodeNew = parameterTypeDefinitions.hashCode();
        int dataTableTypeDefinitionsHashCodeNew = dataTableTypeDefinitions.hashCode();
        int docStringTypeDefinitionsHashCodeNew = docStringTypeDefinitions.hashCode();
        int stepDefinitionsHashCodeNew = stepDefinitions.hashCode();
        boolean firstTime = stepTypeRegistry == null;
        boolean languageChanged = firstTime || !locale.equals(this.locale);
        boolean parameterTypesDefinitionsChanged = parameterTypeDefinitionsHashCode != parameterTypeDefinitionsHashCodeNew
                || parameterTypeDefinitionsHashCode == 0;
        boolean docStringTypeDefinitionsChanged = docStringTypeDefinitionsHashCode != docStringTypeDefinitionsHashCodeNew
                ||
                docStringTypeDefinitionsHashCode == 0;
        boolean dataTableTypeDefinitionsChanged = dataTableTypeDefinitionsHashCode != dataTableTypeDefinitionsHashCodeNew
                ||
                dataTableTypeDefinitionsHashCode == 0;
        boolean stepDefinitionsChanged = stepDefinitionsHashCodeNew != stepDefinitionsHashCode
                || stepDefinitionsHashCode == 0;
        if (firstTime || languageChanged ||
                parameterTypesDefinitionsChanged || stepDefinitionsChanged ||
                dataTableTypeDefinitionsChanged || docStringTypeDefinitionsChanged) {
            // conditions changed => invalidate the glue cache
            this.locale = locale;
            stepTypeRegistry = new StepTypeRegistry(locale);
            stepExpressionFactory = new StepExpressionFactory(stepTypeRegistry, bus);
            parameterTypesDefinitionsChanged = true;
            stepDefinitionsChanged = true;
            dataTableTypeDefinitionsChanged = true;
            docStringTypeDefinitionsChanged = true;
            parameterTypeDefinitionsHashCode = parameterTypeDefinitionsHashCodeNew;
            dataTableTypeDefinitionsHashCode = dataTableTypeDefinitionsHashCodeNew;
            docStringTypeDefinitionsHashCode = docStringTypeDefinitionsHashCodeNew;
            stepDefinitionsHashCode = stepDefinitionsHashCodeNew;
        }

        stepDefinitionsChanged = true; // TODO comment this line to make build
                                       // fail, e.g. due to
                                       // io.cucumber.core.plugin.PrettyFormatterTest.should_handle_scenario_outline

        // TODO: separate prepared and unprepared glue into different classes
        if (parameterTypesDefinitionsChanged) {
            // parameters changed from the previous scenario => re-register them
            parameterTypeDefinitions.forEach(ptd -> {
                try {
                    ParameterType<?> parameterType = ptd.parameterType();
                    stepTypeRegistry.defineParameterType(parameterType);
                    emitParameterTypeDefined(ptd);
                } catch (DuplicateTypeNameException e) {
                    // do nothing (intentionally)
                    // FIXME catching an exception is a dirty hack, so this
                    // should
                    // be replaced by another mechanism (this would probably
                    // require
                    // to change StepTypeRegistry API)
                }
            });
        }
        if (dataTableTypeDefinitionsChanged) {
            dataTableTypeDefinitions.forEach(dtd -> {
                try {
                    stepTypeRegistry.defineDataTableType(dtd.dataTableType());
                } catch (DuplicateTypeException e) {
                    // do nothing (intentionally)
                    // FIXME catching an exception is a dirty hack, so this
                    // should
                    // be replaced by another mechanism (this would probably
                    // require
                    // to change StepTypeRegistry API)
                }
            });
        }
        if (docStringTypeDefinitionsChanged) {
            docStringTypeDefinitions.forEach(dtd -> {
                try {
                    stepTypeRegistry.defineDocStringType(dtd.docStringType());
                } catch (CucumberDocStringException e) {
                    // do nothing (intentionally)
                    // FIXME catching an exception is a dirty hack, so this
                    // should
                    // be replaced by another mechanism (this would probably
                    // require
                    // to change StepTypeRegistry API)
                }
            });
        }

        if (defaultParameterTransformers.size() == 1) {
            DefaultParameterTransformerDefinition definition = defaultParameterTransformers.get(0);
            ParameterByTypeTransformer transformer = definition.parameterByTypeTransformer();
            stepTypeRegistry.setDefaultParameterTransformer(transformer);
        } else if (defaultParameterTransformers.size() > 1) {
            throw new DuplicateDefaultParameterTransformers(defaultParameterTransformers);
        }

        if (defaultDataTableEntryTransformers.size() == 1) {
            DefaultDataTableEntryTransformerDefinition definition = defaultDataTableEntryTransformers.get(0);
            TableEntryByTypeTransformer transformer = definition.tableEntryByTypeTransformer();
            stepTypeRegistry.setDefaultDataTableEntryTransformer(transformer);
        } else if (defaultDataTableEntryTransformers.size() > 1) {
            throw new DuplicateDefaultDataTableEntryTransformers(defaultDataTableEntryTransformers);
        }

        if (defaultDataTableCellTransformers.size() == 1) {
            DefaultDataTableCellTransformerDefinition definition = defaultDataTableCellTransformers.get(0);
            TableCellByTypeTransformer transformer = definition.tableCellByTypeTransformer();
            stepTypeRegistry.setDefaultDataTableCellTransformer(transformer);
        } else if (defaultDataTableCellTransformers.size() > 1) {
            throw new DuplicateDefaultDataTableCellTransformers(defaultDataTableCellTransformers);
        }

        // TODO: Redefine hooks for each scenario, similar to how we're doing
        // for CoreStepDefinition
        beforeHooks.forEach(this::emitHook);
        beforeStepHooks.forEach(this::emitHook);

        if (stepDefinitionsChanged) {
            stepDefinitions.forEach(stepDefinition -> {
                StepExpression expression = stepExpressionFactory.createExpression(stepDefinition);
                CoreStepDefinition coreStepDefinition = new CoreStepDefinition(bus.generateId(), stepDefinition,
                    expression);
                CoreStepDefinition previous = stepDefinitionsByPattern.get(stepDefinition.getPattern());
                if (previous != null) {
                    throw new DuplicateStepDefinitionException(previous, stepDefinition);
                }
                stepDefinitionsByPattern.put(coreStepDefinition.getExpression().getSource(), coreStepDefinition);
                emitStepDefined(coreStepDefinition);
            });
        }

        afterStepHooks.forEach(this::emitHook);
        afterHooks.forEach(this::emitHook);
    }

    private void emitParameterTypeDefined(ParameterTypeDefinition parameterTypeDefinition) {
        ParameterType<?> parameterType = parameterTypeDefinition.parameterType();
        io.cucumber.messages.types.ParameterType messagesParameterType = new io.cucumber.messages.types.ParameterType(
            parameterType.getName(),
            parameterType.getRegexps(),
            parameterType.preferForRegexpMatch(),
            parameterType.useForSnippets(),
            bus.generateId().toString(),
            parameterTypeDefinition.getSourceReference()
                    .map(this::createSourceReference)
                    .orElseGet(this::emptySourceReference));
        bus.send(Envelope.of(messagesParameterType));
    }

    private void emitHook(CoreHookDefinition coreHook) {
        Hook messagesHook = new Hook(
            coreHook.getId().toString(),
            null,
            coreHook.getDefinitionLocation()
                    .map(this::createSourceReference)
                    .orElseGet(this::emptySourceReference),
            coreHook.getTagExpression());
        bus.send(Envelope.of(messagesHook));
    }

    private void emitStepDefined(CoreStepDefinition coreStepDefinition) {
        bus.send(new StepDefinedEvent(
            bus.getInstant(),
            new io.cucumber.plugin.event.StepDefinition(
                coreStepDefinition.getStepDefinition().getLocation(),
                coreStepDefinition.getExpression().getSource())));

        io.cucumber.messages.types.StepDefinition messagesStepDefinition = new io.cucumber.messages.types.StepDefinition(
            coreStepDefinition.getId().toString(),
            new StepDefinitionPattern(
                coreStepDefinition.getExpression().getSource(),
                getExpressionType(coreStepDefinition)),
            coreStepDefinition.getDefinitionLocation()
                    .map(this::createSourceReference)
                    .orElseGet(this::emptySourceReference));
        bus.send(Envelope.of(messagesStepDefinition));
    }

    private SourceReference createSourceReference(io.cucumber.core.backend.SourceReference reference) {
        if (reference instanceof JavaMethodReference) {
            JavaMethodReference methodReference = (JavaMethodReference) reference;
            return SourceReference.of(new JavaMethod(
                methodReference.className(),
                methodReference.methodName(),
                methodReference.methodParameterTypes()));
        }

        if (reference instanceof StackTraceElementReference) {
            StackTraceElementReference stackReference = (StackTraceElementReference) reference;
            JavaStackTraceElement stackTraceElement = new JavaStackTraceElement(
                stackReference.className(),
                // TODO: Fix json schema. Stacktrace elements need not have a
                // source file
                stackReference.fileName().orElse("Unknown"),
                stackReference.methodName());
            Location location = new Location((long) stackReference.lineNumber(), null);
            return new SourceReference(null, null, stackTraceElement, location);
        }
        return emptySourceReference();
    }

    private SourceReference emptySourceReference() {
        return new SourceReference(null, null, null, null);
    }

    private StepDefinitionPatternType getExpressionType(CoreStepDefinition stepDefinition) {
        Class<? extends Expression> expressionType = stepDefinition.getExpression().getExpressionType();
        if (expressionType.isAssignableFrom(RegularExpression.class)) {
            return StepDefinitionPatternType.REGULAR_EXPRESSION;
        } else if (expressionType.isAssignableFrom(CucumberExpression.class)) {
            return StepDefinitionPatternType.CUCUMBER_EXPRESSION;
        } else {
            throw new IllegalArgumentException(expressionType.getName());
        }
    }

    PickleStepDefinitionMatch stepDefinitionMatch(URI uri, Step step) throws AmbiguousStepDefinitionsException {
        PickleStepDefinitionMatch cachedMatch = cachedStepDefinitionMatch(uri, step);
        if (cachedMatch != null) {
            return cachedMatch;
        }
        return findStepDefinitionMatch(uri, step);
    }

    private PickleStepDefinitionMatch cachedStepDefinitionMatch(URI uri, Step step) {
        String stepDefinitionPattern = stepPatternByStepText.get(step.getText());
        if (stepDefinitionPattern == null) {
            return null;
        }

        CoreStepDefinition coreStepDefinition = stepDefinitionsByPattern.get(stepDefinitionPattern);
        if (coreStepDefinition == null) {
            return null;
        }

        // Step definition arguments consists of parameters included in the step
        // text and
        // gherkin step arguments (doc string and data table) which are not
        // included in
        // the step text. As such the step definition arguments can not be
        // cached and
        // must be recreated each time.
        List<Argument> arguments = coreStepDefinition.matchedArguments(step);
        return new PickleStepDefinitionMatch(arguments, coreStepDefinition, uri, step);
    }

    private PickleStepDefinitionMatch findStepDefinitionMatch(URI uri, Step step)
            throws AmbiguousStepDefinitionsException {
        List<PickleStepDefinitionMatch> matches = stepDefinitionMatches(uri, step);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new AmbiguousStepDefinitionsException(step, matches);
        }

        PickleStepDefinitionMatch match = matches.get(0);

        stepPatternByStepText.put(step.getText(), match.getPattern());

        return match;
    }

    private List<PickleStepDefinitionMatch> stepDefinitionMatches(URI uri, Step step) {
        List<PickleStepDefinitionMatch> result = new ArrayList<>();
        for (CoreStepDefinition coreStepDefinition : stepDefinitionsByPattern.values()) {
            List<Argument> arguments = coreStepDefinition.matchedArguments(step);
            if (arguments != null) {
                result.add(new PickleStepDefinitionMatch(arguments, coreStepDefinition, uri, step));
            }
        }
        return result;
    }

    void removeScenarioScopedGlue() {
        removeScenarioScopedGlue(beforeHooks);
        removeScenarioScopedGlue(beforeStepHooks);
        removeScenarioScopedGlue(afterHooks);
        removeScenarioScopedGlue(afterStepHooks);
        removeScenarioScopedGlue(stepDefinitions);
        removeScenarioScopedGlue(dataTableTypeDefinitions);
        removeScenarioScopedGlue(docStringTypeDefinitions);
        removeScenarioScopedGlue(parameterTypeDefinitions);
        removeScenarioScopedGlue(defaultParameterTransformers);
        removeScenarioScopedGlue(defaultDataTableEntryTransformers);
        removeScenarioScopedGlue(defaultDataTableCellTransformers);

        stepDefinitionsByPattern.clear();

    }

    private boolean removeScenarioScopedGlue(Iterable<?> glues) {
        boolean dirty = false;
        Iterator<?> glueIterator = glues.iterator();
        while (glueIterator.hasNext()) {
            Object glue = glueIterator.next();
            if (glue instanceof ScenarioScoped) {
                ScenarioScoped scenarioScoped = (ScenarioScoped) glue;
                scenarioScoped.dispose();
                glueIterator.remove();
                dirty = true;
            }
        }
        return dirty;
    }

}
