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
import org.archicontribs.modelrepository.services.RepositoryService.PublishResult;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.editor.utils.StringUtils;

/**
 * Command Line interface for publishing (pull + push) a repository.
 * <p>
 * Delegates to {@link RepositoryService#publish} for the actual operation.
 * Uses {@link HeadlessMergeHandler} which aborts on conflicts.
 * <p>
 * Usage (should be all on one line):
 * <pre>
 * Archi -consoleLog -nosplash -application com.archimatetool.commandline.app
 *   --modelrepository.loadModel "cloneFolder"
 *   --modelrepository.commitModel "cloneFolder" --modelrepository.commitMessage "msg"
 *   --modelrepository.pushModel "cloneFolder"
 * </pre>
 */
public class PushModelProvider extends AbstractCommandLineProvider {

    static final String PREFIX = Messages.PushModelProvider_0;
    
    static final String OPTION_PUSH_MODEL = "modelrepository.pushModel"; //$NON-NLS-1$
    
    private final RepositoryService repositoryService = new RepositoryService();
    
    public PushModelProvider() {
    }
    
    @Override
    public void run(CommandLine commandLine) throws Exception {
        if(!hasCorrectOptions(commandLine)) {
            return;
        }
        
        String sFolder = commandLine.getOptionValue(OPTION_PUSH_MODEL);
        if(!StringUtils.isSet(sFolder)) {
            logError(NLS.bind(Messages.PushModelProvider_1, OPTION_PUSH_MODEL));
            return;
        }
        
        File cloneFolder = new File(sFolder);
        if(!cloneFolder.exists() || !cloneFolder.isDirectory()) {
            logError(NLS.bind(Messages.PushModelProvider_2, sFolder));
            return;
        }
        
        IArchiRepository repo = new ArchiRepository(cloneFolder);
        
        logMessage(NLS.bind(Messages.PushModelProvider_3, cloneFolder));
        
        // Use null credentials — native git / GCM handles auth in headless mode
        PublishResult result = repositoryService.publish(repo, null,
                new HeadlessMergeHandler(), null);
        
        switch(result.status()) {
            case OK:
                logMessage(Messages.PushModelProvider_4);
                break;
            case UP_TO_DATE:
                logMessage(Messages.PushModelProvider_5);
                break;
            case MERGE_CANCELLED:
                logMessage(Messages.PushModelProvider_6);
                break;
            case PUSH_ERROR:
                logError(NLS.bind(Messages.PushModelProvider_7,
                        result.pushErrors() != null ? result.pushErrors() : "")); //$NON-NLS-1$
                break;
        }
    }
    
    @Override
    public Options getOptions() {
        Options options = new Options();
        
        Option option = Option.builder()
                .longOpt(OPTION_PUSH_MODEL)
                .hasArg()
                .argName(Messages.PushModelProvider_8)
                .desc(NLS.bind(Messages.PushModelProvider_9, OPTION_PUSH_MODEL))
                .build();
        options.addOption(option);
        
        return options;
    }
    
    private boolean hasCorrectOptions(CommandLine commandLine) {
        return commandLine.hasOption(OPTION_PUSH_MODEL);
    }
    
    @Override
    public int getPriority() {
        return PRIORITY_SAVE_MODEL + 30; // Run after pull
    }
    
    @Override
    protected String getLogPrefix() {
        return PREFIX;
    }
}
