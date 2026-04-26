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
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Full test suite including performance tests.
 * Run with: mvn verify -Dinclude.perf.tests=true
 */
@Suite
@SelectClasses({
    ArchiRepositoryTests.class,
    GraficoModelLoaderTests.class,
    GraficoUtilsTests.class,
    CryptoDataTests.class,
    MergeConflictHandlerTests.class,
    RemoteIntegrationTests.class
})
@SuiteDisplayName("All Model Repository Tests (including performance)")
public class AllTestsWithPerformance {
}
