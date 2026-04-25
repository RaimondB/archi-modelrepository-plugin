/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.views.branches;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.actions.AddBranchAction;
import org.archicontribs.modelrepository.actions.DeleteBranchAction;
import org.archicontribs.modelrepository.actions.DeleteStaleBranchesAction;
import org.archicontribs.modelrepository.actions.MergeBranchAction;
import org.archicontribs.modelrepository.actions.SwitchBranchAction;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
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
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.IContributedContentsView;
import org.eclipse.ui.part.ViewPart;

import com.archimatetool.model.IArchimateModel;


/**
 * Branches Viewpart
 */
public class BranchesView
extends ViewPart
implements IContextProvider, ISelectionListener, IRepositoryListener, IContributedContentsView {

	public static String ID = ModelRepositoryPlugin.PLUGIN_ID + ".branchesView"; //$NON-NLS-1$
    public static String HELP_ID = ModelRepositoryPlugin.PLUGIN_ID + ".branchesViewHelp"; //$NON-NLS-1$
    
    private IArchiRepository fSelectedRepository;
    
    private Label fRepoLabel;
    private BranchesTableViewer fBranchesTableViewer;
    
    /**
     * Tracks the current background loading thread so stale threads can be detected.
     */
    private volatile Thread fCurrentLoadThread;
    
    private AddBranchAction fActionAddBranch;
    private SwitchBranchAction fActionSwitchBranch;
    private MergeBranchAction fActionMergeBranch;
    private DeleteBranchAction fActionDeleteBranch;
    private DeleteStaleBranchesAction fActionDeleteStaleBranches;
    
    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout());
        
        // Create Info Section
        createInfoSection(parent);

        // Create Table Section
        createTableSection(parent);
        
        makeActions();
        hookContextMenu();
        makeLocalToolBarActions();
        
        // Register us as a selection provider so that Actions can pick us up
        getSite().setSelectionProvider(getBranchesViewer());
        
        // Listen to workbench selections
        getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(this);

        // Register Help Context
        PlatformUI.getWorkbench().getHelpSystem().setHelp(getBranchesViewer().getControl(), HELP_ID);
        
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
        fRepoLabel.setText(Messages.BranchesView_0);
    }

    private void createTableSection(Composite parent) {
        Composite tableComp = new Composite(parent, SWT.NONE);
        tableComp.setLayout(new TableColumnLayout());
        
        // This ensures a minumum and equal size and no horizontal size creep for the table
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 100;
        gd.heightHint = 50;
        tableComp.setLayoutData(gd);
        
        // Branches Table
        fBranchesTableViewer = new BranchesTableViewer(tableComp);
        
        /*
         * Listen to Table Selections to update local Actions
         */
        fBranchesTableViewer.addSelectionChangedListener((event) -> {
            updateActions();
        });
        
        fBranchesTableViewer.addDoubleClickListener((event) -> {
            if(fActionSwitchBranch.isEnabled()) {
                fActionSwitchBranch.run();
            }
        });
    }
    
    /**
     * Make local actions
     */
    private void makeActions() {
        fActionAddBranch = new AddBranchAction(getViewSite().getWorkbenchWindow());
        fActionAddBranch.setEnabled(false);
        
        fActionSwitchBranch = new SwitchBranchAction(getViewSite().getWorkbenchWindow());
        fActionSwitchBranch.setEnabled(false);
        
        fActionMergeBranch = new MergeBranchAction(getViewSite().getWorkbenchWindow());
        fActionMergeBranch.setEnabled(false);
        
        fActionDeleteBranch = new DeleteBranchAction(getViewSite().getWorkbenchWindow());
        fActionDeleteBranch.setEnabled(false);
        
        fActionDeleteStaleBranches = new DeleteStaleBranchesAction(getViewSite().getWorkbenchWindow());
        fActionDeleteStaleBranches.setEnabled(false);
    }

    /**
     * Hook into a right-click menu
     */
    private void hookContextMenu() {
        MenuManager menuMgr = new MenuManager("#BranchesPopupMenu"); //$NON-NLS-1$
        menuMgr.setRemoveAllWhenShown(true);
        
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                fillContextMenu(manager);
            }
        });
        
        Menu menu = menuMgr.createContextMenu(getBranchesViewer().getControl());
        getBranchesViewer().getControl().setMenu(menu);
        
        getSite().registerContextMenu(menuMgr, getBranchesViewer());
    }
    
    /**
     * Make Local Toolbar items
     */
    protected void makeLocalToolBarActions() {
        IActionBars bars = getViewSite().getActionBars();
        IToolBarManager manager = bars.getToolBarManager();

        manager.add(fActionAddBranch);
        manager.add(fActionSwitchBranch);
        manager.add(fActionMergeBranch);
        manager.add(new Separator());
        manager.add(fActionDeleteBranch);
        //manager.add(fActionDeleteStaleBranches);
    }
    
    /**
     * Update the Local Actions depending on the local selection 
     * @param selection
     */
    private void updateActions() {
        BranchInfo branchInfo = (BranchInfo)getBranchesViewer().getStructuredSelection().getFirstElement();
        fActionSwitchBranch.setBranch(branchInfo);
        fActionAddBranch.setBranch(branchInfo);
        fActionMergeBranch.setBranch(branchInfo);
        fActionDeleteBranch.setBranch(branchInfo);
        fActionDeleteStaleBranches.setSelection(getBranchesViewer().getStructuredSelection());
    }
    
    private void fillContextMenu(IMenuManager manager) {
        // boolean isEmpty = getViewer().getSelection().isEmpty();

        manager.add(fActionAddBranch);
        manager.add(fActionSwitchBranch);
        manager.add(fActionMergeBranch);
        manager.add(new Separator());
        manager.add(fActionDeleteBranch);
        manager.add(fActionDeleteStaleBranches);
    }

    @Override
    public void setFocus() {
        if(getBranchesViewer() != null) {
            getBranchesViewer().getControl().setFocus();
        }
    }
    
    BranchesTableViewer getBranchesViewer() {
        return fBranchesTableViewer;
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
            fRepoLabel.setText(Messages.BranchesView_0 + " " + selectedRepository.getName()); //$NON-NLS-1$
            
            // Cancel any stale background thread
            Thread oldThread = fCurrentLoadThread;
            if(oldThread != null) {
                oldThread.interrupt();
            }
            
            // Load branch data on background thread to keep UI responsive
            final IArchiRepository repo = selectedRepository;
            Thread loadThread = Thread.ofVirtual().name("BranchesView-LoadBranches").start(() -> { //$NON-NLS-1$
                try {
                    long tBg = System.nanoTime();
                    BranchStatus branchStatus = repo.getBranchStatus();
                    UIPerfLogger.log("[BranchesView]", "selectionChanged getBranchStatus", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                    Display display = fRepoLabel.getDisplay();
                    if(!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if(!fRepoLabel.isDisposed() && repo.equals(fSelectedRepository)) {
                                long tUi = System.nanoTime();
                                getBranchesViewer().doSetInput(repo, branchStatus);
                                
                                fActionAddBranch.setRepository(repo);
                                fActionSwitchBranch.setRepository(repo);
                                fActionMergeBranch.setRepository(repo);
                                fActionDeleteBranch.setRepository(repo);
                                fActionDeleteStaleBranches.setRepository(repo);
                                UIPerfLogger.log("[BranchesView]", "selectionChanged UI update", tUi); //$NON-NLS-1$ //$NON-NLS-2$
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
                case IRepositoryListener.BRANCHES_CHANGED:
                    UIPerfLogger.log("[BranchesView]", "repositoryChanged(" + eventName + ") received"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // Cancel any stale background thread
                    Thread oldThread = fCurrentLoadThread;
                    if(oldThread != null) {
                        oldThread.interrupt();
                    }
                    
                    // Load branch data on background thread
                    Thread loadThread = Thread.ofVirtual().name("BranchesView-RefreshBranches").start(() -> { //$NON-NLS-1$
                        try {
                            long tBg = System.nanoTime();
                            BranchStatus branchStatus = repository.getBranchStatus();
                            UIPerfLogger.log("[BranchesView]", "repositoryChanged getBranchStatus", tBg); //$NON-NLS-1$ //$NON-NLS-2$
                            Display display = fRepoLabel.getDisplay();
                            if(!display.isDisposed()) {
                                display.asyncExec(() -> {
                                    if(!fRepoLabel.isDisposed() && repository.equals(fSelectedRepository)) {
                                        long tUi = System.nanoTime();
                                        getBranchesViewer().doSetInput(repository, branchStatus);
                                        UIPerfLogger.log("[BranchesView]", "repositoryChanged UI update", tUi); //$NON-NLS-1$ //$NON-NLS-2$
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
                    break;
                    
                case IRepositoryListener.REPOSITORY_DELETED:
                    fRepoLabel.setText(Messages.BranchesView_0);
                    getBranchesViewer().setInput(""); //$NON-NLS-1$
                    fSelectedRepository = null; // Reset this
                    break;
                    
                case IRepositoryListener.REPOSITORY_CHANGED:
                    fRepoLabel.setText(Messages.BranchesView_0 + " " + repository.getName()); //$NON-NLS-1$
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
        return Messages.BranchesView_1;
    }
}
