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
import org.archicontribs.modelrepository.authentication.CredentialsAuthenticator;
import org.archicontribs.modelrepository.authentication.ProxyAuthenticator;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.archicontribs.modelrepository.services.MergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService.PublishResult;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.model.IArchimateModel;

/**
 * Push Model Action ("Publish")
 * 
 * 1. Do actions in Refresh Model Action (init)
 * 2. Pull + merge via RepositoryService
 * 3. If OK then Push to Remote
 * 
 * @author Phillip Beauvoir
 */
public class PushModelAction extends RefreshModelAction {
    
    public PushModelAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_PUSH));
        setText(Messages.PushModelAction_0);
        setToolTipText(Messages.PushModelAction_0);
    }

    public PushModelAction(IWorkbenchWindow window, IArchimateModel model) {
        super(window, model);
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
            ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(fWindow.getShell());
            
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        monitor.beginTask(Messages.PushModelAction_1, -1);
                        
                        // Update Proxy
                        ProxyAuthenticator.update(getRepository().getOnlineRepositoryURL());
                        
                        // Pull + Push via service
                        PublishResult result = repositoryService.publish(
                                getRepository(), npw, mergeHandler, monitor);
                        
                        // Show push errors if any
                        if(result.status() == PublishResult.Status.PUSH_ERROR && result.pushErrors() != null) {
                            Display.getDefault().syncExec(() -> {
                                displayErrorDialog(Messages.PushModelAction_0, result.pushErrors());
                            });
                        }
                    }
                    catch(Exception ex) {
                        Display.getDefault().syncExec(() -> {
                            displayErrorDialog(Messages.PushModelAction_0, ex);
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
                            // Clear credentials
                            if(npw != null) {
                                npw.clear();
                            }
                            
                            // Clear Proxy
                            ProxyAuthenticator.clear();
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
            displayErrorDialog(Messages.PushModelAction_0, ex);
        }
    }
}
