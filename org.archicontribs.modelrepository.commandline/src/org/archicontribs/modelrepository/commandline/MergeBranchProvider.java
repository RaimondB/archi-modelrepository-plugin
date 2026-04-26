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
import org.archicontribs.modelrepository.services.RepositoryService.MergeBranchResult;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.commandline.AbstractCommandLineProvider;
import com.archimatetool.editor.utils.StringUtils;

/**
 * Command Line interface for merging a branch into the current branch.
 * <p>
 * Delegates to {@link RepositoryService#mergeBranch} for the actual operation.
 * Uses {@link HeadlessMergeHandler} which aborts on conflicts.
 * <p>
 * Usage (should be all on one line):
 * <pre>
 * Archi -consoleLog -nosplash -application com.archimatetool.commandline.app
 *   --modelrepository.loadModel "cloneFolder"
 *   --modelrepository.mergeBranch "cloneFolder" --modelrepository.branch "feature-x"
 * </pre>
 */
@SuppressWarnings("nls")
public class MergeBranchProvider extends AbstractCommandLineProvider {

    static final String PREFIX = Messages.MergeBranchProvider_0;

    static final String OPTION_MERGE_BRANCH = "modelrepository.mergeBranch"; //$NON-NLS-1$
    static final String OPTION_BRANCH = "modelrepository.branch"; //$NON-NLS-1$

    private final RepositoryService repositoryService = new RepositoryService();

    public MergeBranchProvider() {
    }

    @Override
    public void run(CommandLine commandLine) throws Exception {
        if(!hasCorrectOptions(commandLine)) {
            return;
        }

        String sFolder = commandLine.getOptionValue(OPTION_MERGE_BRANCH);
        if(!StringUtils.isSet(sFolder)) {
            logError(NLS.bind(Messages.MergeBranchProvider_1, OPTION_MERGE_BRANCH));
            return;
        }

        File cloneFolder = new File(sFolder);
        if(!cloneFolder.exists() || !cloneFolder.isDirectory()) {
            logError(NLS.bind(Messages.MergeBranchProvider_2, sFolder));
            return;
        }

        String branchName = commandLine.getOptionValue(OPTION_BRANCH);
        if(!StringUtils.isSet(branchName)) {
            logError(NLS.bind(Messages.MergeBranchProvider_3, OPTION_BRANCH));
            return;
        }

        IArchiRepository repo = new ArchiRepository(cloneFolder);

        // Find branches
        BranchStatus branchStatus = repo.getBranchStatus();
        BranchInfo currentBranch = branchStatus.getCurrentLocalBranch();
        BranchInfo branchToMerge = null;

        for(BranchInfo bi : branchStatus.getLocalAndUntrackedRemoteBranches()) {
            if(bi.getShortName().equals(branchName)) {
                branchToMerge = bi;
                break;
            }
        }

        if(branchToMerge == null) {
            logError(NLS.bind(Messages.MergeBranchProvider_4, branchName));
            return;
        }

        logMessage(NLS.bind(Messages.MergeBranchProvider_5, branchName, currentBranch.getShortName()));

        MergeBranchResult result = repositoryService.mergeBranch(repo, currentBranch,
                branchToMerge, new HeadlessMergeHandler(), null);

        switch(result.status()) {
            case OK:
                logMessage(NLS.bind(Messages.MergeBranchProvider_6, result.conflictCount()));
                break;
            case UP_TO_DATE:
                logMessage(Messages.MergeBranchProvider_7);
                break;
            case MERGE_CANCELLED:
                logMessage(Messages.MergeBranchProvider_8);
                break;
            case ERROR:
                logError(Messages.MergeBranchProvider_9);
                break;
        }
    }

    @Override
    public Options getOptions() {
        Options options = new Options();

        Option option = Option.builder()
                .longOpt(OPTION_MERGE_BRANCH)
                .hasArg()
                .argName(Messages.MergeBranchProvider_10)
                .desc(NLS.bind(Messages.MergeBranchProvider_11, OPTION_MERGE_BRANCH))
                .build();
        options.addOption(option);

        option = Option.builder()
                .longOpt(OPTION_BRANCH)
                .hasArg()
                .argName(Messages.MergeBranchProvider_12)
                .desc(NLS.bind(Messages.MergeBranchProvider_13, OPTION_MERGE_BRANCH))
                .build();
        options.addOption(option);

        return options;
    }

    private boolean hasCorrectOptions(CommandLine commandLine) {
        return commandLine.hasOption(OPTION_MERGE_BRANCH);
    }

    @Override
    public int getPriority() {
        return PRIORITY_SAVE_MODEL + 15; // Run after commit, before pull/push
    }

    @Override
    protected String getLogPrefix() {
        return PREFIX;
    }
}
