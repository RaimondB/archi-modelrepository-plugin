/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.views.history;

import java.io.IOException;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.actions.ExtractModelFromCommitAction;
import org.archicontribs.modelrepository.actions.ResetToRemoteCommitAction;
import org.archicontribs.modelrepository.actions.RestoreCommitAction;
import org.archicontribs.modelrepository.actions.UndoLastCommitAction;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.grafico.RepositoryListenerManager;
import org.eclipse.help.HelpSystem;
import org.eclipse.help.IContext;
import org.eclipse.help.IContextProvider;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.IContributedContentsView;
import org.eclipse.ui.part.ViewPart;

import com.archimatetool.model.IArchimateModel;


/**
 * History Viewpart
 */
public class HistoryView
extends ViewPart
implements IContextProvider, ISelectionListener, IRepositoryListener, IContributedContentsView {

	public static String ID = ModelRepositoryPlugin.PLUGIN_ID + ".historyView"; //$NON-NLS-1$
    public static String HELP_ID = ModelRepositoryPlugin.PLUGIN_ID + ".modelRepositoryViewHelp"; //$NON-NLS-1$
    
    private Label fRepoLabel;

    private HistoryTableViewer fHistoryTableViewer;
    
    private RevisionCommentViewer fCommentViewer;
    
    private BranchesViewer fBranchesViewer;
    
    /*
     * Actions
     */
    private ExtractModelFromCommitAction fActionExtractCommit;
    private RestoreCommitAction fActionRestoreCommit;
    private UndoLastCommitAction fActionUndoLastCommit;
    private ResetToRemoteCommitAction fActionResetToRemoteCommit;
    
    
    /*
     * Selected repository
     */
    private IArchiRepository fSelectedRepository;
    
    /**
     * Tracks the current background loading thread so stale threads can be detected.
     * When a new selection is made, this is updated; the stale thread's asyncExec callback
     * checks repo.equals(fSelectedRepository) and discards stale results.
     */
    private volatile Thread fCurrentLoadThread;
    
    /**
     * Cached HEAD ObjectId from selectionChanged bg thread.
     * Used by updateActions to cheaply check RestoreCommitAction enablement.
     */
    private volatile ObjectId fCachedHeadId;
    
    /**
     * Cached enabled states computed by selectionChanged bg thread.
     * updateActions reuses these instead of recomputing on each commit click.
     */
    private volatile boolean fCachedUndoEnabled;
    private volatile boolean fCachedResetEnabled;

    
    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout());
        
        // Create Info Section
        createInfoSection(parent);
        
        // Create History Table and Comment Viewer
        createHistorySection(parent);

        makeActions();
        hookContextMenu();
        //makeLocalMenuActions();
        makeLocalToolBarActions();
        
        // Register us as a selection provider so that Actions can pick us up
        getSite().setSelectionProvider(getHistoryViewer());
        
        // Listen to workbench selections
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(this);

        // Register Help Context
        PlatformUI.getWorkbench().getHelpSystem().setHelp(getHistoryViewer().getControl(), HELP_ID);
        
        // Initialise with whatever is selected in the workbench
        selectionChanged(getSite().getWorkbenchWindow().getPartService().getActivePart(),
                getSite().getWorkbenchWindow().getSelectionService().getSelection());
        
        // Add listener
        RepositoryListenerManager.INSTANCE.addListener(this);
    }
    
    private void createInfoSection(Composite parent) {
        Composite mainComp = new Composite(parent, SWT.NONE);
        mainComp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        mainComp.setLayout(layout);

        // Repository name
        fRepoLabel = new Label(mainComp, SWT.NONE);
        fRepoLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        fRepoLabel.setText(Messages.HistoryView_0);
        
        // Branches
        Label label = new Label(mainComp, SWT.NONE);
        label.setText(Messages.HistoryView_2);

        fBranchesViewer = new BranchesViewer(mainComp);
        GridData gd = new GridData(SWT.END);
        fBranchesViewer.getControl().setLayoutData(gd);

        /*
         * Listen to Branch Selections and forward on to History Viewer
         */
        fBranchesViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                BranchInfo branchInfo = (BranchInfo)event.getStructuredSelection().getFirstElement();
                getHistoryViewer().setSelectedBranch(branchInfo);
                updateActions();
            }
        });
    }
    
    private void createHistorySection(Composite parent) {
        SashForm tableSash = new SashForm(parent, SWT.VERTICAL);
        tableSash.setLayoutData(new GridData(GridData.FILL_BOTH));
        
        Composite tableComp = new Composite(tableSash, SWT.NONE);
        tableComp.setLayout(new TableColumnLayout());
        
        // This ensures a minumum and equal size and no horizontal size creep for the table
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 100;
        gd.heightHint = 50;
        tableComp.setLayoutData(gd);
        
        // History Table
        fHistoryTableViewer = new HistoryTableViewer(tableComp);
        
        // Comments Viewer
        fCommentViewer = new RevisionCommentViewer(tableSash);
        
        tableSash.setWeights(new int[] { 80, 20 });
        
        /*
         * Listen to History Selections to update local Actions
         */
        fHistoryTableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                updateActions();
            }
        });
    }
    
    /**
     * Make local actions
     */
    private void makeActions() {
        fActionExtractCommit = new ExtractModelFromCommitAction(getViewSite().getWorkbenchWindow());
        fActionExtractCommit.setEnabled(false);
        
        fActionRestoreCommit = new RestoreCommitAction(getViewSite().getWorkbenchWindow());
        fActionRestoreCommit.setEnabled(false);
        
        fActionUndoLastCommit = new UndoLastCommitAction(getViewSite().getWorkbenchWindow());
        fActionUndoLastCommit.setEnabled(false);
        
        fActionResetToRemoteCommit = new ResetToRemoteCommitAction(getViewSite().getWorkbenchWindow());
        fActionResetToRemoteCommit.setEnabled(false);
        
        // Register the Keybinding for actions
//        IHandlerService service = (IHandlerService)getViewSite().getService(IHandlerService.class);
//        service.activateHandler(fActionRefresh.getActionDefinitionId(), new ActionHandler(fActionRefresh));
    }

    /**
     * Hook into a right-click menu
     */
    private void hookContextMenu() {
        MenuManager menuMgr = new MenuManager("#HistoryPopupMenu"); //$NON-NLS-1$
        menuMgr.setRemoveAllWhenShown(true);
        
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                fillContextMenu(manager);
            }
        });
        
        Menu menu = menuMgr.createContextMenu(getHistoryViewer().getControl());
        getHistoryViewer().getControl().setMenu(menu);
        
        getSite().registerContextMenu(menuMgr, getHistoryViewer());
    }
    
    /**
     * Make Any Local Bar Menu Actions
     */
//    protected void makeLocalMenuActions() {
//        IActionBars actionBars = getViewSite().getActionBars();
//
//        // Local menu items go here
//        IMenuManager manager = actionBars.getMenuManager();
//        manager.add(new Action("&View Management...") {
//            public void run() {
//                MessageDialog.openInformation(getViewSite().getShell(),
//                        "View Management",
//                        "This is a placeholder for the View Management Dialog");
//            }
//        });
//    }

    /**
     * Make Local Toolbar items
     */
    protected void makeLocalToolBarActions() {
        IActionBars bars = getViewSite().getActionBars();
        IToolBarManager manager = bars.getToolBarManager();

        manager.add(new Separator(IWorkbenchActionConstants.NEW_GROUP));
        
        manager.add(fActionExtractCommit);
        manager.add(fActionRestoreCommit);
        manager.add(new Separator());
        manager.add(fActionUndoLastCommit);
        manager.add(fActionResetToRemoteCommit);
        
        manager.add(new Separator());
    }
    
    /**
     * Update the Local Actions depending on the local selection.
     * Uses cached enabled states from selectionChanged bg thread.
     * Only RestoreCommitAction needs per-commit check (is commit HEAD?).
     */
    private void updateActions() {
        RevCommit commit = (RevCommit)getHistoryViewer().getStructuredSelection().getFirstElement();
        
        // Store commit in actions (cheap - no shouldBeEnabled triggered)
        fActionExtractCommit.setCommit(commit);
        fActionRestoreCommit.setCommit(commit);
        
        // Also set the commit in the Comment Viewer
        fCommentViewer.setCommit(commit);

        // Check if selected branch is the current branch
        BranchInfo selectedBranch = (BranchInfo)getBranchesViewer().getStructuredSelection().getFirstElement();
        boolean isCurrentBranch = selectedBranch != null && selectedBranch.isCurrentBranch();
        
        // ExtractModelFromCommitAction: cheap check
        fActionExtractCommit.setEnabled(commit != null && fActionExtractCommit.getRepository() != null);
        
        if(!isCurrentBranch) {
            // Quick disable - no git I/O needed
            fActionRestoreCommit.setEnabled(false);
            fActionUndoLastCommit.setEnabled(false);
            fActionResetToRemoteCommit.setEnabled(false);
            return;
        }
        
        // RestoreCommitAction: enabled if commit is not HEAD (use cached HEAD)
        boolean restoreEnabled = false;
        ObjectId headId = fCachedHeadId;
        if(commit != null && headId != null) {
            restoreEnabled = !commit.getId().equals(headId);
        }
        fActionRestoreCommit.setEnabled(restoreEnabled);
        
        // UndoLastCommitAction and ResetToRemoteCommitAction: use cached states
        fActionUndoLastCommit.setEnabled(fCachedUndoEnabled);
        fActionResetToRemoteCommit.setEnabled(fCachedResetEnabled);
    }
    
    private void fillContextMenu(IMenuManager manager) {
        // boolean isEmpty = getViewer().getSelection().isEmpty();

        manager.add(fActionExtractCommit);
        manager.add(fActionRestoreCommit);
        manager.add(new Separator());
        manager.add(fActionUndoLastCommit);
        manager.add(fActionResetToRemoteCommit);
    }

    HistoryTableViewer getHistoryViewer() {
        return fHistoryTableViewer;
    }
    
    BranchesViewer getBranchesViewer() {
        return fBranchesViewer;
    }

    
    @Override
    public void setFocus() {
        if(getHistoryViewer() != null) {
            getHistoryViewer().getControl().setFocus();
        }
    }
    
    @Override
    public void selectionChanged(IWorkbenchPart part, ISelection selection) {
        if(part == null || part == this || selection == null) {
            return;
        }
        
        Object selected = ((IStructuredSelection)selection).getFirstElement();
        
        IArchiRepository selectedRepository = null;
        
        // Repository selected
        if(selected instanceof IArchiRepository) {
            selectedRepository = (IArchiRepository)selected;
        }
        // Model selected, but is it in a git repo?
        else {
            IArchimateModel model = part.getAdapter(IArchimateModel.class);
            if(GraficoUtils.isModelInLocalRepository(model)) {
                selectedRepository = new ArchiRepository(GraficoUtils.getLocalRepositoryFolderForModel(model));
            }
        }
        
        // Update if selectedRepository is different 
        if(selectedRepository != null && !selectedRepository.equals(fSelectedRepository)) {
            // Store last selected
            fSelectedRepository = selectedRepository;

            // Set label text
            fRepoLabel.setText(Messages.HistoryView_0 + " " + selectedRepository.getName()); //$NON-NLS-1$
            
            // Cancel any stale background thread
            Thread oldThread = fCurrentLoadThread;
            if(oldThread != null) {
                oldThread.interrupt();
            }
            
            // Load git data on background thread to keep UI responsive
            final IArchiRepository repo = selectedRepository;
            Thread loadThread = Thread.ofVirtual().name("HistoryView-LoadBranches").start(() -> { //$NON-NLS-1$
                try {
                    long tBg = System.nanoTime();
                    BranchStatus branchStatus = repo.getBranchStatus();
                    UIPerfLogger.log("[HistoryView]", "selectionChanged getBranchStatus", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                    
                    // Pre-compute expensive action enabled states on bg thread
                    long tActions = System.nanoTime();
                    boolean undoEnabled = false;
                    boolean resetEnabled = false;
                    ObjectId headID = null;
                    try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
                        Repository gitRepo = git.getRepository();
                        headID = gitRepo.resolve(IGraficoConstants.HEAD);
                        
                        // UndoLastCommitAction: >1 commits AND head != remote
                        if(headID != null) {
                            try(RevWalk revWalk = new RevWalk(gitRepo)) {
                                revWalk.markStart(revWalk.parseCommit(headID));
                                int count = 0;
                                for(@SuppressWarnings("unused") RevCommit c : revWalk) {
                                    count++;
                                    if(count > 1) {
                                        break;
                                    }
                                }
                                if(count > 1) {
                                    undoEnabled = !repo.isHeadAndRemoteSame();
                                }
                            }
                        }
                        
                        // ResetToRemoteCommitAction: remote branch exists AND head != remote
                        if(branchStatus != null && branchStatus.getCurrentRemoteBranch() != null) {
                            resetEnabled = !repo.isHeadAndRemoteSame();
                        }
                    }
                    UIPerfLogger.log("[HistoryView]", "selectionChanged action enabled computation", tActions); //$NON-NLS-1$ //$NON-NLS-2$
                    
                    // Cache for updateActions() to reuse
                    fCachedHeadId = headID;
                    fCachedUndoEnabled = undoEnabled;
                    fCachedResetEnabled = resetEnabled;
                    
                    final boolean ue = undoEnabled;
                    final boolean rre = resetEnabled;
                    Display display = fRepoLabel.getDisplay();
                    if(!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if(!fRepoLabel.isDisposed() && repo.equals(fSelectedRepository)) {
                                long tUi = System.nanoTime();
                                getHistoryViewer().doSetInput(repo, branchStatus);
                                UIPerfLogger.log("[HistoryView]", "  historyViewer.doSetInput", tUi); //$NON-NLS-1$ //$NON-NLS-2$
                                long t2 = System.nanoTime();
                                getBranchesViewer().doSetInput(branchStatus);
                                UIPerfLogger.log("[HistoryView]", "  branchesViewer.doSetInput", t2); //$NON-NLS-1$ //$NON-NLS-2$
                                
                                // Store repo in actions without expensive shouldBeEnabled
                                fActionExtractCommit.setRepository(repo); // cheap shouldBeEnabled
                                fActionRestoreCommit.setRepositoryQuiet(repo);
                                fActionRestoreCommit.setEnabled(false); // no commit selected yet
                                fActionUndoLastCommit.setRepositoryQuiet(repo);
                                fActionUndoLastCommit.setEnabled(ue);
                                fActionResetToRemoteCommit.setRepositoryQuiet(repo);
                                fActionResetToRemoteCommit.setEnabled(rre);
                                UIPerfLogger.log("[HistoryView]", "selectionChanged UI update total", tUi); //$NON-NLS-1$ //$NON-NLS-2$
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
            fCurrentLoadThread = loadThread;
        }
    }
    
    @Override
    public void repositoryChanged(String eventName, IArchiRepository repository) {
        if(repository.equals(fSelectedRepository)) {
            switch(eventName) {
                case IRepositoryListener.HISTORY_CHANGED:
                    UIPerfLogger.log("[HistoryView]", "repositoryChanged(HISTORY_CHANGED) received"); //$NON-NLS-1$ //$NON-NLS-2$
                    fRepoLabel.setText(Messages.HistoryView_0 + " " + repository.getName()); //$NON-NLS-1$
                    fCommentViewer.setCommit(null);
                    
                    // Cancel any stale background thread
                    Thread oldHistoryThread = fCurrentLoadThread;
                    if(oldHistoryThread != null) {
                        oldHistoryThread.interrupt();
                    }
                    
                    // Load commit history on background thread
                    Thread historyThread = Thread.ofVirtual().name("HistoryView-RefreshHistory").start(() -> { //$NON-NLS-1$
                        try {
                            long tBg = System.nanoTime();
                            BranchStatus branchStatus = repository.getBranchStatus();
                            UIPerfLogger.log("[HistoryView]", "HISTORY_CHANGED getBranchStatus", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                            Display display = fRepoLabel.getDisplay();
                            if(!display.isDisposed()) {
                                display.asyncExec(() -> {
                                    if(!fRepoLabel.isDisposed() && repository.equals(fSelectedRepository)) {
                                        long tUi = System.nanoTime();
                                        getHistoryViewer().doSetInput(repository, branchStatus);
                                        UIPerfLogger.log("[HistoryView]", "HISTORY_CHANGED UI update", tUi); //$NON-NLS-1$ //$NON-NLS-2$
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
                    fCurrentLoadThread = historyThread;
                    break;
                    
                case IRepositoryListener.REPOSITORY_DELETED:
                    fRepoLabel.setText(Messages.HistoryView_0);
                    getHistoryViewer().setInput(""); //$NON-NLS-1$
                    fSelectedRepository = null; // Reset this
                    break;
                    
                case IRepositoryListener.REPOSITORY_CHANGED:
                    fRepoLabel.setText(Messages.HistoryView_0 + " " + repository.getName()); //$NON-NLS-1$
                    break;

                case IRepositoryListener.BRANCHES_CHANGED:
                    UIPerfLogger.log("[HistoryView]", "repositoryChanged(BRANCHES_CHANGED) received"); //$NON-NLS-1$ //$NON-NLS-2$
                    // Cancel any stale background thread
                    Thread oldBranchThread = fCurrentLoadThread;
                    if(oldBranchThread != null) {
                        oldBranchThread.interrupt();
                    }
                    
                    // Load branch data on background thread
                    Thread branchThread = Thread.ofVirtual().name("HistoryView-RefreshBranches").start(() -> { //$NON-NLS-1$
                        try {
                            long tBg = System.nanoTime();
                            BranchStatus branchStatus = repository.getBranchStatus();
                            UIPerfLogger.log("[HistoryView]", "BRANCHES_CHANGED getBranchStatus", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                            Display display = fRepoLabel.getDisplay();
                            if(!display.isDisposed()) {
                                display.asyncExec(() -> {
                                    if(!fRepoLabel.isDisposed() && repository.equals(fSelectedRepository)) {
                                        long tUi = System.nanoTime();
                                        getHistoryViewer().doSetInput(repository, branchStatus);
                                        getBranchesViewer().doSetInput(branchStatus);
                                        UIPerfLogger.log("[HistoryView]", "BRANCHES_CHANGED UI update", tUi); //$NON-NLS-1$ //$NON-NLS-2$
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
                    fCurrentLoadThread = branchThread;
                    break;
                    
                default:
                    break;
            }
        }
    }
    
    /**
     * Return null so that the Properties View displays "The active part does not provide properties" instead of a table
     */
    @Override
    public IWorkbenchPart getContributingPart() {
        return null;
    }
    
    @Override
    public void dispose() {
        super.dispose();
        getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(this);
        RepositoryListenerManager.INSTANCE.removeListener(this);
    }
    

    // =================================================================================
    //                       Contextual Help support
    // =================================================================================
    
    @Override
    public int getContextChangeMask() {
        return NONE;
    }

    @Override
    public IContext getContext(Object target) {
        return HelpSystem.getContext(HELP_ID);
    }

    @Override
    public String getSearchExpression(Object target) {
        return Messages.HistoryView_1;
    }
}
