/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Encapsulates all native git process execution.
 * <p>
 * Every method in this class executes the system {@code git} binary via
 * {@link ProcessBuilder}. Methods follow a consistent return convention:
 * <ul>
 *   <li>{@code boolean} — {@code true} if native git succeeded, {@code false}
 *       if native git is not available (caller should fall back to JGit)</li>
 *   <li>{@code Boolean} — {@link Boolean#TRUE} for success, {@link Boolean#FALSE}
 *       for a known failure state (e.g. merge conflicts), {@code null} if native
 *       git is not available</li>
 * </ul>
 * <p>
 * This class is stateless — the repository working directory is passed as a
 * parameter to each method.
 * <p>
 * <b>Important:</b> After any native git operation that modifies the working tree
 * or index, the caller must open and close a JGit {@code Git} instance to sync
 * JGit's in-memory state with the on-disk changes.
 *
 * @see ArchiRepository
 */
@SuppressWarnings("nls")
class NativeGitExecutor {

    /**
     * Result of a native git command execution.
     */
    record Result(int exitCode, String output) {
        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    // ---- Core runner ----

    /**
     * Run a native git command, streaming output lines to the progress monitor.
     *
     * @param repoDir the repository working directory
     * @param monitor optional progress monitor — output lines are reported as subtask (can be null)
     * @param command the git command and arguments
     * @return a {@link Result} with exit code and collected output
     * @throws IOException if the process cannot be started (including "git not found")
     * @throws InterruptedException if the process was interrupted
     */
    static Result run(File repoDir, IProgressMonitor monitor, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if(monitor != null && !line.isBlank()) {
                    monitor.subTask(line.trim());
                }
            }
        }

        int exitCode = process.waitFor();
        return new Result(exitCode, output.toString());
    }

    // ---- Simple process execution (no progress reporting) ----

    /**
     * Run a simple native git command without progress monitoring.
     * Drains all output and returns it.
     *
     * @param repoDir the repository working directory
     * @param command the git command and arguments
     * @return a {@link Result} with exit code and collected output
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the process was interrupted
     */
    private static Result runSimple(File repoDir, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        return new Result(exitCode, output.toString());
    }

    // ---- Git commands ----

    /**
     * Native git checkout.
     *
     * @param repoDir the repository working directory
     * @param branchName the branch name (short name, e.g. "main")
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the checkout failed
     */
    static boolean checkout(File repoDir, String branchName) throws IOException {
        try {
            Result result = runSimple(repoDir, "git", "checkout", branchName);
            if(!result.isSuccess()) {
                throw new IOException("Git checkout failed: " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git checkout interrupted", ex);
        }
    }

    /**
     * Native git checkout of specific paths from a commit.
     * This overwrites the working tree AND index with the commit's content
     * for the given paths. Much faster than TreeWalk + write for large trees.
     * 
     * <p>Usage: {@code git checkout <commitSha> -- path1 path2 ...}</p>
     *
     * @param repoDir the repository working directory
     * @param commitSha the full or abbreviated commit SHA
     * @param paths the paths to restore (e.g. "model/", "images/")
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the checkout failed
     */
    static boolean checkoutPathsFromCommit(File repoDir, String commitSha, String... paths) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("checkout");
            command.add(commitSha);
            command.add("--");
            for(String path : paths) {
                command.add(path);
            }
            Result result = runSimple(repoDir, command.toArray(new String[0]));
            if(!result.isSuccess()) {
                throw new IOException("Git checkout paths failed: " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git checkout paths interrupted", ex);
        }
    }

    /**
     * Native git add -A (stage all changes).
     *
     * @param repoDir the repository working directory
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the add failed
     */
    static boolean addAll(File repoDir) throws IOException {
        try {
            Result result = runSimple(repoDir, "git", "add", "-A");
            if(!result.isSuccess()) {
                throw new IOException("Git add failed: " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git add interrupted", ex);
        }
    }

    /**
     * Native git add -A for specific paths.
     *
     * @param repoDir the repository working directory
     * @param paths repo-relative paths to stage
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the add failed
     */
    static boolean addPaths(File repoDir, Set<String> paths) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("add");
            command.add("-A");
            command.add("--");
            command.addAll(paths);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repoDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git add failed: " + output.toString());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git add interrupted", ex);
        }
    }

    /**
     * Native git reset.
     *
     * @param repoDir the repository working directory
     * @param ref the ref to reset to (e.g. "HEAD", commit SHA)
     * @param resetMode the reset mode flag (--hard, --mixed, --soft)
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the reset failed
     */
    static boolean reset(File repoDir, String ref, String resetMode) throws IOException {
        try {
            Result result = runSimple(repoDir, "git", "reset", resetMode, ref);
            if(!result.isSuccess()) {
                throw new IOException("Git reset failed: " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git reset interrupted", ex);
        }
    }

    /**
     * Native git status --porcelain (check for uncommitted changes).
     *
     * @param repoDir the repository working directory
     * @return {@link Boolean#TRUE} if dirty, {@link Boolean#FALSE} if clean,
     *         {@code null} if native git is not available
     */
    static Boolean status(File repoDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.directory(repoDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            boolean hasOutput = false;
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                hasOutput = (line != null);
                while(reader.readLine() != null) {
                    // drain
                }
            }

            int exitCode = process.waitFor();
            if(exitCode != 0) {
                return null;
            }
            return hasOutput;
        }
        catch(IOException ex) {
            return null;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Native git merge (no custom commit message).
     *
     * @param repoDir the repository working directory
     * @param remoteBranch the remote branch ref (e.g. "origin/master")
     * @param monitor optional progress monitor
     * @return {@link Boolean#TRUE} if merge succeeded,
     *         {@link Boolean#FALSE} if conflicts detected,
     *         {@code null} if native git is not available
     * @throws IOException if merge failed for a reason other than conflicts
     */
    static Boolean merge(File repoDir, String remoteBranch, IProgressMonitor monitor)
            throws IOException {
        try {
            Result result = run(repoDir, monitor, "git", "merge", remoteBranch);

            if(result.isSuccess()) {
                return Boolean.TRUE;
            }

            if(result.output().contains("CONFLICT") || result.output().contains("Automatic merge failed")) {
                return Boolean.FALSE;
            }

            throw new IOException("Git merge failed (exit " + result.exitCode() + "): " + result.output());
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return null;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git merge interrupted", ex);
        }
    }

    /**
     * Native git merge with a custom commit message.
     *
     * @param repoDir the repository working directory
     * @param branchName the branch to merge
     * @param commitMessage the commit message
     * @param monitor optional progress monitor
     * @return {@link Boolean#TRUE} if merge succeeded,
     *         {@link Boolean#FALSE} if conflicts detected,
     *         {@code null} if native git is not available
     * @throws IOException if merge failed for a reason other than conflicts
     */
    static Boolean mergeWithMessage(File repoDir, String branchName, String commitMessage,
            IProgressMonitor monitor) throws IOException {
        try {
            Result result = run(repoDir, monitor,
                    "git", "merge", branchName, "-m", commitMessage);

            if(result.isSuccess()) {
                return Boolean.TRUE;
            }

            if(result.output().contains("CONFLICT") || result.output().contains("Automatic merge failed")) {
                return Boolean.FALSE;
            }

            throw new IOException("Git merge failed (exit " + result.exitCode() + "): " + result.output());
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return null;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git merge interrupted", ex);
        }
    }

    /**
     * Abort a native git merge in progress.
     *
     * @param repoDir the repository working directory
     * @throws IOException if the abort failed
     */
    static void abortMerge(File repoDir) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "merge", "--abort");
            pb.directory(repoDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while(reader.readLine() != null) { /* drain */ }
            }

            process.waitFor();
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git merge --abort interrupted", ex);
        }
    }

    /**
     * Native git clone.
     *
     * @param repoURL the repository URL to clone from
     * @param targetFolder the target folder
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the clone failed
     */
    static boolean clone(String repoURL, File targetFolder) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("clone");
            command.add(repoURL);
            command.add(targetFolder.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(targetFolder.getParentFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git clone failed: " + output.toString());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git clone interrupted", ex);
        }
    }

    /**
     * Native git fetch --prune --progress.
     *
     * @param repoDir the repository working directory
     * @param monitor optional progress monitor
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the fetch failed
     */
    static boolean fetch(File repoDir, IProgressMonitor monitor) throws IOException {
        try {
            Result result = run(repoDir, monitor, "git", "fetch", "--prune", "--progress");
            if(!result.isSuccess()) {
                throw new IOException("Git fetch failed (exit " + result.exitCode() + "): " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git fetch interrupted", ex);
        }
    }

    /**
     * Native git push --progress.
     *
     * @param repoDir the repository working directory
     * @param monitor optional progress monitor
     * @return true if succeeded, false if native git is not available
     * @throws IOException if the push failed
     */
    static boolean push(File repoDir, IProgressMonitor monitor) throws IOException {
        try {
            Result result = run(repoDir, monitor, "git", "push", "--progress");
            if(!result.isSuccess()) {
                throw new IOException("Git push failed (exit " + result.exitCode() + "): " + result.output());
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git push interrupted", ex);
        }
    }

    // ---- Cross-path deletion support ----

    /**
     * Use native git diff and ls-tree to find element IDs truly deleted between
     * merge base and each parent. An element is "truly deleted" by a parent if
     * the diff shows it removed at an old path AND it is NOT present anywhere
     * in that same parent's tree (i.e. not just moved to a new path).
     *
     * @param repoRoot the repository working directory
     * @param mergeBaseSha the merge base commit SHA
     * @param oursSha our commit SHA
     * @param theirsSha their commit SHA
     * @param deletedIds set to populate with deleted element IDs
     * @return true if native git was available, false if JGit fallback needed
     * @throws IOException if the git commands fail
     */
    static boolean collectDeletedIds(File repoRoot, String mergeBaseSha,
            String oursSha, String theirsSha, Set<String> deletedIds) throws IOException {
        try {
            Set<String> deletedByOurs = new HashSet<>();
            Set<String> deletedByTheirs = new HashSet<>();
            collectDeletedIdsFromDiff(repoRoot, mergeBaseSha, oursSha, deletedByOurs);
            collectDeletedIdsFromDiff(repoRoot, mergeBaseSha, theirsSha, deletedByTheirs);

            Set<String> presentInOurs = new HashSet<>();
            Set<String> presentInTheirs = new HashSet<>();
            collectPresentIdsFromTree(repoRoot, oursSha, presentInOurs);
            collectPresentIdsFromTree(repoRoot, theirsSha, presentInTheirs);

            for(String id : deletedByOurs) {
                if(!presentInOurs.contains(id)) {
                    deletedIds.add(id);
                }
            }
            for(String id : deletedByTheirs) {
                if(!presentInTheirs.contains(id)) {
                    deletedIds.add(id);
                }
            }
            return true;
        }
        catch(IOException ex) {
            if(isGitNotFound(ex)) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * Run {@code git diff --name-only --diff-filter=D base..commit} and extract
     * element IDs from deleted GRAFICO filenames.
     */
    private static void collectDeletedIdsFromDiff(File repoRoot, String baseSha,
            String commitSha, Set<String> deletedIds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "diff", "--name-only", "--diff-filter=D",
                baseSha, commitSha, "--", IGraficoConstants.MODEL_FOLDER);
        pb.directory(repoRoot);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        }
        catch(IOException ex) {
            throw ex;
        }

        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                String fileName = line.substring(line.lastIndexOf('/') + 1);
                String id = ArchiRepository.extractIdFromElementFileName(fileName);
                if(id != null) {
                    deletedIds.add(id);
                }
            }
        }

        try {
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("git diff failed with exit code " + exitCode);
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git diff interrupted", e);
        }
    }

    /**
     * Use native {@code git ls-tree -r --name-only} to collect element IDs
     * present in a commit's tree.
     */
    private static void collectPresentIdsFromTree(File repoRoot, String commitSha,
            Set<String> presentIds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "ls-tree", "-r", "--name-only", commitSha,
                "--", IGraficoConstants.MODEL_FOLDER);
        pb.directory(repoRoot);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                String fileName = line.substring(line.lastIndexOf('/') + 1);
                String id = ArchiRepository.extractIdFromElementFileName(fileName);
                if(id != null) {
                    presentIds.add(id);
                }
            }
        }

        try {
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("git ls-tree failed with exit code " + exitCode);
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git ls-tree interrupted", e);
        }
    }

    // ---- Utility ----

    /**
     * Extract short branch name from full ref or return as-is if already short.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "refs/heads/main"} → {@code "main"}</li>
     *   <li>{@code "refs/remotes/origin/main"} → {@code "main"}</li>
     *   <li>{@code "main"} → {@code "main"}</li>
     * </ul>
     *
     * @param branchName full ref or short name
     * @return short branch name
     */
    static String extractShortBranchName(String branchName) {
        if(branchName == null) {
            return null;
        }

        if(branchName.startsWith("refs/heads/")) {
            return branchName.substring("refs/heads/".length());
        }

        if(branchName.startsWith("refs/remotes/origin/")) {
            return branchName.substring("refs/remotes/origin/".length());
        }

        if(branchName.startsWith("refs/remotes/")) {
            int slashAfterRemote = branchName.indexOf('/', "refs/remotes/".length());
            if(slashAfterRemote > 0 && slashAfterRemote < branchName.length() - 1) {
                return branchName.substring(slashAfterRemote + 1);
            }
        }

        return branchName;
    }

    /**
     * Check if an IOException indicates that the {@code git} binary is not found.
     */
    private static boolean isGitNotFound(IOException ex) {
        String message = ex.getMessage();
        return message != null
                && (message.contains("Cannot run program") || message.contains("not found"));
    }

    private NativeGitExecutor() {
        // Utility class — no instances
    }
}
