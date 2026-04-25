/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * BranchInfo
 * 
 * @author Phillip Beauvoir
 */
public class BranchInfo {
    
    private final static String LOCAL_PREFIX = Constants.R_HEADS;
    private final static String REMOTE_PREFIX = Constants.R_REMOTES + IGraficoConstants.ORIGIN + "/"; //$NON-NLS-1$

    private Ref ref;
    
    private String shortName;
    
    private boolean isRemoteDeleted;
    private boolean isCurrentBranch;
    private boolean hasLocalRef;
    private boolean hasRemoteRef;
    private boolean hasUnpushedCommits;
    private boolean hasRemoteCommits;
    private boolean isMerged;
    
    private RevCommit latestCommit;
    
    private File repoDir; 
    
    BranchInfo(Repository repository, Ref ref) throws IOException, GitAPIException {
        repoDir = repository.getWorkTree();
        init(repository, ref);
    }
    
    /**
     * Initialise this BranchInfo from the Repository and the Ref.
     * isMerged is computed separately by BranchStatus in a single pass.
     */
    private void init(Repository repository, Ref ref) throws IOException, GitAPIException {
        this.ref = ref.getTarget(); // Important! Get the target in case it's a symbolic Ref
        
        hasLocalRef = getHasLocalRef(repository);
        hasRemoteRef = getHasRemoteRef(repository);

        getCommitStatus(repository);
        
        isRemoteDeleted = getIsRemoteDeleted(repository);
        isCurrentBranch = getIsCurrentBranch(repository);
        
        getLatestCommitStatus(repository);
    }
    
    /**
     * Set merged status. Called by BranchStatus after computing isMerged for all branches.
     */
    void setMerged(boolean merged) {
        this.isMerged = merged;
    }
    
    public Ref getRef() {
        return ref;
    }
    
    public String getFullName() {
        return ref.getName();
    }
    
    public String getShortName() {
        if(shortName == null) {
            shortName = getShortName(getFullName());
        }
        return shortName;
    }
    
    public boolean isLocal() {
        return getFullName().startsWith(LOCAL_PREFIX);
    }

    public boolean isRemote() {
        return getFullName().startsWith(REMOTE_PREFIX);
    }

    public boolean hasLocalRef() {
        return hasLocalRef;
    }

    public boolean hasRemoteRef() {
        return hasRemoteRef;
    }

    public boolean isRemoteDeleted() {
        return isRemoteDeleted;
    }

    public boolean isCurrentBranch() {
        return isCurrentBranch;
    }
    
    public String getRemoteBranchNameFor() {
        return REMOTE_PREFIX + getShortName();
    }
    
    public String getLocalBranchNameFor() {
        return LOCAL_PREFIX + getShortName();
    }
    
    public RevCommit getLatestCommit() {
        return latestCommit;
    }

    public boolean hasRemoteCommits() {
        return hasRemoteCommits;
    }
    
    public boolean hasUnpushedCommits() {
        return hasUnpushedCommits;
    }
    
    public boolean isMerged() {
        return isMerged;
    }
    
    public boolean isMasterBranch() {
        return IGraficoConstants.MASTER.equals(getShortName());
    }
    
    private boolean getHasLocalRef(Repository repository) throws IOException {
        return repository.findRef(getLocalBranchNameFor()) != null;
    }

    private boolean getHasRemoteRef(Repository repository) throws IOException {
        return repository.findRef(getRemoteBranchNameFor()) != null;
    }

    /*
     * Figure out whether the remote branch has been deleted
     * 1. We have a local branch ref
     * 2. We are tracking it
     * 3. But it does not have a remote branch ref
     */
    private boolean getIsRemoteDeleted(Repository repository) throws IOException {
        if(isRemote()) {
            return false;
        }
        
        // Is it being tracked?
        BranchConfig branchConfig = new BranchConfig(repository.getConfig(), getShortName());
        boolean isBeingTracked = branchConfig.getRemoteTrackingBranch() != null;
        
        // Does it have a remote ref?
        boolean hasNoRemoteBranchFor = repository.findRef(getRemoteBranchNameFor()) == null;
        
        // Is being tracked but no remote ref
        return isBeingTracked && hasNoRemoteBranchFor;
    }

    private boolean getIsCurrentBranch(Repository repository) throws IOException {
        return getFullName().equals(repository.getFullBranch());
    }
    
    private String getShortName(String branchName) {
        if(branchName.startsWith(LOCAL_PREFIX)) {
            return branchName.substring(LOCAL_PREFIX.length());
        }
        
        if(branchName.startsWith(REMOTE_PREFIX)) {
            return branchName.substring(REMOTE_PREFIX.length());
        }
        
        return branchName;
    }
    
    /**
     * Get the latest commit for this branch.
     */
    private void getLatestCommitStatus(Repository repository) throws IOException {
        try(RevWalk revWalk = new RevWalk(repository)) {
            latestCommit = revWalk.parseCommit(ref.getObjectId());
        }
    }
    
    private void getCommitStatus(Repository repository) throws IOException {
        BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, getShortName());
        if(trackingStatus != null) {
            hasUnpushedCommits = trackingStatus.getAheadCount() > 0;
            hasRemoteCommits = trackingStatus.getBehindCount() > 0;
        }
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof BranchInfo) &&
                repoDir.equals(((BranchInfo)obj).repoDir) &&
                getFullName().equals(((BranchInfo)obj).getFullName());
    }
}