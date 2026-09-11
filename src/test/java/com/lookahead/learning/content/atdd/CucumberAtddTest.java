package com.lookahead.learning.content.atdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("atdd")
@ConfigurationParameter(key = "cucumber.glue", value = "com.lookahead.learning.content.atdd")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty,json:target/cucumber/report.json")
public class CucumberAtddTest { }
