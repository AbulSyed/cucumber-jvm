package io.cucumber.compatibility.hooks;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.When;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Hooks {

    @Before
    public void before() {
    }

    @When("a step passes")
    public void aStepPasses() {
    }

    @When("a step fails")
    public void aStepFails() throws Exception {
        throw new Exception("Exception in step");
    }

    @After
    public void after() throws Exception {
        throw new Exception("Exception in hook");
    }

    @After("@some-tag or @some-other-tag")
    public void afterTaggedWithException() throws Exception {
        throw new Exception("Exception in conditional hook");
    }

    @After("@with-attachment")
    public void afterWithAttachment(Scenario scenario) throws Exception {
        Path path = Paths.get("src/test/resources/features/hooks/cucumber.svg");
        byte[] bytes = Files.readAllBytes(path);
        scenario.attach(bytes, "image/svg", null);
    }

}
