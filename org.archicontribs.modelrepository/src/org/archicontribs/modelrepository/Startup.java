/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository;

import org.archicontribs.modelrepository.authentication.GitCredentialManagerDetector;
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;


/**
 * Early Startup class - shakes the plugin to come alive!
 * Implement IStartup so that Menu Items are initialised.
 * Also performs first-run GCM (Git Credential Manager) detection and onboarding.
 * 
 * @author Phillip Beauvoir
 */
public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        // Run GCM onboarding check on the UI thread once workbench is ready
        if(PlatformUI.isWorkbenchRunning()) {
            Display.getDefault().asyncExec(this::checkGCMOnboarding);
        }
    }
    
    /**
     * Check if GCM is available and the user hasn't been asked about it yet.
     * If so, show a dialog explaining the benefits and offering to enable it.
     */
    private void checkGCMOnboarding() {
        IPreferenceStore store = ModelRepositoryPlugin.getInstance().getPreferenceStore();
        
        // Already shown onboarding dialog — don't ask again
        if(store.getBoolean(IPreferenceConstants.PREFS_GCM_ONBOARDING_SHOWN)) {
            return;
        }
        
        // Already explicitly using GCM — no need to ask
        if(IPreferenceConstants.HTTP_AUTH_GCM.equals(store.getString(IPreferenceConstants.PREFS_HTTP_AUTH_METHOD))) {
            store.setValue(IPreferenceConstants.PREFS_GCM_ONBOARDING_SHOWN, true);
            return;
        }
        
        // Check if GCM is available on this system
        if(!GitCredentialManagerDetector.isGCMAvailable()) {
            return; // GCM not installed — nothing to offer
        }
        
        // GCM is available — show the onboarding dialog
        boolean useGCM = MessageDialog.openQuestion(
                Display.getDefault().getActiveShell(),
                Messages.Startup_0,
                Messages.Startup_1);
        
        // Record that we've shown the dialog
        store.setValue(IPreferenceConstants.PREFS_GCM_ONBOARDING_SHOWN, true);
        
        if(useGCM) {
            store.setValue(IPreferenceConstants.PREFS_HTTP_AUTH_METHOD, IPreferenceConstants.HTTP_AUTH_GCM);
        }
        // If they decline, the default PAT method remains — no further prompts
    }
}
