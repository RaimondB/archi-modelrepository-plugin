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
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.CommitResult;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.commandline.CommandLineState;
import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Command Line interface for committing changes to a repository.
 * <p>
 * Delegates to {@link RepositoryService#commit} for the actual operation.
 * <p>
 * Usage (should be all on one line):
 * <pre>
 * Archi -consoleLog -nosplash -application com.archimatetool.commandline.app
 *   --modelrepository.loadModel "cloneFolder"
 *   --modelrepository.commitModel "cloneFolder" --modelrepository.commitMessage "my commit message"
 * </pre>
 * 
 * This will export the active model to GRAFICO files and commit the changes.
 * The model must be loaded first via --modelrepository.loadModel or --modelrepository.cloneModel.
 */
public class CommitModelProvider extends AbstractCommandLineProvider {

    static final String PREFIX = Messages.CommitModelProvider_0;
    
    static final String OPTION_COMMIT_MODEL = "modelrepository.commitModel"; //$NON-NLS-1$
    static final String OPTION_COMMIT_MESSAGE = "modelrepository.commitMessage"; //$NON-NLS-1$
    static final String OPTION_AMEND = "modelrepository.amend"; //$NON-NLS-1$
    
    private final RepositoryService repositoryService = new RepositoryService();
    
    public CommitModelProvider() {
    }
    
    @Override
    public void run(CommandLine commandLine) throws Exception {
        if(!hasCorrectOptions(commandLine)) {
            return;
        }
        
        String sFolder = commandLine.getOptionValue(OPTION_COMMIT_MODEL);
        if(!StringUtils.isSet(sFolder)) {
            logError(NLS.bind(Messages.CommitModelProvider_1, OPTION_COMMIT_MODEL));
            return;
        }
        
        File cloneFolder = new File(sFolder);
        if(!cloneFolder.exists() || !cloneFolder.isDirectory()) {
            logError(NLS.bind(Messages.CommitModelProvider_2, sFolder));
            return;
        }
        
        String commitMessage = commandLine.getOptionValue(OPTION_COMMIT_MESSAGE);
        if(!StringUtils.isSet(commitMessage)) {
            logError(NLS.bind(Messages.CommitModelProvider_3, OPTION_COMMIT_MESSAGE));
            return;
        }
        
        boolean amend = commandLine.hasOption(OPTION_AMEND);
        
        // Get the active model (must have been loaded by a prior command)
        IArchimateModel model = CommandLineState.getModel();
        if(model == null) {
            logError(Messages.CommitModelProvider_4);
            return;
        }
        
        IArchiRepository repo = new ArchiRepository(cloneFolder);
        
        logMessage(NLS.bind(Messages.CommitModelProvider_5, model.getName()));
        
        CommitResult result = repositoryService.commit(repo, model, commitMessage, amend, null);
        
        switch(result.status()) {
            case COMMITTED:
                logMessage(NLS.bind(Messages.CommitModelProvider_6, result.commitId()));
                break;
            case NOTHING_TO_COMMIT:
                logMessage(Messages.CommitModelProvider_7);
                break;
        }
    }
    
    @Override
    public Options getOptions() {
        Options options = new Options();
        
        Option option = Option.builder()
                .longOpt(OPTION_COMMIT_MODEL)
                .hasArg()
                .argName(Messages.CommitModelProvider_8)
                .desc(NLS.bind(Messages.CommitModelProvider_9, OPTION_COMMIT_MODEL))
                .build();
        options.addOption(option);
        
        option = Option.builder()
                .longOpt(OPTION_COMMIT_MESSAGE)
                .hasArg()
                .argName(Messages.CommitModelProvider_10)
                .desc(NLS.bind(Messages.CommitModelProvider_11, OPTION_COMMIT_MODEL))
                .build();
        options.addOption(option);
        
        option = Option.builder()
                .longOpt(OPTION_AMEND)
                .desc(Messages.CommitModelProvider_12)
                .build();
        options.addOption(option);
        
        return options;
    }
    
    private boolean hasCorrectOptions(CommandLine commandLine) {
        return commandLine.hasOption(OPTION_COMMIT_MODEL);
    }
    
    @Override
    public int getPriority() {
        return PRIORITY_SAVE_MODEL + 10; // Run after save, before any push
    }
    
    @Override
    protected String getLogPrefix() {
        return PREFIX;
    }
}
