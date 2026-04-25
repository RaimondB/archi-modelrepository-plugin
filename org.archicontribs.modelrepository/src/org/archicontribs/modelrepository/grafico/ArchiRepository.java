/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.archicontribs.modelrepository.authentication.CredentialsAuthenticator;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CleanCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.utils.PlatformUtils;
import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Representation of a local repository
 * This is a wrapper class around a local repo folder
 * 
 * @author Phillip Beauvoir
 */
@SuppressWarnings("nls")
public class ArchiRepository implements IArchiRepository {
    
    /**
     * The folder location of the local repository
     */
    private File fLocalRepoFolder;

    public ArchiRepository(File localRepoFolder) {
        fLocalRepoFolder = localRepoFolder;
    }

    @Override
    public File getLocalRepositoryFolder() {
        return fLocalRepoFolder;
    }
    
    @Override
    public File getLocalGitFolder() {
        return new File(getLocalRepositoryFolder(), ".git");
    }

    @Override
    public String getName() {
        // If the model is open, return its name
        IArchimateModel model = locateModel();
        if(model != null) {
            return model.getName();
        }
        
        // If model not open, open the "folder.xml" file and read it from there
        File file = new File(getLocalRepositoryFolder(), IGraficoConstants.MODEL_FOLDER + "/" + IGraficoConstants.FOLDER_XML);
        if(file.exists()) {
            try(Stream<String> stream = Files.lines(file.toPath())
                                             .filter(line -> line.indexOf("name=") != -1)) {
                Optional<String> result = stream.findFirst();
                if(result.isPresent()) {
                    String segments[] = result.get().split("\"");
                    if(segments.length == 2) {
                        return segments[1];
                    }
                }
            }
            catch(Exception ex) { // Catch all exceptions to stop exception dialog
                ex.printStackTrace();
            }
        }
        
        return fLocalRepoFolder.getName();
    }

    @Override
    public File getTempModelFile() {
        return new File(getLocalRepositoryFolder(), "/.git/" + IGraficoConstants.LOCAL_ARCHI_FILENAME);
    }
    
    @Override
    public String getOnlineRepositoryURL() throws IOException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            return git.getRepository().getConfig().getString("remote", IGraficoConstants.ORIGIN, "url");
        }
    }
    
    @Override
    public IArchimateModel locateModel() {
        File tempFile = getTempModelFile();
        
        for(IArchimateModel model : IEditorModelManager.INSTANCE.getModels()) {
            if(tempFile.equals(model.getFile())) {
                return model;
            }
        }
        
        return null;
    }

    @Override
    public boolean hasChangesToCommit() throws IOException, GitAPIException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            Status status = git.status().call();
            return !status.isClean();
        }
    }
    
    /**
     * Get a summary of changes to be committed
     * @param maxItems Maximum number of items to list (use -1 for all)
     * @return ChangeSummary containing formatted text and counts
     * @throws IOException
     * @throws GitAPIException
     */
    public ChangeSummary getChangeSummary(int maxItems) throws IOException, GitAPIException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            Status status = git.status().call();
            return ChangeSummary.from(status, getLocalRepositoryFolder(), maxItems);
        }
    }
    
    @Override
    public RevCommit commitChanges(String commitMessage, boolean amend) throws GitAPIException, IOException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            Status status = git.status().call();
            
            // Check if we're in a merge state (MERGE_HEAD exists)
            boolean isMerging = new File(getLocalRepositoryFolder(), ".git/MERGE_HEAD").exists(); //$NON-NLS-1$
            
            // Nothing changed and not in a merge — no commit needed
            if(status.isClean() && !isMerging) {
                return null;
            }
            
            // Check lock file is deleted
            checkDeleteLockFile();
            
            // Stage changes (skip if clean — only here for merge commit)
            if(!status.isClean()) {
                // Add modified files to index
                // Try native Git first (much faster for large repos), falls back to JGit automatically
                gitAdd();
                
                // Add missing files to index
                for(String s : status.getMissing()) {
                    git.rm().addFilepattern(s).call();
                }
            }
            
            // Commit
            CommitCommand commitCommand = git.commit();
            PersonIdent userDetails = getUserDetails();
            commitCommand.setAuthor(userDetails);
            commitCommand.setMessage(commitMessage);
            commitCommand.setAmend(amend);
            return commitCommand.call();
        }
    }
    
    @Override
    public void cloneModel(String repoURL, UsernamePassword npw, ProgressMonitor monitor) throws GitAPIException, IOException {
        // Try native Git for SSH repositories (faster and credentials handled by SSH agent/config)
        // For HTTPS, we need JGit's credential handling
        boolean useNativeGit = GraficoUtils.isSSH(repoURL);
        
        if(useNativeGit) {
            try {
                // Use native git + JGit state sync
                nativeGitClone(repoURL, getLocalRepositoryFolder(), 
                    repository -> setDefaultConfigSettings(repository));
                return; // Success with native git
            }
            catch(IOException ex) {
                // Native git failed, fall through to JGit
            }
        }
        
        // Fall back to JGit (or HTTPS which requires credential handling)
        CloneCommand cloneCommand = Git.cloneRepository();
        cloneCommand.setDirectory(getLocalRepositoryFolder());
        cloneCommand.setURI(repoURL);
        cloneCommand.setTransportConfigCallback(CredentialsAuthenticator.getTransportConfigCallback(repoURL, npw));
        cloneCommand.setProgressMonitor(monitor);

        try(Git git = cloneCommand.call()) {
            setDefaultConfigSettings(git.getRepository());
        }
    }

    @Override
    public Iterable<PushResult> pushToRemote(UsernamePassword npw, ProgressMonitor monitor) throws IOException, GitAPIException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            PushCommand pushCommand = git.push();
            pushCommand.setTransportConfigCallback(CredentialsAuthenticator.getTransportConfigCallback(getOnlineRepositoryURL(), npw));
            pushCommand.setProgressMonitor(monitor);
            
            Iterable<PushResult> result = pushCommand.call();
            
            // After a successful push, ensure we are tracking the current branch
            setTrackedBranch(git.getRepository(), git.getRepository().getBranch());
            
            return result;
        }
    }
    
    @Override
    public PullResult pullFromRemote(UsernamePassword npw, ProgressMonitor monitor) throws IOException, GitAPIException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            PullCommand pullCommand = git.pull();
            pullCommand.setTransportConfigCallback(CredentialsAuthenticator.getTransportConfigCallback(getOnlineRepositoryURL(), npw));
            pullCommand.setRebase(false); // Merge, not rebase
            pullCommand.setProgressMonitor(monitor);
            return pullCommand.call();
        }
    }
    
    @Override
    public FetchResult fetchFromRemote(UsernamePassword npw, ProgressMonitor monitor, boolean isDryrun) throws IOException, GitAPIException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            // Check and set tracked master branch
            setTrackedBranch(git.getRepository(), IGraficoConstants.MASTER);
            FetchCommand fetchCommand = git.fetch();
            fetchCommand.setTransportConfigCallback(CredentialsAuthenticator.getTransportConfigCallback(getOnlineRepositoryURL(), npw));
            fetchCommand.setProgressMonitor(monitor);
            fetchCommand.setDryRun(isDryrun);
            return fetchCommand.call();
        }
    }

    @Override
    public Git createNewLocalGitRepository(String URL) throws GitAPIException, IOException, URISyntaxException {
        if(getLocalRepositoryFolder().exists() && getLocalRepositoryFolder().list().length > 0) {
            throw new IOException("Directory: " + getLocalRepositoryFolder().getAbsolutePath() + " is not empty.");
        }
        
        InitCommand initCommand = Git.init();
        initCommand.setDirectory(getLocalRepositoryFolder());
        Git git = initCommand.call();
        
        RemoteAddCommand remoteAddCommand = git.remoteAdd();
        remoteAddCommand.setName(IGraficoConstants.ORIGIN);
        remoteAddCommand.setUri(new URIish(URL));
        remoteAddCommand.call();
        
        setDefaultConfigSettings(git.getRepository());
        
        // Set tracked master branch
        setTrackedBranch(git.getRepository(), IGraficoConstants.MASTER);
        
        return git;
    }

    @Override
    public byte[] getFileContents(String path, String ref) throws IOException {
        byte[] bytes = null;
        
        try(Repository repository = Git.open(getLocalRepositoryFolder()).getRepository()) {
            ObjectId lastCommitId = repository.resolve(ref);

            try(RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(lastCommitId);
                RevTree tree = commit.getTree();

                // now try to find a specific file
                try(TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(tree);
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilter.create(path));

                    // Not found, return null
                    if(!treeWalk.next()) {
                        return null;
                    }

                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);

                    bytes = loader.getBytes();
                }

                revWalk.dispose();
            }
        }
        
        return bytes;
    }

    @Override
    public String getWorkingTreeFileContents(String path) throws IOException {
        String str = "";
        
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            try(BufferedReader in = new BufferedReader(new FileReader(new File(getLocalRepositoryFolder(), path)))) {
                String line;
                while((line = in.readLine()) != null) {
                    str += line + "\n";
                }
            }
        }
        
        return str;
    }

    @Override
    public void resetToRef(String ref, ResetType resetType) throws IOException, GitAPIException {
        // Check lock file is deleted
        checkDeleteLockFile();
        
        // Reset using local method (tries native git, falls back to JGit automatically)
        gitReset(ref, resetType);
        
        // Clean extra files only for HARD resets
        if(resetType == ResetType.HARD) {
            try(Git git = Git.open(getLocalRepositoryFolder())) {
                CleanCommand cleanCommand = git.clean();
                cleanCommand.setCleanDirectories(true);
                cleanCommand.call();
            }
        }
    }
    
    @Override
    public boolean isHeadAndRemoteSame() throws IOException, GitAPIException {
        BranchStatus status = getBranchStatus();
        BranchInfo currentLocal = status.getCurrentLocalBranch();
        BranchInfo currentRemote = status.getCurrentRemoteBranch();
        
        if(currentLocal == null || currentRemote == null) {
            return false;
        }
        
        Ref localRef = currentLocal.getRef();
        Ref remoteRef = currentRemote.getRef();
        
        if(localRef == null || remoteRef == null) {
            return false;
        }
        
        return localRef.getObjectId().equals(remoteRef.getObjectId());
    }
    
    @Override
    public void exportModelToGraficoFiles() throws IOException, GitAPIException {
        // Open the model before showing the progress monitor
        IArchimateModel model = IEditorModelManager.INSTANCE.openModel(getTempModelFile());
        
        if(model == null) {
            throw new IOException(Messages.ArchiRepository_0);
        }
        
        final Exception[] exception = new Exception[1];

        try {
            // When using this be careful that no UI operations are called as this could lead to an SWT Invalid thread access exception
            // This will show a Cancel button which will not cancel, but this progress monitor is the only one which does not freeze the UI
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor pm) {
                    // Use SubMonitor for proper progress tracking across phases
                    // Export gets 80%, git staging gets 20% (can be slow for large repos)
                    SubMonitor progress = SubMonitor.convert(pm, Messages.ArchiRepository_1, 100);

                    try {
                        // Export model to GRAFICO format (80% of progress)
                        GraficoModelExporter exporter = new GraficoModelExporter(model, getLocalRepositoryFolder());
                        exporter.exportModel(progress.split(80));
                        
                        // Check lock file is deleted
                        checkDeleteLockFile();
                        
                        // Stage modified files to index (20% of progress)
                        // This can take a long time for large repos!
                        progress.subTask(Messages.ArchiRepository_2);
                        
                        // Try native Git first (much faster for large repos), falls back to JGit automatically
                        gitAdd();
                        
                        progress.worked(20);
                    }
                    catch(IOException | GitAPIException ex) {
                        exception[0] = ex;
                    }
                }
            });
        }
        catch(InvocationTargetException | InterruptedException ex) {
            throw new IOException(ex);
        }
        
        if(exception[0] instanceof IOException) {
            throw (IOException)exception[0];
        }
        if(exception[0] instanceof GitAPIException) {
            throw (GitAPIException)exception[0];
        }
    }
    
    @Override
    public boolean exportModelToGraficoFiles(IProgressMonitor monitor) throws IOException, GitAPIException {
        // Open the model
        IArchimateModel model = IEditorModelManager.INSTANCE.openModel(getTempModelFile());
        
        if(model == null) {
            throw new IOException(Messages.ArchiRepository_0);
        }
        
        // Use SubMonitor for proper progress tracking
        // Export gets 80%, git staging gets 20%
        SubMonitor progress = SubMonitor.convert(monitor, 100);
        
        // Export model to GRAFICO format (80% of progress)
        // IMPORTANT: exportModel() returns true if any files were written or deleted.
        // We use this return value to determine if git add is needed.
        // See REFACTORING_NOTES.md for details on this design decision.
        GraficoModelExporter exporter = new GraficoModelExporter(model, getLocalRepositoryFolder());
        boolean hasChanges = exporter.exportModel(progress.split(80));
        
        // Check lock file is deleted
        checkDeleteLockFile();
        
        // Stage modified files to index if there are changes
        if(hasChanges) {
            progress.subTask(Messages.ArchiRepository_2);
            // Use native git add for performance (much faster than JGit on Windows)
            gitAdd();
        }
        progress.worked(20);
        
        return hasChanges;
    }
    
    @Override
    public PersonIdent getUserDetails() throws IOException {
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            StoredConfig config = git.getRepository().getConfig();
            String name = StringUtils.safeString(config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME));
            String email = StringUtils.safeString(config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL));
            return new PersonIdent(name, email);
        }
    }
    
    @Override
    public void saveUserDetails(String name, String email) throws IOException {
        // Get global user details from .gitconfig for comparison
        PersonIdent global = new PersonIdent("", "");
        
        try {
            global = GraficoUtils.getGitConfigUserDetails();
        }
        catch(ConfigInvalidException ex) {
            ex.printStackTrace();
        }
        
        // Save to local config
        try(Git git = Git.open(getLocalRepositoryFolder())) {
            StoredConfig config = git.getRepository().getConfig();
            
            // If global name == local name or blank then unset
            if(!StringUtils.isSet(name) || global.getName().equals(name)) {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME);
            }
            // Set
            else {
                config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, name);
            }
            
            // If global email == local email or blank then unset
            if(!StringUtils.isSet(email) || global.getEmailAddress().equals(email)) {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL);
            }
            else {
                config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, email);
            }

            config.save();
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if((obj != null) && (obj instanceof ArchiRepository)) {
            return fLocalRepoFolder != null && fLocalRepoFolder.equals(((IArchiRepository)obj).getLocalRepositoryFolder());
        }
        return false;
    }
    
    /**
     * Set default settings in the config file 
     * @param repository
     * @throws IOException
     */
    private void setDefaultConfigSettings(Repository repository) throws IOException {
        StoredConfig config = repository.getConfig();
        
        /*
         * Set Line endings in the config file to autocrlf=input
         * This ensures that files are not seen as different
         */
        config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOCRLF, "input");
        
        /*
         * Set longpaths=true because garbage collection is not possible otherwise
         * See https://stackoverflow.com/questions/22575662/filename-too-long-in-git-for-windows
         */
        config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, "longpaths", "true");
        
        // Set ignore case on Mac/Windows
        if(!PlatformUtils.isLinux()) {
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, "ignorecase", "true");
        }
        
        // Set GPG signing false
        config.setString(ConfigConstants.CONFIG_COMMIT_SECTION, null, ConfigConstants.CONFIG_KEY_GPGSIGN, "false");
        
        // Set hooksPath to null in case the user has set a global hooksPath and cause problems
        // such as git-lfs not being found on the system path.
        // JGit will resolve hooksPath to localrepo/.git/hooks
        config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH, null);

        config.save();
    }
    
    /**
     * Set the given branchName to track "origin"
     */
    private void setTrackedBranch(Repository repository, String branchName) throws IOException {
        if(branchName == null) {
            return;
        }
        
        StoredConfig config = repository.getConfig();
        
        if(!IGraficoConstants.ORIGIN.equals(config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branchName, ConfigConstants.CONFIG_KEY_REMOTE))) {
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branchName,  ConfigConstants.CONFIG_KEY_REMOTE, IGraficoConstants.ORIGIN);
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branchName, ConfigConstants.CONFIG_KEY_MERGE, Constants.R_HEADS + branchName);
            config.save();
        }
    }
    
    
    @Override
    public boolean hasLocalChanges() throws IOException {
        String latestChecksum = getLatestChecksum();
        if(latestChecksum == null) {
            return false;
        }

        String currentChecksum = createChecksum();
        return !latestChecksum.equals(currentChecksum);
    }

    @Override
    public boolean saveChecksum() throws IOException {
        // Get the file's checksum as string
        String checksum = createChecksum();
        if(checksum == null) {
            return false;
        }

        File checksumFile = new File(getLocalGitFolder(), "checksum");
        Files.write(Paths.get(checksumFile.getAbsolutePath()), checksum.getBytes(), StandardOpenOption.CREATE);
        
        return true;
    }
    
    /** Cached BranchStatus with a TTL to avoid redundant O(N) computations */
    private volatile BranchStatus fCachedBranchStatus;
    private volatile long fBranchStatusTimestamp;
    private static final long BRANCH_STATUS_TTL_NANOS = 2_000_000_000L; // 2 seconds
    
    @Override
    public BranchStatus getBranchStatus() throws IOException, GitAPIException {
        long now = System.nanoTime();
        BranchStatus cached = fCachedBranchStatus;
        if(cached != null && (now - fBranchStatusTimestamp) < BRANCH_STATUS_TTL_NANOS) {
            return cached;
        }
        BranchStatus fresh = new BranchStatus(this);
        fCachedBranchStatus = fresh;
        fBranchStatusTimestamp = System.nanoTime();
        return fresh;
    }
    
    private String getLatestChecksum() throws IOException {
        File checksumFile = new File(getLocalGitFolder(), "checksum");
        if(!checksumFile.exists()) {
            return null;
        }
        
        byte[] bytes = Files.readAllBytes(Paths.get(checksumFile.getAbsolutePath()));
        return new String(bytes);
    }
    
    private String createChecksum() throws IOException {
        File tempFile = getTempModelFile();
        
        if(tempFile == null) {
            return null;
        }
        
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("MD5");
        }
        catch(NoSuchAlgorithmException ex) {
            throw new IOException("NoSuchAlgorithm Exception", ex);
        } 

        // Get file input stream for reading the file content
        FileInputStream fis = new FileInputStream(tempFile);

        // Create byte array to read data in chunks
        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        // Read file data and update in message digest
        while((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        }

        fis.close();

        // Get the hash's bytes
        byte[] bytes = digest.digest();
        
        // This bytes[] has bytes in decimal format;
        // Convert it to hexadecimal format
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < bytes.length; i++) {
            sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
        }
        
        return sb.toString();
    }
    
    /**
     * In some cases the lock file exists and leads to an error, so we delete it
     */
    private void checkDeleteLockFile() {
        File lockFile = new File(getLocalGitFolder(), "index.lock");
        if(lockFile.exists() && lockFile.canWrite()) {
            lockFile.delete();
        }
    }
    
    // ====================================================================================
    // Native Git Utility Methods
    // ====================================================================================
    
    /**
     * Checkout a branch using native Git (faster) or JGit fallback.
     * After successful native operation, opens the repository to sync JGit state.
     * 
     * @param branchName the branch name to checkout (can be full ref like "refs/heads/main" or short name like "main")
     * @throws IOException if the checkout command failed
     * @throws GitAPIException if JGit fallback fails
     */
    public void checkoutBranch(String branchName) throws IOException, GitAPIException {
        // Native git needs short name (e.g., "main"), JGit can use full ref (e.g., "refs/heads/main")
        String shortName = extractShortBranchName(branchName);
        
        boolean nativeSuccess = tryNativeGitCheckout(shortName);
        
        if(!nativeSuccess) {
            // Fall back to JGit - can use either full or short name
            try(Git git = Git.open(getLocalRepositoryFolder())) {
                git.checkout().setName(branchName).call();
            }
        }
        else {
            // After native Git operation, open repository to sync JGit state
            // This is critical for triggering UI updates and ensuring JGit sees the changes
            Git.open(getLocalRepositoryFolder()).close();
        }
    }
    
    /**
     * Extract short branch name from full ref or return as-is if already short.
     * Examples:
     *   "refs/heads/main" -> "main"
     *   "refs/remotes/origin/main" -> "main"
     *   "main" -> "main"
     * 
     * @param branchName full ref or short name
     * @return short branch name
     */
    private String extractShortBranchName(String branchName) {
        if(branchName == null) {
            return null;
        }
        
        // Strip "refs/heads/" prefix
        if(branchName.startsWith("refs/heads/")) { //$NON-NLS-1$
            return branchName.substring("refs/heads/".length()); //$NON-NLS-1$
        }
        
        // Strip "refs/remotes/origin/" prefix
        if(branchName.startsWith("refs/remotes/origin/")) { //$NON-NLS-1$
            return branchName.substring("refs/remotes/origin/".length()); //$NON-NLS-1$
        }
        
        // Strip any other "refs/remotes/<remote>/" prefix
        if(branchName.startsWith("refs/remotes/")) { //$NON-NLS-1$
            int slashAfterRemote = branchName.indexOf('/', "refs/remotes/".length()); //$NON-NLS-1$
            if(slashAfterRemote > 0 && slashAfterRemote < branchName.length() - 1) {
                return branchName.substring(slashAfterRemote + 1);
            }
        }
        
        // Already a short name
        return branchName;
    }
    
    /**
     * Low-level method: Try to use native Git for checkout (much faster than JGit for many files).
     * 
     * @param branchName the branch name to checkout
     * @return true if native Git checkout succeeded, false if native Git is not available
     * @throws IOException if the checkout command failed
     */
    private boolean tryNativeGitCheckout(String branchName) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", branchName); //$NON-NLS-1$ //$NON-NLS-2$
            pb.directory(getLocalRepositoryFolder());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output to prevent blocking
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git checkout failed: " + output.toString()); //$NON-NLS-1$
            }
            
            return true;
        }
        catch(IOException ex) {
            // Check if this is because git is not found
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                // Native Git not available, fall back to JGit
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git checkout interrupted", ex); //$NON-NLS-1$
        }
    }
    
    /**
     * Add all files to the index using native Git (faster) or JGit fallback.
     * After successful native operation, opens the repository to sync JGit state.
     * 
     * @throws IOException if the add command failed
     * @throws GitAPIException if JGit fallback fails
     */
    private void gitAdd() throws IOException, GitAPIException {
        boolean nativeSuccess = tryNativeGitAdd();
        
        if(!nativeSuccess) {
            // Fall back to JGit - need to call add twice to stage new, modified, AND deleted files
            try(Git git = Git.open(getLocalRepositoryFolder())) {
                // Stage new and modified files
                git.add()
                    .addFilepattern(".") //$NON-NLS-1$
                    .setUpdate(false)  // false = include new files
                    .call();
                
                // Stage modified and deleted files
                git.add()
                    .addFilepattern(".") //$NON-NLS-1$
                    .setUpdate(true)   // true = include deleted files
                    .call();
            }
        }
        else {
            // After native Git operation, open repository to sync JGit state
            // This is critical for triggering UI updates and ensuring JGit sees the changes
            Git.open(getLocalRepositoryFolder()).close();
        }
    }
    
    /**
     * Reset the repository to a specific ref using native Git (faster) or JGit fallback.
     * After successful native operation, opens the repository to sync JGit state.
     * 
     * @param ref the ref to reset to (e.g., "HEAD", "HEAD~1", commit SHA)
     * @param resetType the type of reset (HARD, MIXED, SOFT)
     * @throws IOException if the reset command failed
     * @throws GitAPIException if JGit fallback fails
     */
    private void gitReset(String ref, ResetType resetType) throws IOException, GitAPIException {
        // Map JGit ResetType to native git --hard/--mixed/--soft
        String resetMode = null;
        switch(resetType) {
            case HARD:
                resetMode = "--hard"; //$NON-NLS-1$
                break;
            case MIXED:
                resetMode = "--mixed"; //$NON-NLS-1$
                break;
            case SOFT:
                resetMode = "--soft"; //$NON-NLS-1$
                break;
            default:
                resetMode = "--mixed"; //$NON-NLS-1$
        }
        
        boolean nativeSuccess = tryNativeGitReset(ref, resetMode);
        
        if(!nativeSuccess) {
            // Fall back to JGit
            try(Git git = Git.open(getLocalRepositoryFolder())) {
                git.reset()
                    .setRef(ref)
                    .setMode(resetType)
                    .call();
            }
        }
        else {
            // After native Git operation, open repository to sync JGit state
            // This is critical for triggering UI updates and ensuring JGit sees the changes
            Git.open(getLocalRepositoryFolder()).close();
        }
    }
    
    /**
     * Low-level method: Try to use native Git for reset.
     * 
     * @param ref the ref to reset to
     * @param resetMode the reset mode flag (--hard, --mixed, --soft)
     * @return true if native Git reset succeeded, false if native Git is not available
     * @throws IOException if the reset command failed
     */
    private boolean tryNativeGitReset(String ref, String resetMode) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "reset", resetMode, ref); //$NON-NLS-1$ //$NON-NLS-2$
            pb.directory(getLocalRepositoryFolder());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output to prevent blocking
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git reset failed: " + output.toString()); //$NON-NLS-1$
            }
            
            return true;
        }
        catch(IOException ex) {
            // Check if this is because git is not found
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                // Native Git not available, fall back to JGit
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git reset interrupted", ex); //$NON-NLS-1$
        }
    }
    
    /**
     * Low-level method: Try to use native Git for adding files (much faster than JGit for many files).
     * 
     * @return true if native Git add succeeded, false if native Git is not available
     * @throws IOException if the add command failed
     */
    private boolean tryNativeGitAdd() throws IOException {
        try {
            // Use -A (--all) to stage new, modified, AND deleted files
            ProcessBuilder pb = new ProcessBuilder("git", "add", "-A"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            pb.directory(getLocalRepositoryFolder());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output to prevent blocking
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git add failed: " + output.toString()); //$NON-NLS-1$
            }
            
            return true;
        }
        catch(IOException ex) {
            // Check if this is because git is not found
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                // Native Git not available, fall back to JGit
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git add interrupted", ex); //$NON-NLS-1$
        }
    }
    
    /**
     * Clone a repository using native Git for SSH URLs (faster) or JGit for HTTPS (credential handling).
     * Falls back to JGit if native Git is not available.
     * After successful clone, opens the repository to sync JGit state and applies default config settings.
     * 
     * @param repoURL the repository URL to clone from
     * @param targetFolder the target folder where to clone
     * @param configCallback callback to set default config after clone (can be null)
     * @throws IOException if the clone command failed
     */
    private void nativeGitClone(String repoURL, File targetFolder, ConfigCallback configCallback) 
            throws IOException {
        boolean nativeSuccess = tryNativeGitClone(repoURL, targetFolder);
        
        if(!nativeSuccess) {
            // Native Git not available - caller must use JGit fallback
            throw new IOException("Native Git not available"); //$NON-NLS-1$
        }
        
        // After native git clone, open repository to sync JGit state and apply config
        try(Git git = Git.open(targetFolder)) {
            if(configCallback != null) {
                configCallback.setConfig(git.getRepository());
            }
        }
    }
    
    /**
     * Callback interface for setting repository configuration after clone.
     */
    private interface ConfigCallback {
        void setConfig(Repository repository) throws IOException;
    }
    
    /**
     * Low-level method: Try to use native Git for cloning (much faster than JGit for large repositories).
     * 
     * @param repoURL the repository URL to clone from
     * @param targetFolder the target folder where to clone
     * @return true if native Git clone succeeded, false if native Git is not available
     * @throws IOException if the clone command failed
     */
    private boolean tryNativeGitClone(String repoURL, File targetFolder) throws IOException {
        try {
            // Build command: git clone <url> <target>
            List<String> command = new ArrayList<>();
            command.add("git"); //$NON-NLS-1$
            command.add("clone"); //$NON-NLS-1$
            command.add(repoURL);
            command.add(targetFolder.getAbsolutePath());
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(targetFolder.getParentFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Read output to prevent blocking
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git clone failed: " + output.toString()); //$NON-NLS-1$
            }
            
            return true;
        }
        catch(IOException ex) {
            // Check if this is because git is not found
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                // Native Git not available, fall back to JGit
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git clone interrupted", ex); //$NON-NLS-1$
        }
    }
}
