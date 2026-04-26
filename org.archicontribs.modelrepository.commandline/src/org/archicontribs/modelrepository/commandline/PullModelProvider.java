/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.commandline;

import java.io.File;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.services.HeadlessMergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.RefreshResult;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.editor.utils.StringUtils;

/**
 * Command Line interface for pulling (refreshing) a repository.
 * <p>
 * Delegates to {@link RepositoryService#refresh} for the actual operation.
 * Uses {@link HeadlessMergeHandler} which aborts on conflicts.
 * <p>
 * Usage (should be all on one line):
 * <pre>
 * Archi -consoleLog -nosplash -application com.archimatetool.commandline.app
 *   --modelrepository.loadModel "cloneFolder"
 *   --modelrepository.pullModel "cloneFolder"
 * </pre>
 */
public class PullModelProvider extends AbstractCommandLineProvider {

    static final String PREFIX = Messages.PullModelProvider_0;
    
    static final String OPTION_PULL_MODEL = "modelrepository.pullModel"; //$NON-NLS-1$
    
    private final RepositoryService repositoryService = new RepositoryService();
    
    public PullModelProvider() {
    }
    
    @Override
    public void run(CommandLine commandLine) throws Exception {
        if(!hasCorrectOptions(commandLine)) {
            return;
        }
        
        String sFolder = commandLine.getOptionValue(OPTION_PULL_MODEL);
        if(!StringUtils.isSet(sFolder)) {
            logError(NLS.bind(Messages.PullModelProvider_1, OPTION_PULL_MODEL));
            return;
        }
        
        File cloneFolder = new File(sFolder);
        if(!cloneFolder.exists() || !cloneFolder.isDirectory()) {
            logError(NLS.bind(Messages.PullModelProvider_2, sFolder));
            return;
        }
        
        IArchiRepository repo = new ArchiRepository(cloneFolder);
        
        logMessage(NLS.bind(Messages.PullModelProvider_3, cloneFolder));
        
        // Use null credentials — native git / GCM handles auth in headless mode
        RefreshResult result = repositoryService.refresh(repo, null,
                new HeadlessMergeHandler(), null);
        
        switch(result.status()) {
            case OK:
                logMessage(Messages.PullModelProvider_4);
                break;
            case UP_TO_DATE:
                logMessage(Messages.PullModelProvider_5);
                break;
            case MERGE_CANCELLED:
                logMessage(Messages.PullModelProvider_6);
                break;
            case ERROR:
                logError(Messages.PullModelProvider_7);
                break;
        }
    }
    
    @Override
    public Options getOptions() {
        Options options = new Options();
        
        Option option = Option.builder()
                .longOpt(OPTION_PULL_MODEL)
                .hasArg()
                .argName(Messages.PullModelProvider_8)
                .desc(NLS.bind(Messages.PullModelProvider_9, OPTION_PULL_MODEL))
                .build();
        options.addOption(option);
        
        return options;
    }
    
    private boolean hasCorrectOptions(CommandLine commandLine) {
        return commandLine.hasOption(OPTION_PULL_MODEL);
    }
    
    @Override
    public int getPriority() {
        return PRIORITY_SAVE_MODEL + 20; // Run after commit, before push
    }
    
    @Override
    protected String getLogPrefix() {
        return PREFIX;
    }
}
