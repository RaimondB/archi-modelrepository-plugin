/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.dialogs;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * New Model Repo Dialog
 * 
 * @author Phillip Beauvoir
 */
public class NewModelRepoDialog extends CloneInputDialog {

    public NewModelRepoDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.NewModelRepoDialog_0);
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Control control = super.createDialogArea(parent);
        setTitle(Messages.NewModelRepoDialog_0);
        return control;
    }
}