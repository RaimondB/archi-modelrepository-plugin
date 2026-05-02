/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.GeneralSecurityException;
import java.util.concurrent.CancellationException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.authentication.CredentialsAuthenticator;
import org.archicontribs.modelrepository.authentication.ProxyAuthenticator;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.services.MergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.RefreshResult;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Refresh model action
 * 
 * 1. Offer to save the model
 * 2. If there are changes offer to Commit
 * 3. Get credentials for Pull
 * 4. Check Proxy
 * 5. Pull from Remote (via RepositoryService)
 * 6. Handle Merge conflicts (via InteractiveMergeHandler)
 * 7. Reload temp file from Grafico files
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Phillip Beauvoir
 */
public class RefreshModelAction extends AbstractModelAction {
    
    protected static final int PULL_STATUS_ERROR = -1;
    protected static final int PULL_STATUS_OK = 0;
    protected static final int PULL_STATUS_UP_TO_DATE = 1;
    protected static final int PULL_STATUS_MERGE_CANCEL = 2;
    
    protected static final int USER_OK = 0;
    protected static final int USER_CANCEL = 1;
    
    protected final RepositoryService repositoryService = new RepositoryService();
    
    public RefreshModelAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_REFRESH));
        setText(Messages.RefreshModelAction_0);
        setToolTipText(Messages.RefreshModelAction_0);
    }
    
    public RefreshModelAction(IWorkbenchWindow window, IArchimateModel model) {
        this(window);
        if(model != null) {
            setRepository(new ArchiRepository(GraficoUtils.getLocalRepositoryFolderForModel(model)));
        }
    }
    
    @Override
    public void run() {
        try {
            int status = init();
            if(status != USER_OK) {
                return;
            }
            
            // Check primary key set (only needed for PAT auth, not GCM)
            if(!CredentialsAuthenticator.checkPrimaryKeyIfNeeded()) {
                return;
            }

            // Get credentials before opening the progress dialog
            UsernamePassword npw = getUsernamePassword();
            
            // Create the merge handler for interactive mode
            MergeHandler mergeHandler = new InteractiveMergeHandler(fWindow.getShell());

            // Do main action with PM dialog — run on background thread (true)
            // null parent avoids Windows WM_ACTIVATE/WM_DEACTIVATE cycle that causes maximize/restore flash
            UIPerfLogger.log("[Refresh]", "ProgressMonitorDialog opening, shell.maximized=" + fWindow.getShell().getMaximized()); //$NON-NLS-1$
            ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(null);
            
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        // Update Proxy
                        ProxyAuthenticator.update(getRepository().getOnlineRepositoryURL());
                        
                        monitor.beginTask(Messages.RefreshModelAction_5, -1);
                        RefreshResult result = repositoryService.refresh(
                                getRepository(), npw, mergeHandler, monitor);
                        
                        if(result.status() == RefreshResult.Status.UP_TO_DATE) {
                            Display.getDefault().syncExec(() -> {
                                MessageDialog.openInformation(fWindow.getShell(), Messages.RefreshModelAction_0, Messages.RefreshModelAction_2);
                            });
                        }
                    }
                    catch(Exception ex) {
                        Display.getDefault().syncExec(() -> {
                            displayErrorDialog(Messages.RefreshModelAction_0, ex);
                        });
                    }
                    finally {
                        try {
                            Display.getDefault().syncExec(() -> {
                                try {
                                    saveChecksumAndNotifyListeners();
                                }
                                catch(IOException ex) {
                                    ex.printStackTrace();
                                }
                            });
                        }
                        finally {
                            // Clear Proxy
                            ProxyAuthenticator.clear();
                            
                            // Clear credentials
                            if(npw != null) {
                                npw.clear();
                            }
                        }
                    }
                }
            });
        }
        catch(CancellationException ex) {
            // User cancelled the credentials dialog
        }
        catch(GeneralSecurityException ex) {
            displayCredentialsErrorDialog(ex);
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.RefreshModelAction_0, ex);
        }
    }
    
    protected int init() throws IOException, GitAPIException {
        // Offer to save the model if open and dirty
        // We need to do this to keep grafico and temp files in sync
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return USER_CANCEL;
            }
        }
        
        // Do the Grafico Export first
        getRepository().exportModelToGraficoFiles();
        
        // Then offer to Commit
        boolean hasChanges = getRepository().hasChangesToCommit();
        
        if(hasChanges) {
            if(!offerToCommitChanges()) {
                // User cancelled commit dialog - reset staged changes
                getRepository().resetToRef(IGraficoConstants.HEAD);
                return USER_CANCEL;
            }
            notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
        }
        
        return USER_OK;
    }
}
