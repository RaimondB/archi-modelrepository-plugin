/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.views.history;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ILazyContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.editor.ui.UIUtils;


/**
 * History Table Viewer
 */
public class HistoryTableViewer extends TableViewer {
    
    /**
     * Maximum number of commits to load from the RevWalk.
     * Limits UI blocking and memory consumption for long-lived repos.
     */
    private static final int MAX_COMMITS = 500;
    
    private RevCommit fLocalCommit, fOriginCommit;
    
    private BranchInfo fSelectedBranch;
    
    /**
     * Guards against stale background loads delivering results after a new load started.
     * Each load increments this; the callback checks if it's still current.
     */
    private final AtomicReference<Thread> fCurrentLoadThread = new AtomicReference<>();
    
    /**
     * Constructor
     */
    public HistoryTableViewer(Composite parent) {
        super(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER | SWT.FULL_SELECTION | SWT.VIRTUAL);
        
        // Mac Item height
        UIUtils.fixMacSiliconItemHeight(getTable());
        
        setup(parent);
        
        setContentProvider(new HistoryContentProvider());
        setLabelProvider(new HistoryLabelProvider());
        
        ColumnViewerToolTipSupport.enableFor(this);
        
        setUseHashlookup(true);
    }

    /**
     * Set things up.
     */
    protected void setup(Composite parent) {
        getTable().setHeaderVisible(true);
        getTable().setLinesVisible(false);
        
        TableColumnLayout tableLayout = (TableColumnLayout)parent.getLayout();
        
        TableViewerColumn column = new TableViewerColumn(this, SWT.NONE, 0);
        column.getColumn().setText(Messages.HistoryTableViewer_0);
        tableLayout.setColumnData(column.getColumn(), new ColumnWeightData(10, false));
        
        column = new TableViewerColumn(this, SWT.NONE, 1);
        column.getColumn().setText(Messages.HistoryTableViewer_1);
        tableLayout.setColumnData(column.getColumn(), new ColumnWeightData(50, false));

        column = new TableViewerColumn(this, SWT.NONE, 2);
        column.getColumn().setText(Messages.HistoryTableViewer_2);
        tableLayout.setColumnData(column.getColumn(), new ColumnWeightData(20, false));
    
        column = new TableViewerColumn(this, SWT.NONE, 3);
        column.getColumn().setText(Messages.HistoryTableViewer_3);
        tableLayout.setColumnData(column.getColumn(), new ColumnWeightData(20, false));
    }
    
    public void doSetInput(IArchiRepository archiRepo) {
        // Get BranchStatus and currentLocalBranch
        try {
            BranchStatus branchStatus = archiRepo.getBranchStatus();
            doSetInput(archiRepo, branchStatus);
            return;
        }
        catch(IOException | GitAPIException ex) {
            ex.printStackTrace();
        }
        
        doSetInput(archiRepo, (BranchStatus)null);
    }
    
    /**
     * Set input with a pre-computed BranchStatus to avoid redundant git operations.
     * Loads commit history on a background thread, then updates the table via asyncExec.
     */
    public void doSetInput(IArchiRepository archiRepo, BranchStatus branchStatus) {
        if(branchStatus != null) {
            fSelectedBranch = branchStatus.getCurrentLocalBranch();
        }
        
        // Show empty table immediately while loading
        setInput(null);
        
        loadCommitsInBackground(archiRepo);
    }
    
    public void setSelectedBranch(BranchInfo branchInfo) {
        if(branchInfo != null && branchInfo.equals(fSelectedBranch)) {
            return;
        }

        fSelectedBranch = branchInfo;
        
        // Reload commits in background for new branch
        Object input = getInput();
        if(input instanceof IArchiRepository) {
            loadCommitsInBackground((IArchiRepository)input);
        }
        else {
            // Fallback: re-trigger from scratch (doSetInput stored nothing yet)
            setInput(null);
        }
    }
    
    /**
     * Load commits on a background thread and deliver results to the UI thread.
     * If a new load starts before the previous one finishes, the stale result is discarded.
     */
    private void loadCommitsInBackground(IArchiRepository archiRepo) {
        Thread loadThread = Thread.ofVirtual().name("HistoryTableViewer-LoadCommits").start(() -> { //$NON-NLS-1$
            // Capture this thread reference for staleness check in asyncExec
            Thread thisThread = Thread.currentThread();
            
            // Compute commits off the UI thread
            long tBg = System.nanoTime();
            List<RevCommit> commits = getCommits(archiRepo);
            UIPerfLogger.log("[HistoryTable]", "getCommits(" + commits.size() + ")", tBg); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            
            Display display = getTable().getDisplay();
            if(!display.isDisposed()) {
                display.asyncExec(() -> {
                    // Discard result if a newer load was started
                    if(fCurrentLoadThread.get() != thisThread) {
                        UIPerfLogger.log("[HistoryTable]", "asyncExec DISCARDED (stale)"); //$NON-NLS-1$ //$NON-NLS-2$
                        return;
                    }
                    if(getTable().isDisposed()) {
                        return;
                    }
                    
                    long tUi = System.nanoTime();
                    // Suppress redraws during bulk update
                    getTable().setRedraw(false);
                    try {
                        // Deliver commits to the content provider and set input
                        HistoryContentProvider provider = (HistoryContentProvider)getContentProvider();
                        provider.setCommits(commits);
                        setInput(archiRepo);
                        setItemCount(commits.size());
                    }
                    finally {
                        getTable().setRedraw(true);
                    }
                    
                    // Layout and select outside setRedraw block
                    UIPerfLogger.log("[HistoryTable]", "parent.layout() called"); //$NON-NLS-1$
                    getTable().getParent().layout(new Control[] { getTable() });
                    
                    // Select first row
                    Object element = getElementAt(0);
                    if(element != null) {
                        setSelection(new StructuredSelection(element), true);
                    }
                    UIPerfLogger.log("[HistoryTable]", "asyncExec UI delivery (" + commits.size() + " commits)", tUi); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                });
            }
        });
        
        fCurrentLoadThread.set(loadThread);
    }
    
    // ===============================================================================================
	// ===================================== Table Model ==============================================
	// ===============================================================================================
    
    /**
     * The Model for the Table.
     * Commits are loaded externally (on a background thread) and delivered via setCommits().
     */
    class HistoryContentProvider implements ILazyContentProvider {
        private List<RevCommit> commits = Collections.emptyList();
        
        @Override
        public void inputChanged(Viewer v, Object oldInput, Object newInput) {
            // Don't reload commits here — they are loaded on a background thread
            // and delivered via setCommits() before setInput() is called.
        }
        
        void setCommits(List<RevCommit> newCommits) {
            this.commits = newCommits != null ? newCommits : Collections.emptyList();
        }

        @Override
        public void dispose() {
        }

        @Override
        public void updateElement(int index) {
            if(index < commits.size()) {
                replace(commits.get(index), index);
            }
        }
    }
    
    /**
     * Load commits from git. Called on a background thread.
     * Limits to MAX_COMMITS to avoid unbounded memory and time.
     */
    private List<RevCommit> getCommits(Object parent) {
        List<RevCommit> commits = new ArrayList<>();
        fLocalCommit = null;
        fOriginCommit = null;
        
        if(!(parent instanceof IArchiRepository) || fSelectedBranch == null) {
            return commits;
        }
        
        IArchiRepository repo = (IArchiRepository)parent;
        
        // Local Repo was deleted
        if(!repo.getLocalRepositoryFolder().exists()) {
            return commits;
        }

        try(Repository repository = Git.open(repo.getLocalRepositoryFolder()).getRepository()) {
            try(RevWalk revWalk = new RevWalk(repository)) {
                ObjectId objectID = repository.resolve(fSelectedBranch.getLocalBranchNameFor());
                if(objectID != null) {
                    fLocalCommit = revWalk.parseCommit(objectID);
                    revWalk.markStart(fLocalCommit); 
                }
                
                objectID = repository.resolve(fSelectedBranch.getRemoteBranchNameFor());
                if(objectID != null) {
                    fOriginCommit = revWalk.parseCommit(objectID);
                    revWalk.markStart(fOriginCommit);
                }
                
                // Collect commits with limit
                int count = 0;
                for(RevCommit commit : revWalk) {
                    commits.add(commit);
                    if(++count >= MAX_COMMITS) {
                        break;
                    }
                }
                
                revWalk.dispose();
            }
        }
        catch(IOException ex) {
            ex.printStackTrace();
        }
        
        return commits;
    }
    
    // ===============================================================================================
	// ===================================== Label Model ==============================================
	// ===============================================================================================

    class HistoryLabelProvider extends CellLabelProvider {
        
        DateFormat dateFormat = DateFormat.getDateTimeInstance();
        
        public String getColumnText(RevCommit commit, int columnIndex) {
            switch(columnIndex) {
                case 0:
                    return commit.getName().substring(0, 8);
                    
                case 1:
                    return commit.getShortMessage();
                    
                case 2:
                    return commit.getAuthorIdent().getName();
                
                case 3:
                    return dateFormat.format(new Date(commit.getCommitTime() * 1000L));
                    
                default:
                    return null;
            }
        }

        @Override
        public void update(ViewerCell cell) {
            if(cell.getElement() instanceof RevCommit) {
                RevCommit commit = (RevCommit)cell.getElement();
                
                cell.setText(getColumnText(commit, cell.getColumnIndex()));
                
                if(cell.getColumnIndex() == 1) {
                    Image image = null;
                    
                    if(commit.equals(fLocalCommit) && commit.equals(fOriginCommit)) {
                        image = IModelRepositoryImages.ImageFactory.getImage(IModelRepositoryImages.ICON_HISTORY_VIEW);
                    }
                    else if(commit.equals(fOriginCommit)) {
                        image = IModelRepositoryImages.ImageFactory.getImage(IModelRepositoryImages.ICON_REMOTE);
                    }
                    else if(commit.equals(fLocalCommit)) {
                        image = IModelRepositoryImages.ImageFactory.getImage(IModelRepositoryImages.ICON_LOCAL);
                    }
                    
                    cell.setImage(image);
                }
            }
        }
        
        @Override
        public String getToolTipText(Object element) {
            if(element instanceof RevCommit) {
                RevCommit commit = (RevCommit)element;
                
                String s = ""; //$NON-NLS-1$
                
                if(commit.equals(fLocalCommit) && commit.equals(fOriginCommit)) {
                    s += Messages.HistoryTableViewer_4 + " "; //$NON-NLS-1$
                }
                else if(commit.equals(fLocalCommit)) {
                    s += Messages.HistoryTableViewer_5 + " "; //$NON-NLS-1$
                }

                else if(commit.equals(fOriginCommit)) {
                    s += Messages.HistoryTableViewer_6 + " "; //$NON-NLS-1$
                }
                
                s += commit.getFullMessage().trim();
                
                return s;
            }
            
            return null;
        }
    }
}
