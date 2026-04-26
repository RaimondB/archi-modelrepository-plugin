/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository;


import org.archicontribs.modelrepository.authentication.CryptoDataTests;
import org.archicontribs.modelrepository.grafico.ArchiRepositoryTests;
import org.archicontribs.modelrepository.grafico.GraficoModelLoaderTests;
import org.archicontribs.modelrepository.grafico.GraficoUtilsTests;
import org.archicontribs.modelrepository.grafico.RemoteIntegrationTests;
import org.archicontribs.modelrepository.merge.MergeConflictHandlerTests;
import org.archicontribs.modelrepository.services.RepositoryServiceTests;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Default test suite — excludes performance tests for fast build cycles.
 * Run with: mvn verify
 * 
 * To include performance tests: mvn verify -Dinclude.perf.tests=true
 * (This switches to AllTestsWithPerformance suite via Maven profile)
 */
@Suite
@SelectClasses({
    ArchiRepositoryTests.class,
    GraficoModelLoaderTests.class,
    GraficoUtilsTests.class,
    CryptoDataTests.class,
    MergeConflictHandlerTests.class,
    RemoteIntegrationTests.class,
    RepositoryServiceTests.class
})
@ExcludeTags("performance")
@SuiteDisplayName("All Model Repository Tests")
public class AllTests {
}
