package io.cucumber.java8;

import io.cucumber.core.backend.DataTableTypeDefinition;
import io.cucumber.core.backend.DefaultDataTableCellTransformerDefinition;
import io.cucumber.core.backend.DefaultDataTableEntryTransformerDefinition;
import io.cucumber.core.backend.DefaultParameterTransformerDefinition;
import io.cucumber.core.backend.DocStringTypeDefinition;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.ParameterTypeDefinition;
import io.cucumber.core.backend.StepDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class ClosureAwareGlueRegistry implements LambdaGlueRegistry {

    private final List<AbstractGlueDefinition> definitions = new ArrayList<>();
    private int registered;

    private final Glue glue;

    ClosureAwareGlueRegistry(Glue glue) {
        this.glue = glue;
    }

    void startRegistration() {
        registered = 0;
    }

    @Override
    public void addStepDefinition(StepDefinition stepDefinition) {
        updateOrRegister((Java8StepDefinition) stepDefinition, definitions, glue::addStepDefinition);
    }

    @Override
    public void addBeforeStepHookDefinition(HookDefinition beforeStepHook) {
        updateOrRegister((Java8HookDefinition) beforeStepHook, definitions, glue::addBeforeStepHook);

    }

    @Override
    public void addAfterStepHookDefinition(HookDefinition afterStepHook) {
        updateOrRegister((Java8HookDefinition) afterStepHook, definitions, glue::addAfterStepHook);
    }

    @Override
    public void addBeforeHookDefinition(HookDefinition beforeHook) {
        updateOrRegister((Java8HookDefinition) beforeHook, definitions, glue::addBeforeHook);
    }

    @Override
    public void addAfterHookDefinition(HookDefinition afterHook) {
        updateOrRegister((Java8HookDefinition) afterHook, definitions, glue::addAfterHook);
    }

    @Override
    public void addDocStringType(DocStringTypeDefinition docStringType) {
        updateOrRegister((Java8DocStringTypeDefinition) docStringType, definitions, glue::addDocStringType);
    }

    @Override
    public void addDataTableType(DataTableTypeDefinition dataTableType) {
        updateOrRegister((Java8DataTableTypeDefinition) dataTableType, definitions, glue::addDataTableType);
    }

    @Override
    public void addParameterType(ParameterTypeDefinition parameterType) {
        updateOrRegister((Java8ParameterTypeDefinition) parameterType, definitions, glue::addParameterType);
    }

    @Override
    public void addDefaultParameterTransformer(DefaultParameterTransformerDefinition defaultParameterTransformer) {
        updateOrRegister((Java8DefaultParameterTransformerDefinition) defaultParameterTransformer, definitions,
            glue::addDefaultParameterTransformer);
    }

    @Override
    public void addDefaultDataTableCellTransformer(
            DefaultDataTableCellTransformerDefinition defaultDataTableCellTransformer
    ) {
        updateOrRegister((Java8DefaultDataTableCellTransformerDefinition) defaultDataTableCellTransformer, definitions,
            glue::addDefaultDataTableCellTransformer);
    }

    @Override
    public void addDefaultDataTableEntryTransformer(
            DefaultDataTableEntryTransformerDefinition defaultDataTableEntryTransformer
    ) {
        updateOrRegister(
            (Java8DefaultDataTableEntryTransformerDefinition) defaultDataTableEntryTransformer,
            definitions,
            glue::addDefaultDataTableEntryTransformer);
    }

    private <T extends AbstractGlueDefinition> void updateOrRegister(
            T definition, List<AbstractGlueDefinition> definitions, Consumer<T> register
    ) {
        if (definitions.size() <= registered) {
            definitions.add(definition);
            register.accept(definition);
        } else {
            AbstractGlueDefinition existing = definitions.get(registered);
            existing.updateClosure(definition);
        }
        registered++;
    }

    void disposeClosures() {
        definitions.forEach(AbstractGlueDefinition::disposeClosure);
    }
}
