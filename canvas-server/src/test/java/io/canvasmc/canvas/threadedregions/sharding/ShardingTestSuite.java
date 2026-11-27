package io.canvasmc.canvas.threadedregions.sharding;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Canvas intra-region sharding tests")
@SelectPackages("io.canvasmc.canvas.threadedregions.sharding")
@ExcludeClassNamePatterns(".*TestSuite")
public class ShardingTestSuite {
}
