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
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.services.HeadlessMergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.SwitchResult;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.editor.utils.StringUtils;

/**
 * Command Line interface for switching branches.
 * <p>
 * Delegates to {@link RepositoryService#switchBranch} for the actual operation.
 * <p>
 * Usage (should be all on one line):
 * <pre>
 * Archi -consoleLog -nosplash -application com.archimatetool.commandline.app
 *   --modelrepository.loadModel "cloneFolder"
 *   --modelrepository.switchBranch "cloneFolder" --modelrepository.branch "feature-x"
 * </pre>
 */
@SuppressWarnings("nls")
public class SwitchBranchProvider extends AbstractCommandLineProvider {

    static final String PREFIX = Messages.SwitchBranchProvider_0;

    static final String OPTION_SWITCH_BRANCH = "modelrepository.switchBranch"; //$NON-NLS-1$
    static final String OPTION_BRANCH = "modelrepository.branch"; //$NON-NLS-1$

    private final RepositoryService repositoryService = new RepositoryService();

    public SwitchBranchProvider() {
    }

    @Override
    public void run(CommandLine commandLine) throws Exception {
        if(!hasCorrectOptions(commandLine)) {
            return;
        }

        String sFolder = commandLine.getOptionValue(OPTION_SWITCH_BRANCH);
        if(!StringUtils.isSet(sFolder)) {
            logError(NLS.bind(Messages.SwitchBranchProvider_1, OPTION_SWITCH_BRANCH));
            return;
        }

        File cloneFolder = new File(sFolder);
        if(!cloneFolder.exists() || !cloneFolder.isDirectory()) {
            logError(NLS.bind(Messages.SwitchBranchProvider_2, sFolder));
            return;
        }

        String branchName = commandLine.getOptionValue(OPTION_BRANCH);
        if(!StringUtils.isSet(branchName)) {
            logError(NLS.bind(Messages.SwitchBranchProvider_3, OPTION_BRANCH));
            return;
        }

        IArchiRepository repo = new ArchiRepository(cloneFolder);

        // Find the branch
        BranchStatus branchStatus = repo.getBranchStatus();
        BranchInfo targetBranch = null;

        for(BranchInfo bi : branchStatus.getLocalAndUntrackedRemoteBranches()) {
            if(bi.getShortName().equals(branchName)) {
                targetBranch = bi;
                break;
            }
        }

        if(targetBranch == null) {
            logError(NLS.bind(Messages.SwitchBranchProvider_4, branchName));
            return;
        }

        logMessage(NLS.bind(Messages.SwitchBranchProvider_5, branchName));

        SwitchResult result = repositoryService.switchBranch(repo, targetBranch, true,
                new HeadlessMergeHandler(), null);

        switch(result.status()) {
            case OK:
                logMessage(NLS.bind(Messages.SwitchBranchProvider_6, branchName));
                break;
            case ALREADY_ON_BRANCH:
                logMessage(NLS.bind(Messages.SwitchBranchProvider_6, branchName));
                break;
        }
    }

    @Override
    public Options getOptions() {
        Options options = new Options();

        Option option = Option.builder()
                .longOpt(OPTION_SWITCH_BRANCH)
                .hasArg()
                .argName(Messages.SwitchBranchProvider_7)
                .desc(NLS.bind(Messages.SwitchBranchProvider_8, OPTION_SWITCH_BRANCH))
                .build();
        options.addOption(option);

        option = Option.builder()
                .longOpt(OPTION_BRANCH)
                .hasArg()
                .argName(Messages.SwitchBranchProvider_9)
                .desc(NLS.bind(Messages.SwitchBranchProvider_10, OPTION_SWITCH_BRANCH))
                .build();
        options.addOption(option);

        return options;
    }

    private boolean hasCorrectOptions(CommandLine commandLine) {
        return commandLine.hasOption(OPTION_SWITCH_BRANCH);
    }

    @Override
    public int getPriority() {
        return PRIORITY_SAVE_MODEL + 5; // Run before commit/pull/push
    }

    @Override
    protected String getLogPrefix() {
        return PREFIX;
    }
}
