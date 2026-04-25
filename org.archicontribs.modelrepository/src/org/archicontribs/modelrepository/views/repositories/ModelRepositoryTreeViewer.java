/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.views.repositories;

import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.grafico.RepositoryListenerManager;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;

import com.archimatetool.editor.ui.ColorFactory;
import com.archimatetool.editor.ui.UIUtils;
import com.archimatetool.editor.utils.StringUtils;


/**
 * Repository Tree Viewer
 */
public class ModelRepositoryTreeViewer extends TreeViewer implements IRepositoryListener {
    
    // Cache status for expensive calls
    private class StatusCache {
        BranchInfo branchInfo;
        boolean hasLocalChanges;
        
        public StatusCache(BranchInfo branchInfo, boolean hasLocalChanges) {
            this.branchInfo = branchInfo;
            this.hasLocalChanges = hasLocalChanges;
        }
    }
    
    private volatile Map<IArchiRepository, StatusCache> cache = new Hashtable<>();
    
    /**
     * Background thread for status cache refresh
     */
    private volatile Thread fCacheRefreshThread;

    /**
     * Constructor
     */
    public ModelRepositoryTreeViewer(Composite parent) {
        super(parent, SWT.MULTI);
        
        // Mac Item height
        UIUtils.fixMacSiliconItemHeight(getTree());
        
        setContentProvider(new ModelRepoTreeContentProvider());
        setLabelProvider(new ModelRepoTreeLabelProvider());
        
        RepositoryListenerManager.INSTANCE.addListener(this);
        
        // Dispose of this and clean up
        getTree().addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e) {
                RepositoryListenerManager.INSTANCE.removeListener(ModelRepositoryTreeViewer.this);
            }
        });
        
        ColumnViewerToolTipSupport.enableFor(this);
        
        setComparator(new ViewerComparator(Collator.getInstance()) {
            @Override
            public int compare(Viewer viewer, Object e1, Object e2) {
                IArchiRepository r1 = (IArchiRepository)e1;
                IArchiRepository r2 = (IArchiRepository)e2;
                return getComparator().compare(r1.getName(), r2.getName());
            }
        });
        
        setInput(""); //$NON-NLS-1$
        
        // Refresh File System Job
        RefreshFilesJob.getInstance().init(this);
        
        // Fetch Job
        FetchJob.getInstance().init(this);
    }

    protected void refreshInBackground() {
        if(!getControl().isDisposed()) {
            refreshStatusCacheInBackground(() -> {
                if(!getControl().isDisposed()) {
                    refresh();
                }
            });
        }
    }

    @Override
    public void repositoryChanged(String eventName, IArchiRepository repository) {
        UIPerfLogger.log("[RepoTreeViewer]", "repositoryChanged(" + eventName + ") received"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        switch(eventName) {
            case IRepositoryListener.REPOSITORY_ADDED:
                refreshStatusCacheInBackground(() -> {
                    if(!getControl().isDisposed()) {
                        refresh();
                        setSelection(new StructuredSelection(repository));
                    }
                });
                break;
                
            case IRepositoryListener.REPOSITORY_DELETED:
                refreshStatusCacheInBackground(() -> {
                    if(!getControl().isDisposed()) {
                        refresh();
                    }
                });
                break;

            default:
                refreshStatusCacheInBackground(() -> {
                    if(!getControl().isDisposed()) {
                        refresh();
                    }
                });
                break;
        }
    }
    
    /**
     * @return Root folder of model repos
     */
    protected File getRootFolder() {
        return ModelRepositoryPlugin.getInstance().getUserModelRepositoryFolder();
    }
    
    /**
     * @return All repos in the file system
     */
    protected List<IArchiRepository> getRepositories(File folder) {
        // Only show top level folders that are git repos
        List<IArchiRepository> repos = new ArrayList<IArchiRepository>();
        
        if(folder.exists() && folder.isDirectory()) {
            File[] files = getRootFolder().listFiles();
            if(files != null) {
                for(File file : files) {
                    if(GraficoUtils.isGitRepository(file)) {
                        repos.add(new ArchiRepository(file));
                    }
                }
            }
        }
        
        return repos;
    }
    
    /**
     * Update the status cache (can be called from any thread)
     */
    private void updateStatusCache(List<IArchiRepository> repos) {
        Map<IArchiRepository, StatusCache> newCache = new ConcurrentHashMap<>();
        
        // Process repos in parallel using virtual threads
        CountDownLatch latch = new CountDownLatch(repos.size());
        
        for(IArchiRepository repo : repos) {
            Thread.ofVirtual().name("StatusCache-" + repo.getName()).start(() -> { //$NON-NLS-1$
                try {
                    BranchInfo branchInfo = repo.getBranchStatus().getCurrentLocalBranch();
                    if(branchInfo != null) { // This can be null!!
                        StatusCache sc = new StatusCache(branchInfo, repo.hasLocalChanges());
                        newCache.put(repo, sc);
                    }
                }
                catch(IOException | GitAPIException ex) {
                    ex.printStackTrace();
                    ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Error getting Model Repository Status", ex); //$NON-NLS-1$
                }
                finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        
        cache = newCache;
    }
    
    /**
     * Refresh the status cache on a background thread, then run the UI callback on the display thread.
     */
    private void refreshStatusCacheInBackground(Runnable uiCallback) {
        // Cancel any stale refresh thread
        Thread oldThread = fCacheRefreshThread;
        if(oldThread != null) {
            oldThread.interrupt();
        }
        
        Thread refreshThread = Thread.ofVirtual().name("ModelRepositoryTreeViewer-CacheRefresh").start(() -> { //$NON-NLS-1$
            try {
                long tBg = System.nanoTime();
                List<IArchiRepository> repos = getRepositories(getRootFolder());
                updateStatusCache(repos);
                UIPerfLogger.log("[RepoTreeViewer]", "refreshStatusCacheInBackground updateStatusCache", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                
                if(!getControl().isDisposed()) {
                    getControl().getDisplay().asyncExec(() -> {
                        if(!getControl().isDisposed()) {
                            long tUi = System.nanoTime();
                            uiCallback.run();
                            UIPerfLogger.log("[RepoTreeViewer]", "refreshStatusCacheInBackground UI callback", tUi); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    });
                }
            }
            catch(Exception ex) {
                if(!(ex instanceof InterruptedException)) {
                    ex.printStackTrace();
                }
            }
        });
        fCacheRefreshThread = refreshThread;
    }
    
    // ===============================================================================================
	// ===================================== Tree Model ==============================================
	// ===============================================================================================
    
    /**
     * The model for the Tree.
     */
    class ModelRepoTreeContentProvider implements ITreeContentProvider {
        
        @Override
        public void inputChanged(Viewer v, Object oldInput, Object newInput) {
        }
        
        @Override
        public void dispose() {
        }
        
        @Override
        public Object[] getElements(Object parent) {
            return getChildren(getRootFolder());
        }
        
        @Override
        public Object getParent(Object child) {
            if(child instanceof File) {
                return ((File)child).getParentFile();
            }
            if(child instanceof IArchiRepository) {
                return ((IArchiRepository)child).getLocalRepositoryFolder().getParentFile();
            }
            return null;
        }
        
        @Override
        public Object[] getChildren(Object parent) {
            if(parent instanceof File) {
                List<IArchiRepository> repos = getRepositories((File)parent);
                // If cache is empty (first load), populate synchronously for initial display
                if(cache.isEmpty() && !repos.isEmpty()) {
                    updateStatusCache(repos);
                }
                return repos.toArray();
            }
            
            return new Object[0];
        }
        
        @Override
        public boolean hasChildren(Object parent) {
            return false;
        }
    }
    
    // ===============================================================================================
	// ===================================== Label Model ==============================================
	// ===============================================================================================

    class ModelRepoTreeLabelProvider extends CellLabelProvider {
        Image getImage(IArchiRepository repo) {
            Image image = IModelRepositoryImages.ImageFactory.getImage(IModelRepositoryImages.ICON_MODEL);
            
            StatusCache sc = cache.get(repo);
            if(sc != null) {
                if(sc.hasLocalChanges) {
                    image = IModelRepositoryImages.ImageFactory.getOverlayImage(image,
                            IModelRepositoryImages.ICON_LEFT_BALL_OVERLAY, IDecoration.BOTTOM_LEFT);
                }
                
                if(sc.branchInfo.hasUnpushedCommits()) {
                    image = IModelRepositoryImages.ImageFactory.getOverlayImage(image,
                            IModelRepositoryImages.ICON_RIGHT_BALL_OVERLAY, IDecoration.BOTTOM_RIGHT);
                }
                
                if(sc.branchInfo.hasRemoteCommits()) {
                    image = IModelRepositoryImages.ImageFactory.getOverlayImage(image,
                            IModelRepositoryImages.ICON_TOP_BALL_OVERLAY, IDecoration.TOP_RIGHT);
                }
            }
            
            return image;
        }
        
        String getStatusText(IArchiRepository repo) {
            String s = ""; //$NON-NLS-1$
            
            StatusCache sc = cache.get(repo);
            if(sc != null) {
                if(sc.hasLocalChanges) {
                    s += Messages.ModelRepositoryTreeViewer_2;
                }
                if(sc.branchInfo.hasUnpushedCommits()) {
                    if(StringUtils.isSet(s)) {
                        s += " | "; //$NON-NLS-1$
                    }
                    s += Messages.ModelRepositoryTreeViewer_0;
                }
                if(sc.branchInfo.hasRemoteCommits()) {
                    if(StringUtils.isSet(s)) {
                        s += " | "; //$NON-NLS-1$
                    }
                    s += Messages.ModelRepositoryTreeViewer_1;
                }
                if(!StringUtils.isSet(s)) {
                    s = Messages.ModelRepositoryTreeViewer_3;
                }
            }
            
            return s;
        }
        
        @Override
        public void update(ViewerCell cell) {
            if(cell.getElement() instanceof IArchiRepository) {
                IArchiRepository repo = (IArchiRepository)cell.getElement();
                
                // Local repo was perhaps deleted
                if(!repo.getLocalRepositoryFolder().exists()) {
                    return;
                }
                
                // Clear this first
                cell.setForeground(null);
                
                StatusCache sc = cache.get(repo);
                if(sc != null) {
                    // Repository name and current branch
                    cell.setText(repo.getName() + " [" + sc.branchInfo.getShortName() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    
                    // Red text
                    if(sc.branchInfo.hasUnpushedCommits() || sc.branchInfo.hasRemoteCommits() || sc.hasLocalChanges) {
                        cell.setForeground(ColorFactory.get(255, 64, 0));
                    }
                }
                else {
                    cell.setText(repo.getName());
                }

                // Image
                cell.setImage(getImage(repo));
            }
        }
        
        @Override
        public String getToolTipText(Object element) {
            if(element instanceof IArchiRepository) {
                IArchiRepository repo = (IArchiRepository)element;
                
                String s = repo.getName();
                
                String status = getStatusText(repo);
                if(StringUtils.isSet(status)) {
                    s += "\n" + status.replaceAll(" \\| ", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                
                return s;
            }
            
            return null;
        }
    }
}
