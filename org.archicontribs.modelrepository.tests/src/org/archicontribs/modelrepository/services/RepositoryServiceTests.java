/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.archicontribs.modelrepository.GitHelper;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.archicontribs.modelrepository.services.RepositoryService.CommitResult;
import org.archicontribs.modelrepository.services.RepositoryService.MergeBranchResult;
import org.archicontribs.modelrepository.services.RepositoryService.RefreshResult;
import org.archicontribs.modelrepository.services.RepositoryService.PublishResult;
import org.archicontribs.modelrepository.services.RepositoryService.SwitchResult;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;

/**
 * Tests for {@link RepositoryService}.
 * <p>
 * These tests exercise the service layer with real temporary git repositories.
 * They do not require SWT or Eclipse UI — all merge interactions use
 * {@link HeadlessMergeHandler}.
 */
@SuppressWarnings("nls")
public class RepositoryServiceTests {
    
    private RepositoryService service;
    
    @BeforeEach
    public void setUp() {
        service = new RepositoryService();
    }
    
    @AfterEach
    public void tearDown() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }
    
    // ---- commitChanges tests ----
    
    @Test
    public void commitChanges_WithStagedChanges_ReturnsCommitted() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "testCommit");
        IArchiRepository repo = new ArchiRepository(localRepoFolder);
        
        try(Repository gitRepo = GitHelper.createNewRepository(localRepoFolder)) {
            // Create the temp model file that saveChecksum() needs
            createTempModelFile(localRepoFolder);
            
            // Create and stage a file
            createAndStageFile(localRepoFolder, gitRepo, "test.txt", "Hello");
            
            // Make initial commit so HEAD exists
            Git.wrap(gitRepo).commit()
                    .setAuthor("Test", "test@test.com")
                    .setMessage("Initial")
                    .call();
            
            // Create another file for the actual test
            createAndStageFile(localRepoFolder, gitRepo, "test2.txt", "World");
            
            CommitResult result = service.commitChanges(repo, "Test commit", false);
            
            assertEquals(CommitResult.Status.COMMITTED, result.status());
            assertNotNull(result.commitId());
            assertEquals(40, result.commitId().length());
        }
    }
    
    @Test
    public void commitChanges_WithNoChanges_ReturnsNothingToCommit() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "testNoChanges");
        IArchiRepository repo = new ArchiRepository(localRepoFolder);
        
        try(Repository gitRepo = GitHelper.createNewRepository(localRepoFolder)) {
            // Create initial commit
            createAndStageFile(localRepoFolder, gitRepo, "test.txt", "Hello");
            Git.wrap(gitRepo).commit()
                    .setAuthor("Test", "test@test.com")
                    .setMessage("Initial")
                    .call();
            
            // No new changes — commitChanges should find nothing
            CommitResult result = service.commitChanges(repo, "Empty", false);
            
            assertEquals(CommitResult.Status.NOTHING_TO_COMMIT, result.status());
            assertNull(result.commitId());
        }
    }
    
    // ---- refresh tests ----
    
    @Test
    public void refresh_WithUpToDateRepo_ReturnsUpToDate() throws Exception {
        // Create a bare "remote" and a local clone
        File bareRepoFolder = new File(GitHelper.getTempTestsFolder(), "bare.git");
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "local");
        
        // Create bare repo with one commit
        try(Git bareGit = Git.init().setDirectory(bareRepoFolder).setBare(true).call()) {
            // Clone it
            try(Git localGit = Git.cloneRepository()
                    .setURI(bareRepoFolder.toURI().toString())
                    .setDirectory(localRepoFolder)
                    .call()) {
                
                // Create initial commit in local and push
                createAndStageFile(localRepoFolder, localGit.getRepository(), "test.txt", "Hello");
                localGit.commit()
                        .setAuthor("Test", "test@test.com")
                        .setMessage("Initial")
                        .call();
                localGit.push().call();
                
                IArchiRepository repo = new ArchiRepository(localRepoFolder);
                
                // Refresh — should be up to date since we just pushed
                RefreshResult result = service.refresh(repo, null,
                        new HeadlessMergeHandler(), new NullProgressMonitor());
                
                assertEquals(RefreshResult.Status.UP_TO_DATE, result.status());
            }
        }
    }
    
    @Test
    public void refresh_WithRemoteChanges_ReturnsOK() throws Exception {
        File bareRepoFolder = new File(GitHelper.getTempTestsFolder(), "bare.git");
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "local");
        File otherCloneFolder = new File(GitHelper.getTempTestsFolder(), "other");
        
        // Create bare repo
        try(Git bareGit = Git.init().setDirectory(bareRepoFolder).setBare(true).call()) {
            // Clone to local
            try(Git localGit = Git.cloneRepository()
                    .setURI(bareRepoFolder.toURI().toString())
                    .setDirectory(localRepoFolder)
                    .call()) {
                
                // Initial commit + push
                createAndStageFile(localRepoFolder, localGit.getRepository(), "test.txt", "Hello");
                localGit.commit()
                        .setAuthor("Test", "test@test.com")
                        .setMessage("Initial")
                        .call();
                localGit.push().call();
                
                // Clone to "other" and make a change there
                try(Git otherGit = Git.cloneRepository()
                        .setURI(bareRepoFolder.toURI().toString())
                        .setDirectory(otherCloneFolder)
                        .call()) {
                    
                    createAndStageFile(otherCloneFolder, otherGit.getRepository(), "new-file.txt", "New content");
                    otherGit.commit()
                            .setAuthor("Other", "other@test.com")
                            .setMessage("Remote change")
                            .call();
                    otherGit.push().call();
                }
                
                // Now refresh local — should detect remote changes
                IArchiRepository repo = new ArchiRepository(localRepoFolder);
                
                // Use a custom merge handler that allows model reload without SWT
                MergeHandler handler = new TestMergeHandler();
                
                RefreshResult result = service.refresh(repo, null,
                        handler, new NullProgressMonitor());
                
                assertEquals(RefreshResult.Status.OK, result.status());
                
                // Verify the remote file was pulled
                File newFile = new File(localRepoFolder, "new-file.txt");
                assertEquals(true, newFile.exists());
            }
        }
    }
    
    // ---- publish tests ----
    
    @Test
    public void publish_WithUpToDateRepo_ReturnsOK() throws Exception {
        File bareRepoFolder = new File(GitHelper.getTempTestsFolder(), "bare.git");
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "local");
        
        // Create bare repo
        try(Git bareGit = Git.init().setDirectory(bareRepoFolder).setBare(true).call()) {
            // Clone to local
            try(Git localGit = Git.cloneRepository()
                    .setURI(bareRepoFolder.toURI().toString())
                    .setDirectory(localRepoFolder)
                    .call()) {
                
                // Initial commit + push
                createAndStageFile(localRepoFolder, localGit.getRepository(), "test.txt", "Hello");
                localGit.commit()
                        .setAuthor("Test", "test@test.com")
                        .setMessage("Initial")
                        .call();
                localGit.push().call();
                
                // Make a local change and commit
                createAndStageFile(localRepoFolder, localGit.getRepository(), "local-file.txt", "Local content");
                localGit.commit()
                        .setAuthor("Test", "test@test.com")
                        .setMessage("Local change")
                        .call();
                
                IArchiRepository repo = new ArchiRepository(localRepoFolder);
                
                MergeHandler handler = new TestMergeHandler();
                
                // Publish — should pull (up to date) + push local change
                PublishResult result = service.publish(repo, null,
                        handler, new NullProgressMonitor());
                
                assertEquals(PublishResult.Status.OK, result.status());
                assertNull(result.pushErrors());
            }
        }
    }
    
    // ---- HeadlessMergeHandler tests ----
    
    @Test
    public void headlessMergeHandler_ResolveConflicts_ReturnsFalse() {
        HeadlessMergeHandler handler = new HeadlessMergeHandler();
        // Should always return false (abort) in headless mode
        assertEquals(false, handler.resolveConflicts(null, "test message"));
    }
    
    @Test
    public void headlessMergeHandler_ResolveFolderMoves_UsesDefaults() {
        HeadlessMergeHandler handler = new HeadlessMergeHandler();
        // Should not throw — uses default choices
        handler.resolveFolderMoves(List.of());
    }
    
    // ---- switchBranch tests ----
    
    @Test
    public void switchBranch_ToExistingLocalBranch_ReturnsOK() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "switchLocal");
        
        try(Git git = Git.init().setDirectory(localRepoFolder).call()) {
            // Initial commit on main
            createAndStageFile(localRepoFolder, git.getRepository(), "test.txt", "Hello");
            git.commit().setAuthor("Test", "test@test.com").setMessage("Initial").call();
            
            // Create a feature branch
            git.branchCreate().setName("feature-x").call();
            
            // Create temp model file for saveChecksum()
            createTempModelFile(localRepoFolder);
            
            IArchiRepository repo = new ArchiRepository(localRepoFolder);
            BranchStatus branchStatus = repo.getBranchStatus();
            
            BranchInfo featureBranch = null;
            for(BranchInfo bi : branchStatus.getLocalBranches()) {
                if("feature-x".equals(bi.getShortName())) {
                    featureBranch = bi;
                    break;
                }
            }
            assertNotNull(featureBranch, "feature-x branch should exist");
            
            SwitchResult result = service.switchBranch(repo, featureBranch, false,
                    new TestMergeHandler(), new NullProgressMonitor());
            
            assertEquals(SwitchResult.Status.OK, result.status());
            
            // Verify we're on the feature branch
            assertEquals("refs/heads/feature-x", git.getRepository().getFullBranch());
        }
    }
    
    @Test
    public void switchBranch_ToRemoteBranch_CreatesLocalTrackingBranch() throws Exception {
        File bareRepoFolder = new File(GitHelper.getTempTestsFolder(), "bare.git");
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "switchRemote");
        File otherCloneFolder = new File(GitHelper.getTempTestsFolder(), "other");
        
        try(Git bareGit = Git.init().setDirectory(bareRepoFolder).setBare(true).call()) {
            // Clone to "other" and create a branch + push it
            try(Git otherGit = Git.cloneRepository()
                    .setURI(bareRepoFolder.toURI().toString())
                    .setDirectory(otherCloneFolder)
                    .call()) {
                
                createAndStageFile(otherCloneFolder, otherGit.getRepository(), "test.txt", "Hello");
                otherGit.commit().setAuthor("Test", "test@test.com").setMessage("Initial").call();
                otherGit.push().call();
                
                // Create feature branch and push
                otherGit.branchCreate().setName("remote-feature").call();
                otherGit.checkout().setName("remote-feature").call();
                createAndStageFile(otherCloneFolder, otherGit.getRepository(), "feature.txt", "Feature");
                otherGit.commit().setAuthor("Test", "test@test.com").setMessage("Feature commit").call();
                otherGit.push().setRemote("origin").add("remote-feature").call();
            }
            
            // Clone to local (will have remote-feature as remote-only branch)
            try(Git localGit = Git.cloneRepository()
                    .setURI(bareRepoFolder.toURI().toString())
                    .setDirectory(localRepoFolder)
                    .call()) {
                
                // Create temp model file for saveChecksum()
                createTempModelFile(localRepoFolder);
                
                IArchiRepository repo = new ArchiRepository(localRepoFolder);
                BranchStatus branchStatus = repo.getBranchStatus();
                
                // Find the remote-only branch
                BranchInfo remoteBranch = null;
                for(BranchInfo bi : branchStatus.getLocalAndUntrackedRemoteBranches()) {
                    if("remote-feature".equals(bi.getShortName())) {
                        remoteBranch = bi;
                        break;
                    }
                }
                assertNotNull(remoteBranch, "remote-feature branch should exist");
                
                SwitchResult result = service.switchBranch(repo, remoteBranch, false,
                        new TestMergeHandler(), new NullProgressMonitor());
                
                assertEquals(SwitchResult.Status.OK, result.status());
                
                // Verify we're on the local tracking branch
                assertEquals("refs/heads/remote-feature", localGit.getRepository().getFullBranch());
                
                // Verify the feature file exists
                assertEquals(true, new File(localRepoFolder, "feature.txt").exists());
            }
        }
    }
    
    // ---- mergeBranch tests ----
    
    @Test
    public void mergeBranch_FastForward_ReturnsOK() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "mergeFF");
        
        try(Git git = Git.init().setDirectory(localRepoFolder).call()) {
            // Initial commit on main
            createAndStageFile(localRepoFolder, git.getRepository(), "test.txt", "Hello");
            git.commit().setAuthor("Test", "test@test.com").setMessage("Initial").call();
            
            // Create feature branch and add a commit
            git.branchCreate().setName("feature-merge").call();
            git.checkout().setName("feature-merge").call();
            createAndStageFile(localRepoFolder, git.getRepository(), "feature.txt", "Feature content");
            git.commit().setAuthor("Test", "test@test.com").setMessage("Feature work").call();
            
            // Switch back to main
            git.checkout().setName("master").call();
            
            // Create temp model file for saveChecksum()
            createTempModelFile(localRepoFolder);
            
            IArchiRepository repo = new ArchiRepository(localRepoFolder);
            BranchStatus branchStatus = repo.getBranchStatus();
            
            BranchInfo currentBranch = branchStatus.getCurrentLocalBranch();
            BranchInfo featureBranch = null;
            for(BranchInfo bi : branchStatus.getLocalBranches()) {
                if("feature-merge".equals(bi.getShortName())) {
                    featureBranch = bi;
                    break;
                }
            }
            assertNotNull(featureBranch, "feature-merge branch should exist");
            
            MergeBranchResult result = service.mergeBranch(repo, currentBranch,
                    featureBranch, new TestMergeHandler(), new NullProgressMonitor());
            
            assertEquals(MergeBranchResult.Status.OK, result.status());
            assertEquals(0, result.conflictCount());
            
            // Verify the feature file exists on main
            assertEquals(true, new File(localRepoFolder, "feature.txt").exists());
        }
    }
    
    @Test
    public void mergeBranch_AlreadyUpToDate_ReturnsUpToDate() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "mergeUpToDate");
        
        try(Git git = Git.init().setDirectory(localRepoFolder).call()) {
            // Initial commit on main
            createAndStageFile(localRepoFolder, git.getRepository(), "test.txt", "Hello");
            git.commit().setAuthor("Test", "test@test.com").setMessage("Initial").call();
            
            // Create feature branch at same commit (no divergence)
            git.branchCreate().setName("feature-noop").call();
            
            IArchiRepository repo = new ArchiRepository(localRepoFolder);
            BranchStatus branchStatus = repo.getBranchStatus();
            
            BranchInfo currentBranch = branchStatus.getCurrentLocalBranch();
            BranchInfo featureBranch = null;
            for(BranchInfo bi : branchStatus.getLocalBranches()) {
                if("feature-noop".equals(bi.getShortName())) {
                    featureBranch = bi;
                    break;
                }
            }
            assertNotNull(featureBranch, "feature-noop branch should exist");
            
            MergeBranchResult result = service.mergeBranch(repo, currentBranch,
                    featureBranch, new TestMergeHandler(), new NullProgressMonitor());
            
            // Native git returns OK (can't distinguish), JGit returns UP_TO_DATE
            assertTrue(result.status() == MergeBranchResult.Status.UP_TO_DATE
                    || result.status() == MergeBranchResult.Status.OK,
                    "Expected UP_TO_DATE or OK but got " + result.status());
            assertEquals(0, result.conflictCount());
        }
    }

    @Test
    public void shouldRunCrossPathDeletion_ConflictingMerge_ReturnsFalse() {
        assertEquals(false, RepositoryService.shouldRunCrossPathDeletion(MergeStatus.CONFLICTING));

        // Sanity checks for non-conflicting states.
        assertEquals(true, RepositoryService.shouldRunCrossPathDeletion(MergeStatus.MERGED));
        assertEquals(true, RepositoryService.shouldRunCrossPathDeletion(MergeStatus.FAST_FORWARD));
        assertEquals(true, RepositoryService.shouldRunCrossPathDeletion(null));
    }

    /**
     * REGRESSION TEST: Non-conflicting additions from "theirs" must survive a conflicting merge.
     * 
     * Scenario: "theirs" branch adds 50 new files AND modifies a shared file.
     * "ours" branch also modifies the shared file (conflict).
     * User resolves all conflicts to THEIRS.
     * 
     * Expected: The merge result should contain ALL of theirs' additions plus the
     * resolved conflict content. No files from theirs should be deleted.
     * 
     * This reproduces a real-world bug where merging master (9000+ additions) into
     * a branch with 234 conflicts resulted in ~9000 deletions vs master.
     * 
     * The test exercises the same code path as RepositoryService.mergeBranch():
    * 1. ArchiRepository.mergeBranch() → native git merge with conflicting paths retained
     * 2. git checkout --stage=THEIRS for conflicting paths (handler.merge() equivalent)
     * 3. git add -A + commit (commitChanges() equivalent)
     */
    @Test
    public void mergeBranch_ConflictResolvedToTheirs_PreservesNonConflictingAdditions() throws Exception {
        File localRepoFolder = new File(GitHelper.getTempTestsFolder(), "mergePreserveAdditions");

        try(Git git = Git.init().setDirectory(localRepoFolder).call()) {
            Repository gitRepo = git.getRepository();

            // Initial commit: a shared file that will be modified by both branches
            createAndStageFile(localRepoFolder, gitRepo, "model/shared.xml", "<element name=\"original\"/>");
            git.commit().setAuthor("Test", "test@test.com").setMessage("Initial").call();

            // === Create "theirs-branch": adds many new files + modifies shared file ===
            git.branchCreate().setName("theirs-branch").call();
            git.checkout().setName("theirs-branch").call();

            // Add 50 new files (simulating master adding thousands of files)
            int additionCount = 50;
            for(int i = 0; i < additionCount; i++) {
                String path = "model/additions/folder" + (i / 10) + "/Element_id-new" + i + ".xml";
                createAndStageFile(localRepoFolder, gitRepo, path,
                        "<element name=\"new" + i + "\" id=\"id-new" + i + "\"/>");
            }

            // Modify the shared file (this will conflict with ours)
            createAndStageFile(localRepoFolder, gitRepo, "model/shared.xml",
                    "<element name=\"theirs-modification\"/>");
            git.commit().setAuthor("Test", "test@test.com").setMessage("theirs: add 50 files + modify shared").call();

            // === Back on main: modify the same shared file differently ===
            git.checkout().setName("master").call();
            createAndStageFile(localRepoFolder, gitRepo, "model/shared.xml",
                    "<element name=\"ours-modification\"/>");
            git.commit().setAuthor("Test", "test@test.com").setMessage("ours: modify shared").call();

            // === Step 1: Merge using same sequence as ArchiRepository.mergeBranch() ===
            // Native git conflicts are retained; no JGit replay is needed.
            IArchiRepository repo = new ArchiRepository(localRepoFolder);
            ArchiRepository.MergeOperationResult mergeResult =
                    ((ArchiRepository) repo).mergeBranch("theirs-branch", "Merge theirs", new NullProgressMonitor());

            assertNotNull(mergeResult, "mergeBranch should report the native conflicting merge");
            assertEquals(org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING,
                    mergeResult.status(),
                    "Merge should conflict on shared.xml");

            // === DIAGNOSTIC: Check if theirs' additions are on disk after native merge ===
            int presentAfterMerge = 0;
            for(int i = 0; i < additionCount; i++) {
                File addedFile = new File(localRepoFolder,
                        "model/additions/folder" + (i / 10) + "/Element_id-new" + i + ".xml");
                if(addedFile.exists()) {
                    presentAfterMerge++;
                }
            }
            assertEquals(additionCount, presentAfterMerge,
                    "After native merge (CONFLICTING), all non-conflicting additions from theirs "
                    + "should be on disk. Only " + presentAfterMerge + "/" + additionCount + " found.");

            // === Step 2: Resolve conflict to THEIRS (same as handler.merge()) ===
            org.eclipse.jgit.api.CheckoutCommand checkout = git.checkout();
            checkout.setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS);
            for(String conflictPath : mergeResult.conflictingPaths()) {
                checkout.addPath(conflictPath);
            }
            checkout.call();

            // === Step 3: Commit (same as ArchiRepository.commitChanges()) ===
            // Use the same gitAdd() logic: git add -A
            ((ArchiRepository) repo).commitChanges("Merge with conflicts resolved to theirs", false);

            // === CRITICAL ASSERTION: All 50 additions from theirs must exist after commit ===
            int missingCount = 0;
            List<String> missingFiles = new java.util.ArrayList<>();
            for(int i = 0; i < additionCount; i++) {
                File addedFile = new File(localRepoFolder,
                        "model/additions/folder" + (i / 10) + "/Element_id-new" + i + ".xml");
                if(!addedFile.exists()) {
                    missingCount++;
                    if(missingFiles.size() < 5) {
                        missingFiles.add(addedFile.getName());
                    }
                }
            }
            assertEquals(0, missingCount,
                    "Non-conflicting additions from theirs should survive merge. "
                    + "Missing " + missingCount + " of " + additionCount + " files. "
                    + "Examples: " + missingFiles);

            // Also verify the conflict was resolved to theirs' content
            String sharedContent = java.nio.file.Files.readString(
                    new File(localRepoFolder, "model/shared.xml").toPath());
            assertTrue(sharedContent.contains("theirs-modification"),
                    "Conflict should be resolved to theirs content, got: " + sharedContent);

            // Verify the commit tree matches theirs' additions
            org.eclipse.jgit.revwalk.RevCommit headCommit;
            try(org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(gitRepo)) {
                headCommit = rw.parseCommit(gitRepo.resolve("HEAD"));
            }
            try(org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(gitRepo)) {
                tw.addTree(headCommit.getTree());
                tw.setRecursive(true);
                tw.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create("model/additions/"));
                int committedAdditions = 0;
                while(tw.next()) {
                    committedAdditions++;
                }
                assertEquals(additionCount, committedAdditions,
                        "All " + additionCount + " additions should be in the committed tree");
            }
        }
    }

    // ---- Test helpers ----
    
    private void createAndStageFile(File repoFolder, Repository gitRepo, String fileName, String content)
            throws Exception {
        File file = new File(repoFolder, fileName);
        file.getParentFile().mkdirs();
        try(FileWriter fw = new FileWriter(file)) {
            fw.write(content);
            fw.flush();
        }
        Git.wrap(gitRepo).add().addFilepattern(fileName).call();
    }
    
    /**
     * Create the temp.archimate file that saveChecksum() needs to compute a checksum.
     */
    private void createTempModelFile(File localRepoFolder) throws Exception {
        File tempModel = new File(localRepoFolder, ".git/temp.archimate");
        tempModel.getParentFile().mkdirs();
        try(FileWriter fw = new FileWriter(tempModel)) {
            fw.write("<archimate:model/>");
            fw.flush();
        }
    }
    
    /**
     * Test merge handler that works without SWT.
     * Reloads model using headless loader (no UI thread needed).
     */
    private static class TestMergeHandler implements MergeHandler {
        @Override
        public boolean resolveConflicts(MergeConflictHandler handler, String dialogMessage) {
            return false; // Abort on conflicts in tests
        }

        @Override
        public void resolveFolderMoves(List<FolderMoveInfo> folderMoves) {
            // Use defaults
        }

        @Override
        public void reloadModel(GraficoModelLoader loader, IProgressMonitor monitor) throws IOException {
            // Skip model reload in tests — we're testing git operations, not EMF import
        }
    }
}
