/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;

/**
 * Bulk-loads file contents for merge conflict paths from the git DirCache (index).
 * 
 * <p>During a merge conflict, the DirCache contains conflict stages for each
 * conflicting path: stage 2 = ours (HEAD), stage 3 = theirs (merge branch).
 * This class reads the DirCache once and loads all conflicting file contents
 * in a single pass, replacing N individual Git.open() + TreeWalk operations
 * with one DirCache.read() + N ObjectLoader.getBytes() calls.</p>
 * 
 * <p>For paths not in the cache (e.g., non-conflicting paths discovered during
 * move detection), falls back to {@link IArchiRepository#getFileContents}.</p>
 * 
 * @author coArchi
 */
class MergeContentCache {

    /** Ours (HEAD / stage 2) content. Key present with null value = file doesn't exist at that stage. */
    private final Map<String, byte[]> oursContent;
    
    /** Theirs (stage 3) content. Key present with null value = file doesn't exist at that stage. */
    private final Map<String, byte[]> theirsContent;
    
    /** The set of paths that were pre-cached (all conflict paths). */
    private final Set<String> cachedPaths;
    
    /** Fallback repository for cache misses. */
    private final IArchiRepository archiRepo;
    
    /** The local ref string (e.g. "HEAD"). */
    private final String localRef;
    
    /** The their ref string (e.g. "origin/master"). */
    private final String theirRef;

    /**
     * Build the cache by reading the DirCache once and extracting content
     * for all conflicting paths at stages 2 (ours) and 3 (theirs).
     * 
     * @param repoFolder the local repository folder (.git parent)
     * @param conflictPaths the set of conflicting file paths from MergeResult
     * @param archiRepo fallback for cache misses
     * @param localRef the local ref string (e.g. "HEAD")
     * @param theirRef the their ref string (e.g. "origin/master")
     */
    MergeContentCache(File repoFolder, Set<String> conflictPaths,
            IArchiRepository archiRepo, String localRef, String theirRef) throws IOException {
        this.cachedPaths = conflictPaths;
        this.archiRepo = archiRepo;
        this.localRef = localRef;
        this.theirRef = theirRef;
        this.oursContent = new HashMap<>(conflictPaths.size());
        this.theirsContent = new HashMap<>(conflictPaths.size());
        
        // Pre-populate all conflict paths with null (= not found at that stage)
        for(String path : conflictPaths) {
            oursContent.put(path, null);
            theirsContent.put(path, null);
        }
        
        long t = System.nanoTime();
        int loaded = 0;
        
        try(Repository repository = Git.open(repoFolder).getRepository()) {
            DirCache dirCache = repository.readDirCache();
            
            for(int i = 0; i < dirCache.getEntryCount(); i++) {
                DirCacheEntry entry = dirCache.getEntry(i);
                String entryPath = entry.getPathString();
                
                if(!conflictPaths.contains(entryPath)) {
                    continue;
                }
                
                int stage = entry.getStage();
                if(stage == 2) {
                    // Ours (HEAD)
                    ObjectLoader loader = repository.open(entry.getObjectId());
                    oursContent.put(entryPath, loader.getBytes());
                    loaded++;
                }
                else if(stage == 3) {
                    // Theirs
                    ObjectLoader loader = repository.open(entry.getObjectId());
                    theirsContent.put(entryPath, loader.getBytes());
                    loaded++;
                }
            }
        }
        
        log(IStatus.INFO, "[MergeContentCache] Loaded " + loaded + " entries for " //$NON-NLS-1$ //$NON-NLS-2$
                + conflictPaths.size() + " conflict paths in " //$NON-NLS-1$
                + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$
    }

    /**
     * Get file content for a path at a given ref.
     * 
     * <p>For conflict paths, returns cached content from DirCache stages.
     * For non-conflict paths, falls back to individual repository lookup.</p>
     * 
     * @param path the file path relative to repo root
     * @param ref the git ref ("HEAD", branch name, etc.)
     * @return the file content bytes, or null if the file doesn't exist at that ref
     */
    byte[] getContent(String path, String ref) throws IOException {
        // Check cache for conflict paths
        if(cachedPaths.contains(path)) {
            if(localRef.equals(ref)) {
                return oursContent.get(path);
            }
            if(theirRef.equals(ref)) {
                return theirsContent.get(path);
            }
        }
        
        // Cache miss — fall back to individual lookup
        return archiRepo.getFileContents(path, ref);
    }

    private static void log(int severity, String message) {
        ModelRepositoryPlugin plugin = ModelRepositoryPlugin.getInstance();
        if(plugin != null) {
            plugin.log(severity, message, null);
        }
    }
}
